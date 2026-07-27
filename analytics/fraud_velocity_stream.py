#!/usr/bin/env python3
"""결제 스트림 실시간 사기 velocity 피처 — Kafka → Spark Structured Streaming → Redis.

은행 표준 실시간 분석 스택 데모: Kafka(이벤트 백본)와 Spark(윈도우 집계)를 함께 쓴다.
payment.completed 이벤트를 소비해 지불자(senderAccountId)별 최근 윈도우 거래속도를 집계하고,
Redis 에 실시간 사기 피처로 기록한다. 임계 초과 시 velocity 경고를 남긴다.

원칙:
  - OLTP 서비스(customer/loan/payment 코어)는 건드리지 않는다 — 기존 Kafka 토픽만 읽는 분석 소비자.
  - 필요할 때만 기동하는 배치/스트리밍 잡(payment 격리 compose와 동일 철학).

실행(로컬):
  pip install -r requirements.txt
  spark-submit --packages org.apache.spark:spark-sql-kafka-0-10_2.12:3.5.1 fraud_velocity_stream.py

환경변수: KAFKA_BOOTSTRAP · KAFKA_TOPIC · REDIS_HOST · REDIS_PORT · CHECKPOINT_DIR
          WINDOW · SLIDE · VELOCITY_THRESHOLD
"""
import os

from pyspark.sql import SparkSession
from pyspark.sql import functions as F
from pyspark.sql.types import StructType, StructField, StringType, DoubleType, TimestampType

KAFKA_BOOTSTRAP    = os.getenv("KAFKA_BOOTSTRAP", "kafka:29092")
KAFKA_TOPIC        = os.getenv("KAFKA_TOPIC", "payment.completed")
REDIS_HOST         = os.getenv("REDIS_HOST", "redis")
REDIS_PORT         = int(os.getenv("REDIS_PORT", "6379"))
CHECKPOINT_DIR     = os.getenv("CHECKPOINT_DIR", "/checkpoints/fraud-velocity")
WINDOW             = os.getenv("WINDOW", "5 minutes")
SLIDE              = os.getenv("SLIDE", "1 minute")
VELOCITY_THRESHOLD = int(os.getenv("VELOCITY_THRESHOLD", "10"))

# payment.completed 페이로드 스키마 (PaymentInstruction 기준).
# ※ 실제 이벤트 필드명이 다르면 여기만 맞추면 된다(JSON 파싱).
SCHEMA = StructType([
    StructField("paymentInstructionId", StringType()),
    StructField("senderAccountId",      StringType()),
    StructField("transferAmount",       DoubleType()),
    StructField("completedAt",          TimestampType()),
])


def write_batch(batch_df, epoch_id):
    """집계된 마이크로배치를 Redis 실시간 피처로 기록 (+ 임계 초과 경고)."""
    rows = batch_df.collect()
    if not rows:
        return
    import redis  # executor/driver 런타임 의존성
    client = redis.Redis(host=REDIS_HOST, port=REDIS_PORT)
    pipe = client.pipeline()
    for row in rows:
        acct = row["senderAccountId"]
        if acct is None:
            continue
        key = f"fraud:velocity:{acct}"
        pipe.hset(key, mapping={
            "txn_count":  int(row["txn_count"]),
            "amount_sum": float(row["amount_sum"] or 0),
            "window_end": str(row["window_end"]),
        })
        pipe.expire(key, 3600)
        if int(row["txn_count"]) >= VELOCITY_THRESHOLD:
            pipe.sadd("fraud:velocity:alerts", acct)
            print(f"[ALERT] velocity {row['txn_count']} tx / {WINDOW} · acct={acct} · sum={row['amount_sum']}")
    pipe.execute()


def main():
    spark = SparkSession.builder.appName("fraud-velocity-stream").getOrCreate()
    spark.sparkContext.setLogLevel("WARN")

    raw = (spark.readStream.format("kafka")
           .option("kafka.bootstrap.servers", KAFKA_BOOTSTRAP)
           .option("subscribe", KAFKA_TOPIC)
           .option("startingOffsets", "latest")
           .load())

    events = (raw
              .select(F.from_json(F.col("value").cast("string"), SCHEMA).alias("e"))
              .select("e.*")
              # 이벤트 시각 누락 시 처리시각으로 대체(데모 견고성)
              .withColumn("completedAt", F.coalesce(F.col("completedAt"), F.current_timestamp())))

    agg = (events
           .withWatermark("completedAt", "10 minutes")
           .groupBy(F.window("completedAt", WINDOW, SLIDE), F.col("senderAccountId"))
           .agg(F.count("*").alias("txn_count"),
                F.sum("transferAmount").alias("amount_sum"))
           .select(F.col("senderAccountId"),
                   F.col("txn_count"),
                   F.col("amount_sum"),
                   F.col("window.end").alias("window_end")))

    query = (agg.writeStream
             .outputMode("update")
             .foreachBatch(write_batch)
             .option("checkpointLocation", CHECKPOINT_DIR)
             .start())

    print(f"[fraud-velocity] Kafka={KAFKA_BOOTSTRAP}/{KAFKA_TOPIC} → Redis={REDIS_HOST}:{REDIS_PORT} "
          f"· window={WINDOW}/{SLIDE} · threshold={VELOCITY_THRESHOLD}")
    query.awaitTermination()


if __name__ == "__main__":
    main()
