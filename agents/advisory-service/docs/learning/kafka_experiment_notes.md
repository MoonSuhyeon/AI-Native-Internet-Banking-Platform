# Kafka 실험 노트

> 각 Phase 실험 후 결과를 여기에 기록.

---

## L1 — 토픽 거버넌스

**관찰 명령**
```bash
docker exec ib-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --describe \
  --topic advisory.quarantine.triggered.v1
```

| 항목 | 기대값 | 실측값 |
|---|---|---|
| partitions | 3 | |
| replication-factor | 1 | |
| retention.ms | 2592000000 (30일) | |

**auto-create 꺼진 상태에서 토픽 없이 발행 시 오류 메시지:**
```
(여기에 붙여넣기)
```

**학습 결론:**

---

## L2 — Producer 고급 설정

**실험 1: linger.ms=0 vs 20**

| linger.ms | 처리량(msg/s) | 평균 지연(ms) | batch-size-avg |
|---|---|---|---|
| 0 | | | |
| 20 | | | |

**실험 2: compression.type 비교** (1000건 기준)

| compression.type | payload 크기(bytes) | 압축률 | CPU 사용률 |
|---|---|---|---|
| none | | | |
| lz4 | | | |
| snappy | | | |
| zstd | | | |

**학습 결론:**

---

## L6 — Consumer 기본

**실험 1: auto.offset.reset=earliest vs latest**

| 설정 | 기존 메시지 수신 여부 | 첫 수신 offset |
|---|---|---|
| earliest | | |
| latest | | |

**실험 2: 수동 ack 전 재시작 시 offset 위치**
- 재시작 전 처리한 마지막 offset:
- 재시작 후 처음 수신한 offset:
- 중복 처리된 메시지 수:

**실험 3: DLQ 격리**
- 강제 예외 발생 후 DLQ 토픽 메시지 확인:
```bash
docker exec ib-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic advisory.quarantine.triggered.v1.dlq --from-beginning
```
결과:

**학습 결론:**

---

## L7 — Consumer 성능/장애 튜닝

**실험 1: fetch-min-bytes 조정**

| fetch-min-bytes | fetch-max-wait-ms | 평균 지연(ms) | 처리량(msg/s) |
|---|---|---|---|
| 1 | 500 | | |
| 10240 | 500 | | |

**실험 2: max.poll.interval.ms 초과 → rebalance**
- experiment-delay-ms 설정값:
- max-poll-interval-ms 설정값:
- rebalance 발생까지 걸린 시간:
- 로그에서 확인한 rebalance 메시지:
```
(여기에 붙여넣기)
```

**실험 3: max-poll-records 조정**

| max-poll-records | 처리 시간/배치(ms) | interval 초과 여부 |
|---|---|---|
| 500 | | |
| 5000 | | |

**학습 결론:**

---

## L4 — 커스텀 파티셔너

**실험 1: 기본 파티셔너 (use-skew-aware-partitioner=false)**
```bash
docker exec ib-kafka /opt/kafka/bin/kafka-run-class.sh \
  kafka.tools.GetOffsetShell \
  --bootstrap-server localhost:9092 \
  --topic advisory.test.skew.v1
```

| partition | 메시지 수 |
|---|---|
| 0 | |
| 1 | |
| 2 | |

**실험 2: SkewAwarePartitioner (use-skew-aware-partitioner=true)**

| partition | 메시지 수 |
|---|---|
| 0 | |
| 1 | |
| 2 | |

**hot-key(reviewerId 1, 2, 100) 분포 비교:**
- 기본 파티셔너: 쏠린 파티션 =
- SkewAwarePartitioner: 분산 결과 =

**순서 보장 손실 확인 여부 (hot-key 메시지가 여러 파티션에 분산됨):**

**학습 결론:**

---

## L8 — Partition Assignment + Static Membership

**실험 준비: 컨슈머 3개 띄우기**
```bash
# 터미널 3개에서 각각 다른 INSTANCE_ID로 기동
ADVISORY_CONSUMER_INSTANCE_ID=advisory-consumer-0 ./gradlew :services:loan-service:bootRun
ADVISORY_CONSUMER_INSTANCE_ID=advisory-consumer-1 ./gradlew :services:loan-service:bootRun
ADVISORY_CONSUMER_INSTANCE_ID=advisory-consumer-2 ./gradlew :services:loan-service:bootRun

# 파티션 할당 확인
docker exec ib-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --describe --group advisory-quarantine-notifier
```

**실험 1: Assignment Strategy 비교** (컨슈머 1→2→3 순차 추가)

| strategy | 컨슈머 추가 시 rebalance 중 처리 정지 여부 | 할당 균등도 |
|---|---|---|
| RangeAssignor | | |
| RoundRobinAssignor | | |
| StickyAssignor | | |
| CooperativeStickyAssignor | | |

**rebalance 로그 (CooperativeSticky vs Sticky 비교):**
```
(여기에 붙여넣기)
```

**실험 2: Static Membership**
- `group-instance-id` 설정 전 재시작 시 rebalance 발생 여부:
- `group-instance-id` 설정 후 재시작 시 rebalance 발생 여부:
- session.timeout.ms(45s) 내 복귀 vs 초과 시 동작 차이:

