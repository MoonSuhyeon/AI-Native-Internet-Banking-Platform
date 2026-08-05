#!/usr/bin/env bash
# services/payment-service/infra/init-topics.sh
#
# 결제계 Kafka 토픽 초기 생성 스크립트 (단일 broker 버전)
# 기준: 결제계_Kafka토픽정의서_v3.1.xlsx 시트 2 (토픽_종합표) — 총 18개
#
# ── 토폴로지 변경 안내 ────────────────────────────────────────────────────
#   과거: kftc/bok/internal 3개 클러스터(docker-compose-kafka.yml)에 분산.
#   현재: 루트 docker-compose.yml의 단일 broker `ib-kafka`(auto-create=false)에 통합.
#   → 본 스크립트는 docker exec 없이 kafka-topics.sh 를 직접 호출하므로
#     "kafka bin 이 있는 컨테이너 내부"에서 실행되는 것을 전제로 한다.
#
# 토픽 분류:
#   KFTC 외부망  : 2개 일반 + 2개 DLQ = 4개
#   BOK 외부망   : 2개 일반 + 2개 DLQ = 4개
#   내부 이벤트   : 3개 일반 + 3개 DLQ      = 6개
#   수신계 이벤트 : 4개 일반 (DLQ 없음)      = 4개
#   합계: 18개
#
#   ★ account.*.dlq 미포함: 토픽정의서 시트 6(DLQ_토픽) 미정의.
#     시트 11 주석 "캐시무효화이라 DLQ 불필요". 필요 시 별도 추가.
#
# ── 실행 방법 ─────────────────────────────────────────────────────────────
#   (1) compose 원샷 서비스(권장): docker compose up payment-topic-init
#       └ ib-payment-topic-init 가 이 파일을 마운트해 KAFKA_BOOTSTRAP=kafka:29092 로 1회 실행
#   (2) 수동(이미 떠 있는 broker에 직접):
#       docker exec -e KAFKA_BOOTSTRAP=localhost:9092 ib-kafka \
#         bash -c "$(cat services/payment-service/infra/init-topics.sh)"
#
# ── 환경변수 ──────────────────────────────────────────────────────────────
#   KAFKA_BOOTSTRAP   : 부트스트랩 주소 (default localhost:9092)
#   KAFKA_TOPICS_BIN  : kafka-topics.sh 경로 (default /opt/kafka/bin/kafka-topics.sh)

set -euo pipefail

BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:9092}"
KAFKA_TOPICS_BIN="${KAFKA_TOPICS_BIN:-/opt/kafka/bin/kafka-topics.sh}"

# Retention (ms) — 토픽정의서 v3.1 시트 7 (설정_명세)
NORMAL_RETENTION_MS=604800000     # 7일
DLQ_RETENTION_MS=2592000000       # 30일

# ---------------------------------------------------------------------------
# create_topic <topic> <partitions> <replication> <retention_ms>
#   출력: created / exists / FAIL(→exit 1)
#   replication=1 — 단일 broker 환경
# ---------------------------------------------------------------------------
create_topic() {
  local topic=$1 partitions=$2 replication=$3 retention_ms=$4
  local output exit_code=0

  printf "  %-32s (p=%s r=%s) ... " "$topic" "$partitions" "$replication"

  output=$("$KAFKA_TOPICS_BIN" \
      --bootstrap-server "$BOOTSTRAP" \
      --create \
      --if-not-exists \
      --topic "$topic" \
      --partitions "$partitions" \
      --replication-factor "$replication" \
      --config "retention.ms=${retention_ms}" \
      --config "cleanup.policy=delete" \
      2>&1) || exit_code=$?

  if [ "$exit_code" -ne 0 ]; then
    echo "FAIL (exit=${exit_code})"
    echo "    └─ ${output}"
    exit 1
  elif echo "$output" | grep -qi "already exists"; then
    echo "exists (skip)"
  else
    echo "created"
  fi
}

echo "======================================================================"
echo " 결제계 Kafka 토픽 초기화 (단일 broker)"
echo " bootstrap = ${BOOTSTRAP}  |  총 18개"
echo "======================================================================"

# ── broker 준비 대기 (compose healthcheck와 별개의 방어막) ──────────────────
echo "[ broker 응답 대기 ]"
for i in $(seq 1 30); do
  if "$KAFKA_TOPICS_BIN" --bootstrap-server "$BOOTSTRAP" --list >/dev/null 2>&1; then
    echo "  ✅ broker 응답 확인 (${BOOTSTRAP})"
    break
  fi
  if [ "$i" -eq 30 ]; then
    echo "  ❌ broker 미응답: ${BOOTSTRAP} (30회 재시도 실패)"
    exit 1
  fi
  sleep 2
done
echo ""

# ---------------------------------------------------------------------------
# 1. KFTC 외부망 토픽 (시트 3 + 시트 6)
# ---------------------------------------------------------------------------
echo "[ 1. KFTC 외부망 ]"
create_topic "kftc.network.request"       3 1 "$NORMAL_RETENTION_MS"
create_topic "kftc.network.response"      3 1 "$NORMAL_RETENTION_MS"
create_topic "kftc.network.request.dlq"   1 1 "$DLQ_RETENTION_MS"
create_topic "kftc.network.response.dlq"  1 1 "$DLQ_RETENTION_MS"
echo ""

# ---------------------------------------------------------------------------
# 2. BOK 외부망 토픽 (시트 4 + 시트 6)
# ---------------------------------------------------------------------------
echo "[ 2. BOK 외부망 ]"
create_topic "bok.network.request"        3 1 "$NORMAL_RETENTION_MS"
create_topic "bok.network.response"       3 1 "$NORMAL_RETENTION_MS"
create_topic "bok.network.request.dlq"    1 1 "$DLQ_RETENTION_MS"
create_topic "bok.network.response.dlq"   1 1 "$DLQ_RETENTION_MS"
echo ""

# ---------------------------------------------------------------------------
# 3. 내부 이벤트 토픽 (시트 5 + 시트 6)
# ---------------------------------------------------------------------------
echo "[ 3. 내부 이벤트 ]"
create_topic "payment.completed"          3 1 "$NORMAL_RETENTION_MS"
create_topic "payment.failed"             3 1 "$NORMAL_RETENTION_MS"
create_topic "payment.reversed"           3 1 "$NORMAL_RETENTION_MS"
create_topic "payment.completed.dlq"      1 1 "$DLQ_RETENTION_MS"
create_topic "payment.failed.dlq"         1 1 "$DLQ_RETENTION_MS"
create_topic "payment.reversed.dlq"       1 1 "$DLQ_RETENTION_MS"
echo ""

# ---------------------------------------------------------------------------
# 4. 수신계 이벤트 토픽 (시트 11 / Producer: deposit-service, DLQ 없음)
# ---------------------------------------------------------------------------
echo "[ 4. 수신계 이벤트 (deposit→payment) ]"
create_topic "account.status.changed"     3 1 "$NORMAL_RETENTION_MS"
create_topic "account.fraud-reported"     3 1 "$NORMAL_RETENTION_MS"
create_topic "account.holder.changed"     3 1 "$NORMAL_RETENTION_MS"
create_topic "account.closed"             3 1 "$NORMAL_RETENTION_MS"
echo ""

echo "======================================================================"
echo " 토픽 생성 완료 — 총 18개"
echo "======================================================================"
"$KAFKA_TOPICS_BIN" --bootstrap-server "$BOOTSTRAP" --list | grep -E '^(kftc|bok|payment|account)\.' | sort
