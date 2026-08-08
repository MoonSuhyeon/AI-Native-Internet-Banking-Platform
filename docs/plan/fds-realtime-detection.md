# 실시간 이상거래 탐지 (FDS 탐지 단계)

> 상태: 설계 — 승인 전
> 범위: 결제 이벤트 → 탐지(룰 + 이상탐지) → 기존 조사 에이전트 → HITL 큐
> 도메인: 인증보안계(이상거래) 주도, 결제계는 **읽기 전용 조회 하나만** 추가

---

## 1. 왜 하는가

지금 구조는 **조사(investigation)는 있고 탐지(detection)가 없다.**

조사 에이전트(LangGraph)는 완성돼 있지만 진입점이 `data/cases/*.json` 파일이다.
즉 "누가 사건을 만들어 주는가"가 비어 있다. 실제 은행 SOP는

```
거래 발생 → 탐지 → 조사 → 사람 판단 → 조치
```

인데 앞의 두 칸이 없다. 이 문서는 그 두 칸을 채운다.

조치(지급정지·STR)는 지금처럼 **권고까지만** 하고 실행은 사람이 한다. 바뀌지 않는다.

---

## 2. 이음매는 이미 있다

결제계는 이미 이벤트를 낸다. **결제계 코드를 고치지 않는다.**

```
services/core-banking/.../payment/outbound/kafka/OutboxPublisher.java
  PAYMENT_COMPLETED → payment.completed
  PAYMENT_FAILED    → payment.failed
  PAYMENT_REVERSED  → payment.reversed
```

Outbox 패턴이라 발행이 DB 트랜잭션과 함께 보장된다. 여기에 **새 컨슈머 그룹으로
하나 더 구독**한다. 컨슈머 그룹이 다르므로 회계계 등 기존 소비자에 영향이 없고,
탐지가 죽어도 결제는 계속 돈다.

### 문제: 페이로드가 최소한이다

```json
{ "paymentInstructionId": "...", "status": "COMPLETED",
  "amount": 1000000, "completedAt": "..." }
```

송신자·수취인·은행코드·자행여부가 없다. **금액 룰 하나 말고는 아무것도 못 만든다.**

`PaymentInstruction` 엔티티에는 필요한 값이 다 있다 — `senderUserId`,
`senderAccountNoSnap`, `receiverBankCode`, `receiverAccountNo`,
`receiverHolderNameSnap`, `isIntraBank`, `routingNetworkType`, `transferAmount`.

**선택지**

| 안 | 방법 | 평가 |
|---|---|---|
| A | 결제계에 내부 조회 API `GET /internal/payments/{piId}` 추가 | **채택.** 추가 전용·읽기 전용, 트랜잭션 경로 안 건드림 |
| B | Outbox payload 확장 | 반려. 결제 트랜잭션 경로 수정 — 남의 도메인 핵심을 건드린다 |
| C | 탐지기가 core_banking DB 직접 조회 | 반려. 서비스 경계 붕괴 |

A 가 결제계에 남기는 변경은 **읽기 전용 엔드포인트 하나**다. 기존
`GET /api/payments/inbound` 와 같은 계층에 둔다.

---

## 3. 구성

```
payment.completed
      │  (신규 컨슈머 그룹 fds-detector)
      ▼
┌─────────────────────────────────────────────┐
│ fds-detector (신규 Java 모듈)                │
│  1) 보강: GET /internal/payments/{piId}      │
│  2) 룰 8종 → 히트 시그널                      │
│  3) 이상탐지 점수 (inference-server)          │
│  4) 임계 초과분만 사건 생성                    │
└─────────────────────────────────────────────┘
      │  POST /api/investigate  (기존)
      ▼
조사 에이전트 (LangGraph, 기존)  →  HITL 큐 (어드민 /admin/fraud, 기존)
```

### 왜 별도 모듈인가

조사 에이전트(Python) 안에 넣으면 **실시간 스트림과 조사 루프가 한 프로세스에 묶인다.**
조사는 건당 수 초~수십 초가 걸리는데, 그게 밀리면 컨슈머 랙이 쌓이고 결국 탐지가 멈춘다.
SOP 상으로도 탐지와 조사는 다른 단계다.

Java 로 두면 결제계의 Kafka 설정·DLQ·재시도·`payment.kafka.consume` 지표 패턴을
그대로 쓸 수 있다.

> 대안: customer-service(인증보안계, 같은 도메인)에 얹기. 새 서비스가 안 늘어나지만
> 탐지 부하가 고객 조회 API 와 한 프로세스를 공유한다. 권장하지 않는다.

---

## 4. 룰 8종

가용 필드로 실제 판정 가능한 것만 넣는다. 임계값은 설정이 아니라 **정책 테이블**에
둔다 — 룰 임계는 운영 중 계속 바뀌고, 바뀐 이력이 남아야 한다.