**실험 3: offsets.retention.minutes**
```bash
# 그룹 정지 후 기본값(1440분=1일) vs 10080분(7일) 비교
# 그룹 offset 확인
docker exec ib-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --describe --group advisory-quarantine-notifier
```
- 그룹 정지 후 offset 유지 시간:
- 재시작 후 auto.offset.reset 동작 여부:

**학습 결론:**

---

## L3 — Avro + Schema Registry

**실험 준비**
```bash
# schema-registry 기동 확인
curl http://localhost:8081/subjects
```

**실험 1: String → Avro 마이그레이션**
```yaml
# application.yml
advisory.kafka.use-avro: false  # 1단계: String 발행
advisory.kafka.use-avro: true   # 2단계: Avro 발행
```
- String 발행 시 kafka-console-consumer 출력:
- Avro 발행 시 kafka-console-consumer 출력 (바이너리):
- Schema Registry에 등록된 스키마 확인:
```bash
curl http://localhost:8081/subjects
curl http://localhost:8081/subjects/advisory.quarantine.triggered.v1-value/versions/latest
```
결과:

**실험 2: payload 크기 비교**

| 직렬화 | 메시지 크기(bytes) |
|---|---|
| JSON String | |
| Avro binary | |

**실험 3: Schema Evolution — BACKWARD 호환성**
```bash
# v2 스키마(severity 필드 추가) 등록 — BACKWARD 호환 → 성공해야 함
curl -X POST http://localhost:8081/subjects/advisory.quarantine.triggered.v1-value/versions \
  -H "Content-Type: application/vnd.schemaregistry.v1+json" \
  -d "{\"schema\": $(cat services/advisory-service/src/main/avro/quarantine_triggered_v2.avsc | jq -Rs .)}"
```
결과 (version id):

**실험 4: 호환성 위반 시도 — 필수 필드 삭제**
```bash
# revId 필드 삭제한 스키마 등록 → BACKWARD 위반 → 실패해야 함
curl -X POST http://localhost:8081/compatibility/subjects/advisory.quarantine.triggered.v1-value/versions/latest \
  -H "Content-Type: application/vnd.schemaregistry.v1+json" \
  -d '{"schema": "{\"type\":\"record\",\"name\":\"QuarantineTriggered\",\"namespace\":\"com.bank.loan.advisory.avro\",\"fields\":[{\"name\":\"conclusionCd\",\"type\":\"string\"}]}"}'
```
결과 (INCOMPATIBLE 응답 확인):

**학습 결론:**

---

## L5 — Quota / Throttling

**구현 위치**
- `AdvisoryKafkaQuotaManager` — AdminClient로 quota 설정·조회·해제
- `InternalAdvisoryToolController` — POST/GET/DELETE `/api/internal/advisory/quota`

**Quota 종류**

| 키 | 단위 | 대상 |
|---|---|---|
| `producer_byte_rate` | bytes/s | 특정 client-id의 producer 전송률 상한 |
| `consumer_byte_rate` | bytes/s | 특정 client-id의 consumer 수신률 상한 |
| `request_percentage` | % (0~100) | 브로커 I/O·네트워크 스레드 점유 비율 상한 |

**실험 1: producer throttle**

설정:
```bash
# HTTP API로 설정 (애플리케이션 기동 중)
curl -X POST "http://localhost:8083/api/internal/advisory/quota?clientId=advisory-producer&producerByteRate=1024"
```
또는 직접:
```bash
docker exec ib-kafka /opt/kafka/bin/kafka-configs.sh \
  --bootstrap-server localhost:9092 \
  --entity-type clients --entity-name advisory-producer \
  --alter --add-config producer_byte_rate=1024
```

현재 quota 확인:
```bash
curl http://localhost:8083/api/internal/advisory/quota
# 또는
docker exec ib-kafka /opt/kafka/bin/kafka-configs.sh \
  --bootstrap-server localhost:9092 --entity-type clients --describe
```

| client-id | producerByteRate(설정) | throttle-time-avg 관찰값(ms) | 실제 처리량(msg/s) |
|---|---|---|---|
| advisory-producer | 제한 없음 | | |
| advisory-producer | 1024 B/s | | |
| advisory-producer | 102400 B/s | | |

**실험 2: consumer throttle**

```bash
curl -X POST "http://localhost:8083/api/internal/advisory/quota?clientId=advisory-quarantine-notifier&consumerByteRate=1024"
```

| client-id | consumerByteRate(설정) | consumer lag 변화 | fetch-rate 변화 |
|---|---|---|---|
| advisory-quarantine-notifier | 제한 없음 | | |
| advisory-quarantine-notifier | 1024 B/s | | |

**실험 3: request_percentage throttle**

```bash
curl -X POST "http://localhost:8083/api/internal/advisory/quota?clientId=advisory-producer&requestPercentage=1"
```

| requestPercentage | 브로커 응답 지연 증가 여부 | producer 에러 발생 여부 |
|---|---|---|
| 100 (무제한) | | |
| 1 | | |

**quota 해제**
```bash
curl -X DELETE "http://localhost:8083/api/internal/advisory/quota?clientId=advisory-producer"
curl -X DELETE "http://localhost:8083/api/internal/advisory/quota?clientId=advisory-quarantine-notifier"
```

**학습 결론:**
