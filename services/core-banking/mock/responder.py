#!/usr/bin/env python3
"""
Mock KFTC/BOK Responder — 테스트 전용
kftc.network.request  -> SETTLEMENT_NOTIFY    -> kftc.network.response
bok.network.request   -> SETTLEMENT_COMPLETED -> bok.network.response

환경변수:
  KFTC_BOOTSTRAP  (default: kftc-kafka:29092)
  BOK_BOOTSTRAP   (default: bok-kafka:29093)
  SUCCESS_RATE    0.0~1.0  (default: 1.0 = 100% 성공)
  DELAY_MS        응답 지연 ms (default: 300)
"""
import json
import os
import random
import threading
import time
from datetime import datetime

from kafka import KafkaConsumer, KafkaProducer

KFTC_BOOTSTRAP = os.environ.get("KFTC_BOOTSTRAP", "kftc-kafka:29092")
BOK_BOOTSTRAP  = os.environ.get("BOK_BOOTSTRAP",  "bok-kafka:29093")
SUCCESS_RATE   = float(os.environ.get("SUCCESS_RATE", "1.0"))
DELAY_MS       = int(os.environ.get("DELAY_MS", "300"))


def now_str():
    return datetime.now().strftime("%Y%m%d%H%M%S")


def make_producer(bootstrap):
    while True:
        try:
            return KafkaProducer(
                bootstrap_servers=bootstrap,
                value_serializer=lambda v: json.dumps(v).encode(),
                key_serializer=lambda k: k.encode() if k else None,
                acks="all",
            )
        except Exception as e:
            print(f"[Mock] producer 연결 재시도 ({bootstrap}): {e}", flush=True)
            time.sleep(3)


def make_consumer(topic, group_id, bootstrap):
    while True:
        try:
            return KafkaConsumer(
                topic,
                bootstrap_servers=bootstrap,
                group_id=group_id,
                auto_offset_reset="latest",
                enable_auto_commit=True,
                value_deserializer=lambda v: json.loads(v.decode()),
            )
        except Exception as e:
            print(f"[Mock] consumer 연결 재시도 ({bootstrap}/{topic}): {e}", flush=True)
            time.sleep(3)


def handle_kftc():
    consumer = make_consumer("kftc.network.request", "mock-kftc-responder", KFTC_BOOTSTRAP)
    producer = make_producer(KFTC_BOOTSTRAP)
    print(f"[KFTC Mock] Ready  bootstrap={KFTC_BOOTSTRAP}  success_rate={SUCCESS_RATE}", flush=True)

    for record in consumer:
        payload = record.value
        clearing_no = payload.get("clearingNo", "")
        if not clearing_no:
            continue

        print(f"[KFTC Mock] <- request  clearingNo={clearing_no}", flush=True)
        time.sleep(DELAY_MS / 1000.0)

        if random.random() < SUCCESS_RATE:
            resp = {
                "messageType":  "SETTLEMENT_NOTIFY",
                "clearingNo":   clearing_no,
                "responseCode": "0000",
                "settledAt":    now_str(),
            }
            tag = "SUCCESS"
        else:
            resp = {
                "messageType":   "REJECT",
                "clearingNo":    clearing_no,
                "responseCode":  "1001",
                "rejectMessage": "Mock: payment rejected",
                "rejectedAt":    now_str(),
            }
            tag = "FAIL"

        producer.send("kftc.network.response", key=clearing_no, value=resp)
        producer.flush()
        print(f"[KFTC Mock] -> {resp['messageType']}({tag})  clearingNo={clearing_no}", flush=True)


def handle_bok():
    consumer = make_consumer("bok.network.request", "mock-bok-responder", BOK_BOOTSTRAP)
    producer = make_producer(BOK_BOOTSTRAP)
    print(f"[BOK  Mock] Ready  bootstrap={BOK_BOOTSTRAP}  success_rate={SUCCESS_RATE}", flush=True)

    for record in consumer:
        payload = record.value
        bok_ref = payload.get("bokReferenceNo", "")
        if not bok_ref:
            continue

        print(f"[BOK  Mock] <- Request  bokReferenceNo={bok_ref}", flush=True)
        time.sleep(DELAY_MS / 1000.0)

        if random.random() < SUCCESS_RATE:
            resp = {
                "messageType":    "SETTLEMENT_COMPLETED",
                "bokReferenceNo": bok_ref,
                "responseCode":   "0000",
                "settledAt":      now_str(),
            }
            tag = "SUCCESS"
        else:
            resp = {
                "messageType":    "SETTLEMENT_REJECT",
                "bokReferenceNo": bok_ref,
                "responseCode":   "2001",
                "rejectMessage":  "Mock: BOK settlement rejected",
                "rejectedAt":     now_str(),
            }
            tag = "FAIL"

        producer.send("bok.network.response", key=bok_ref, value=resp)
        producer.flush()
        print(f"[BOK  Mock] -> SETTLEMENT_COMPLETED({tag})  bokReferenceNo={bok_ref}", flush=True)


if __name__ == "__main__":
    t_kftc = threading.Thread(target=handle_kftc, daemon=True)
    t_bok  = threading.Thread(target=handle_bok,  daemon=True)
    t_kftc.start()
    t_bok.start()
    t_kftc.join()
    t_bok.join()