| # | 룰 | 근거 필드 | 비고 |
|---|---|---|---|
| 1 | 거액 단건 | `transferAmount` | 구간은 정책 테이블 |
| 2 | 분할 거래(structuring) | sender + 시간창 | 임계 직하 반복 |
| 3 | 거래 속도 이상 | sender + 시간창 | 단시간 다건 |
| 4 | 심야 거래 | `completedAt` | KST 기준([[BusinessDate]] 재사용) |
| 5 | 신규 수취인 고액 | receiver 이력 | 첫 거래 + 금액 |
| 6 | 수취인 분산(fan-out) | receiver 다양도 | 자금세탁 전형 |
| 7 | 타행 집중 | `receiverBankCode`, `isIntraBank` | 짧은 시간 여러 은행 |
| 8 | 인증 이상 결합 | 직전 인증 이벤트 | 계정 탈취 신호. 조사 에이전트의 `get_auth_events` 와 같은 소스 |

2·3·5·6·7 은 **이력**이 필요하다. 탐지기가 자체 집계 테이블(`fds_txn_window`)을
유지한다 — 결제계 DB 를 읽지 않기 위해서다.

---

## 5. 이상탐지 (IsolationForest)

새로 만들 게 적다. **모델 학습·서빙 기반이 이미 있다.**

```
tools/data-tools/scripts/train_model.py       ← 학습 파이프라인
agents/inference-server/app/main.py           ← 서빙 (xgboost>=2.1 사용 중)
  POST /predict        (대출 decision)
  POST /predict/pd     (PD + isotonic calibration)
  POST /predict/anomaly  ← 추가
```

모델 디렉터리 규약(`data/models/<name>_<v>/`)과 버전 env 패턴을 그대로 따른다.

**룰과 ML 의 역할 분담.** 룰은 설명 가능하고 규제 대응이 되지만 새로운 수법을 못 잡는다.
이상탐지는 반대다. 그래서 **룰 히트는 그 자체로 사건화**하고, 룰이 안 걸린 건에 대해서만
이상 점수를 본다. 둘 다 안 걸리면 통과.

서빙이 죽으면 **룰만으로 계속 돈다**(fail-open on ML, fail-closed on rules).
이상탐지가 멈췄다고 탐지 전체가 멈추면 안 된다.

---

## 6. 조사 에이전트 진입점

지금은 `POST /api/investigate {"case": "case_h1"}` 로 **파일 이름**을 받는다.
실거래를 넣으려면 사건을 구성해 넘겨야 한다.

기존 도구 계층에 **토글 패턴이 이미 있다** — `TRIAGE_REAL_TOOLS` 에 도구 이름을 넣으면
목 대신 실제 백엔드를 부른다(`get_auth_events` 가 그렇게 동작 중).

같은 방식으로:
- `POST /api/investigate` 가 `case` 대신 `transaction` 페이로드도 받도록 확장
- 신규 도구 `get_payment_detail` 을 토글 대상에 추가

**기존 목 경로를 끊지 않는다.** 데모와 eval 워크플로(`eval-fraud.yml`)가 목으로 도는데,
그게 깨지면 CI 가 죽는다.

---

## 7. 계측

이번에 붙인 축을 그대로 쓴다.

| 지표 | 의미 |
|---|---|
| `fds_detection_total{rule, outcome}` | 룰별 히트 |
| `fds_anomaly_score` (Histogram) | 이상 점수 분포 → 임계 조정 근거 |
| `fds_case_created_total{trigger}` | 사건화 수 (rule / anomaly) |
| `fds_consumer_lag` | 컨슈머 랙 — 탐지가 밀리는지 |
| `fraud_recommendation_reviewed_total` (기존) | **채택률** — 사람이 권고를 뒤집는 비율 |

마지막이 핵심이다. 탐지를 붙이면 **오탐이 늘었는지 줄었는지 바로 보인다.**

---

## 8. 단계

각 단계마다 승인받고 진행한다.

| 단계 | 내용 | 파일 수(예상) |
|---|---|---|
| 1 | 결제계 내부 조회 API + 계약 테스트 | 3 |
| 2 | fds-detector 모듈 뼈대 + 컨슈머 + DLQ + 멱등 | 6~8 |
| 3 | 룰 8종 + 정책 테이블 + 집계 윈도우 | 8~10 |
| 4 | `/predict/anomaly` + 학습 스크립트 | 4~5 |
| 5 | 조사 에이전트 진입점 확장 + `get_payment_detail` | 3~4 |
| 6 | 지표·대시보드·문서 | 3 |

---

## 9. 확인이 필요한 것

1. **결제계에 읽기 전용 엔드포인트 하나 추가** — 다른 도메인이다. 개인 포크라
   그대로 진행할지, 팀 레포 기준으로 협의가 필요한지.
2. **fds-detector 를 새 모듈로 둘지**, customer-service 에 얹을지.
3. 룰 8종 목록에서 빼거나 더할 것.

---

## 10. 하지 않는 것

- 자동 지급정지. 권고까지만 한다 — 기존 HITL 원칙 유지.
- 결제 트랜잭션 경로 수정.
- 기존 목 기반 데모·eval 경로 제거.
