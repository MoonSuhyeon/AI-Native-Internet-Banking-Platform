# analytics — 실시간 분석 스택 (Kafka → Spark → Redis)

은행 표준 실시간 분석 스택 데모. **기존 Kafka 이벤트 백본을 Spark Structured Streaming 으로 소비**해
지불자별 거래속도(velocity)를 실시간 집계하고 Redis 에 사기 피처로 기록한다.

> ⚠️ 규모상 기술적 필수는 아니다(합성·단일노드). **"Kafka + Spark 실시간 파이프라인을 구현할 줄 안다"**를
> 보이기 위한 학습/포트폴리오 목적. OLTP 서비스(customer/loan/payment 코어)는 **건드리지 않는다** —
> 기존 `payment.completed` 토픽만 읽는 분석 소비자다.

## 파이프라인
```
payment.completed (Kafka)
   │  readStream(kafka)
   ▼
Spark Structured Streaming
   groupBy(window 5min/slide 1min, senderAccountId)
     → txn_count, amount_sum      (withWatermark 10min)
   ▼  foreachBatch
Redis  HSET fraud:velocity:{account} {txn_count, amount_sum, window_end}
       SADD fraud:velocity:alerts {account}   (threshold 초과 시)
```

## 구성
| 파일 | 역할 |
|---|---|
| `fraud_velocity_stream.py` | PySpark Structured Streaming 잡 |
| `requirements.txt` | pyspark · redis |
| `docker-compose.yml` | Spark 컨테이너(격리 profile) + 체크포인트 볼륨 |

## 재사용 vs 신규
- **재사용**: Kafka(`payment.completed`) · Redis · (선택) Grafana 패널
- **신규**: Spark 컨테이너 1개 · 이 스트리밍 잡 · 체크포인트 볼륨 — 그게 전부

## 실행
### A) 로컬 spark-submit
```bash
pip install -r requirements.txt
export KAFKA_BOOTSTRAP=localhost:9092 REDIS_HOST=localhost
spark-submit --packages org.apache.spark:spark-sql-kafka-0-10_2.12:3.5.1 fraud_velocity_stream.py
```

### B) Docker (루트 스택 위에)
```bash
docker compose up -d kafka redis            # 루트 스택
docker compose -f analytics/docker-compose.yml up
```

## 결과 확인
```bash
redis-cli KEYS 'fraud:velocity:*'
redis-cli HGETALL fraud:velocity:<계좌ID>
redis-cli SMEMBERS fraud:velocity:alerts
```

## 확장 지점
- **경고 재발행**: 임계 초과 계좌를 Kafka `fraud.velocity.alert` 토픽으로 재발행 → fraud-agent 연동
- **피처 영속화**: Redis 대신(또는 함께) Postgres 피처테이블 / parquet 데이터레이크(MinIO)
- **배치 버전**: Kafka→parquet 적재 후 Spark 배치 ETL → `tools/data-tools` XGBoost 학습 피처
- **스키마**: 이벤트가 Avro 라면 Schema Registry 추가(지금은 JSON 파싱)

## 주의
- 이벤트 페이로드 필드명은 `fraud_velocity_stream.py` 의 `SCHEMA` 에서 실제 `payment.completed` 에 맞춰 조정.
- compose 의 `networks.ib.name` 은 루트 스택 네트워크 이름(`docker network ls`)에 맞춘다.
