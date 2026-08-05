# Payment Service — Claude Code 도메인 가이드

> `services/payment-service` 전용 가이드. 루트 전사 가이드와 충돌 시 아래 **컨텍스트 우선순위** 기준으로 판단.

---

## 1. 컨텍스트 우선순위

상충 시 위쪽이 우선.

1. 사용자의 현재 메시지
2. `/docs/AI_GUIDELINES.md` — 전사 공통 AI 가이드
3. `/CLAUDE.md` — 팀 루트 Claude Code 가이드
4. **본 파일** — 결제계 도메인 특화 규약
5. `/services/payment-service/docs/` 안 설계 산출물 — **진실의 원천**
6. 그 외 레포 파일

---

## 2. 도메인 개요

- 결제계 = 자행·타행 이체 처리 + KFTC/BOK 외부망 통신 + Saga 보상 트랜잭션 관리
- A/B 두 은행이 동일 코드베이스 운영. `BANK_CODE` 환경변수(`A` / `B`)로 분리
- 의존 서비스: **deposit-service** (잔액·계좌마스터 통합, 필수) / **loan-service** (선택) / **customer-service** (드물게)

---

## 3. 설계 산출물 (★ 진실의 원천)

`services/payment-service/docs/` 에 아래 10개 xlsx가 박제된다.
코드·DB 스키마·Kafka 토픽 작성 전 **반드시** 해당 산출물을 먼저 확인한다.

| 파일 | 핵심 내용 |
|---|---|
| `결제계_정책문서_v7.2.xlsx` | 30개 정책 (P-001 ~ P-030) |
| `결제계_enum_상태전이도_v9.xlsx` | 9개 진행상태 + 전이매트릭스 9×9 |
| `결제계_API명세서_v2.3.xlsx` | 22개 API |
| `결제계_Kafka토픽정의서_v3.1.xlsx` | 18개 토픽 + DLQ |
| `결제계_deposit_API합의서_v1.0.xlsx` | deposit 도메인 9개 API (🟡 가정 7건 포함) |
| `결제계_시나리오_v4.1.xlsx` | 14개 시나리오 |
| `결제계_컬럼명세서_v12.2.xlsx` | 203 컬럼 (컬럼명 기준) |
| `결제계_테이블정의서_v2.xlsx` | 테이블 목록·관계 |
| `결제계_기술스택정의서_v2.2.xlsx` | Java 17, Spring Boot 3.x, PostgreSQL 16, Kafka |
| `결제계_용어집.xlsx` | 도메인 용어 정의 |

---

## 4. 핵심 설계 결정

| 정책 | 내용 |
|---|---|
| **P-028** | OUT 트랜잭션 분해: TX-1 DRAFT INSERT → 외부검증(TX 밖) → 외부 출금호출(TX 밖) → TX-2 분개+전이+Outbox INSERT → Outbox 워커 발행 |
| **P-029** | Self-Listening 방지: Consumer에서 `sender/receiver_bank_code` 필터링 |
| **P-030** | 외부 시스템 트리거 강제 취소는 반드시 `CANCEL_REQUESTED` 경유 |
| **P-002 보강** | `PROCESSING` 종료 분기 = 외부 자금 변동 여부 기준으로 판단 |
| **P-014 보강** | ACK 이후 `REVERSING` 진입 = 외부/운영자 트리거만 허용 |
| **청산상태 ACK** | Kafka Producer `acks=all` 응답 수신 시점으로 재정의 (enum v9 기준) |
| **운영자 강제 취소** | 모든 외부/운영자 트리거 강제 취소는 `CANCEL_REQUESTED` 경유 (일관성 G) |

---

## 5. 코드 작성 규칙 (★ Claude Code 행동 규약)

- **외부 API 호출 격리**: `@Transactional` 범위 안에 외부 HTTP 호출 절대 금지 (P-028 핵심)
- **Kafka 발행**: 모두 Outbox 워커 경유. `KafkaTemplate.send()` 직접 호출 금지
- **Kafka Consumer ack**: DB COMMIT 완료 후 `ack.acknowledge()` 호출 (at-least-once 보장)
- **외부 응답 박제**: deposit·KFTC 등 모든 외부 응답은 스냅샷 컬럼에 동시 저장
- **멱등키**: 결제계 책임으로 발급. 형식 `{API}-{거래ID}-{시도번호}`
- **보상 호출 전 검증**: 결제지시 진행상태 자체 검증 선행 필수 (이중 보상 방지)
- **DB 접근**: MyBatis XML 매퍼 사용 (JPA 미사용). 매퍼 위치 `src/main/resources/mappers/`. 복잡 SQL(분개 4건 동시 INSERT 등) 정확 제어 목적 (기술스택 정의서 v2.2)
- **환경 분리**: `application.yml` = 컨테이너용 default (port 8080) / `application-local.yml` = 로컬 IDE 전용 (`SPRING_PROFILES_ACTIVE=local`, port 8084, .gitignore 제외)
- **패키지 분리**:
  - 도메인 모델 → `com.bank.payment.domain`
  - 외부 발신 (Feign, Kafka Producer) → `com.bank.payment.outbound`
  - 외부 수신 (Kafka Consumer) → `com.bank.payment.inbound`

---

## 6. 모르는 것 처리 (★ 중요)

- 정책·enum·스키마에 명시 안 된 부분은 **추측 금지** — 사용자에게 질문
- 산출물 간 모순 발견 시 **자동 결정 금지** — 사용자에게 보고 후 지시 대기
- xlsx 미확인 상태로 컬럼명·enum값·토픽명을 코드에 직접 작성 금지
  - 컬럼 기준: `결제계_컬럼명세서_v12.2.xlsx`
  - enum 기준: `결제계_enum_상태전이도_v9.xlsx`
- `결제계_deposit_API합의서_v1.0.xlsx` 의 🟡 가정(7건) 영역 작업 시 반드시 사용자 확인
- ★deposit-service 인스턴스 분리 가정: 현재 "은행별 분리(A은행 deposit ↔ A은행 결제계)"로 가정.
  합의서 🔴 확인 3 미해소. Stage 1 인프라 결정 시 사용자 재확인 필요.

---

## 7. Stage 진행 상태

| Stage | 내용 | 상태 |
|---|---|---|
| **0** | CLAUDE.md + 디렉토리 구조 + 산출물 박제 | ✅ 완료 (2026-05-19) |
| **1** | `services/payment-service/docker-compose-kafka.yml` (Kafka 3 클러스터 + UI) + 토픽 18개 + payment-db A/B | ✅ 완료 (2026-05-19) |
| **2** | build.gradle 확장 + Spring Boot 골격 + 멀티 Kafka Config + MyBatis 전환 (Redis/JPA 제거) | ✅ 완료 (2026-05-19) |
| **3** | Flyway V1~V6 마이그레이션 (141 컬럼, 38 CHECK, 10 FK) | ✅ 완료 (2026-05-19) |
| **4-A** | 도메인 6개 박제 (한 라운드 한 도메인) | ✅ 완료 (2026-05-20) |
| **4-A-1** | PaymentInstruction (36 필드) | ✅ 완료 (2026-05-20) |
| **4-A-2~6** | 나머지 도메인 5개 (IdempotencyKey/Ledger/ExternalCall/OutboxMessage/StatusHistory) | ✅ 완료 (2026-05-20) |
| **4-B** | 자행이체 Service/Controller (txStep1~4, Orchestrator) | ✅ 완료 |
| **5** | 자행 S1 정상 + 수신검증 8건 | ✅ 완료 |
| **5-F** | 자행 실패보상 F1(잔액부족 직행)/F8(입금실패 보상)/F5(분개실패 보상) | ✅ 완료 |
| **7-A** | Outbox 워커 (PENDING→Kafka publish) | ✅ 완료 |
| **7-B** | KFTC response consumer + DLQ + @EnableKafka | ✅ 완료 |
| **7-C** | Orchestrator 자행/타행 분기 | ✅ 완료 |
| **S2-A** | KFTC 타행송신 + 완결 (SETTLEMENT_NOTIFY→CT SETTLED→PI COMPLETED) | ✅ 완료 |
| **F2** | KFTC 거절보상 (CLEARING→REVERSING→FAILED + 역분개4 + B-5) | ✅ 완료 |
| **BOK-1** | 인프라: V11 bok_settlement_transaction + 10억 라우팅 + Outbox BOK_REQUEST_SENT | ✅ 완료 |
| **BOK-2** | S3 송신+완결 (processInterBok + txStep4InterBok + BokNetworkResponseConsumer + SETTLEMENT_COMPLETED) | ✅ 완료 |
| **BOK-3** | F3 거절보상 (SETTLEMENT_REJECT → 역분개4 BOK_REJECTION + B-5 + BST REJECTED) | ✅ 완료 |

---

## 8. Git 작업 규약

루트 `docs/AI_GUIDELINES.md §5` (AI 흔적 금지)를 그대로 따른다. 아래는 결제계 강조 사항.

- `Co-authored-by: AI명` 류 footer **절대 금지**
- Claude Code는 `git commit / push / add / rebase / merge` **직접 실행 금지**
- `git status / diff / log` 등 읽기 전용 명령만 실행 가능
- 사용자가 명시적으로 요청해도 쓰기 git 명령 거부 → 커밋 메시지 후보만 제안

### 매 작업 종료 시 출력 형식

```
[작업 요약]
- 생성/수정된 파일: (리스트)
- 핵심 변경 사항: (3~5줄)

[제안 커밋 메시지]
<type>(payment): <subject>

- 변경 디테일 (3~5줄)

관련 정책/산출물: (P-XXX, 컬럼명세서 v12.2 등)

[다음 단계]
사용자가 IDE/터미널에서 직접 git add + commit + push 실행
```

---

## 9. 도메인 용어

> 자세한 정의는 `docs/결제계_용어집.xlsx` 참조. 아래는 자주 헷갈리는 것만.

| 용어 | 1줄 정의 |
|---|---|
| 박제(Snapshot) | 외부 응답을 결제계 DB에 영구 저장. 이후 변경 없음 |
| 멱등키 | 재시도 시 deposit이 직전 응답을 반환하게 하는 키 |
| 거래분개(ledger) | 결제계 내부 회계 차/대변 분개. 한 결제에 2~4건 발생 |
| deposit deposit_transactions | 통장 거래내역 row. 결제계 ledger와 별개 개념. (합의서 v1.0 의 `common_transaction` 은 미구현 통합모델 테이블명이었고, 실제 deposit-service 는 `deposit_transactions` 를 사용 — V18 에서 유령 테이블 제거) |
| 청산대기(CLEARING_PENDING) | KFTC 외부망 송신 후 정산 전 임시 계정 상태 |
| Outbox 워커 | 결제계 DB Outbox 테이블 → Kafka 비동기 발행 워커 |
| Saga / 보상 트랜잭션 | 외부 자금 변동 후 실패 시 역 호출로 원상복구하는 패턴 (P-014) |
| CANCEL_REQUESTED | 운영자/외부 트리거로 강제 취소 요청된 상태. REVERSING 직전 단계 (enum v9) |
| 청산상태 ACK | Kafka Producer acks=all 응답 수신 시점 = KFTC 접수 신호로 간주 (enum v9 재정의) |

---

## 스펙 충돌 누적 (구현 중 발견, 코드가 채택한 값)

| 항목 | 스펙 | 채택값 | 사유 |
|---|---|---|---|
| channel | 시나리오 'APP' | 'MOBILE' (WEB/MOBILE/BRANCH/ATM/OPEN_BANKING/INBOUND) | DB channel CHECK에 APP 없음 |
| failure_category | 시나리오 'EXTERNAL_REJECTION' | ledger=KFTC_REJECTION/BOK_REJECTION, PI=KFTC_REJECTED/BOK_REJECTED (Outbox payload만 EXTERNAL_REJECTION) | 레이어별 의미 분리. DB CHECK 정합 |
| BOK 라우팅 임계 | 테이블정의서 1억(예시 오류) | 10억 (transferAmount >= 1,000,000,000) | enum 상태전이도 #16/#39 "10억" 채택 |
| BOK 청산대기 계정 | 스펙 미정의 | 'KB-CLR-BOK' (임시) | KFTC 'KB-CLR-088' 패턴. 추후 확정 필요 |
| BOK 분개 구조 | 테이블정의서 "청산대기 없음, 즉시 한은당좌" | 청산대기 4건 (KFTC와 동일) | 한은당좌(BOK_DDA) 분개는 회계계 소관. 결제계 ledger CHECK에 BOK_DDA 없음 |

---

## 검증 환경 교훈 (로컬 e2e)

- ★payment-service-b 끄고 검증: a/b 둘 다 뜨면 같은 consumer 그룹으로 파티션 분배 → 메시지가 b 인스턴스로 새서 a 로그에 안 뜸. `docker compose -f docker-compose-kafka.yml stop payment-service-b`
- 송신 바디 channel:"MOBILE" 필수 (헤더 X-Idempotency-Key/X-User-Id/X-Auth-Token-Id만으론 channel NULL → CHECK 위반)
- 포트 8080 (8084는 application-local.yml IDE 전용), DB는 payment_a (payment 아님)
- 컨테이너 빌드: docker compose -f docker-compose-kafka.yml --profile app up -d --build payment-service-a (호스트 ./gradlew 금지, §10)
- Kafka produce 시 clearingNo/bokReferenceNo 실제값 치환 필수 (플레이스홀더 그대로 보내면 "BST/CT 없음 skip")
- BOK 10억 테스트: mock(DepositBalanceClientMock) 잔액/한도 20억으로 수정됨 (테스트 하네스, 프로덕션 아님)
- Windows 콘솔 한글: chcp 65001 + [Console]::OutputEncoding=UTF8 선행 (안 하면 정상 한글도 깨져 보임)
- TRUNCATE 7테이블: payment_instruction, idempotency_key, ledger, external_call, outbox_message, status_history, kftc_clearing_transaction (+bok_settlement_transaction) CASCADE

---

## 10. 작업 범위 격리 (영구 규칙)

이 프로젝트는 팀 모노레포. 작업 범위는 엄격히 격리:

### 절대 금지 (팀 자산)
- 루트 build.gradle 수정/생성
- 루트 settings.gradle 수정/생성
- 루트 .gitignore 수정/생성
- 루트 gradlew, gradlew.bat, gradle/wrapper/ 생성 (이미 있으면 그대로 둠)
- common/, services/customer-service/, services/deposit-service/, services/loan-service/ 진입/수정/삭제
- 위 영역에서 발견된 문제는 보고만, 임의 처리 금지

### 허용 작업 범위
- services/payment-service/** 만 작업
- 컨테이너 기반 빌드/실행 (Dockerfile, docker-compose-kafka.yml)
- payment-db, payment-kafka 인프라 검증
- 호스트 ./gradlew 호출 금지. 컨테이너 검증만.

### 작업 패턴
- 모노레포 멀티프로젝트 구조 유지
- 루트 파일은 한 줄도 안 건드림
- 격리 작업/디렉토리 이동/정리 작업 절대 안 함

### Git 작업 제약
- git add, git commit, git push 등 git 변경 명령 절대 실행 금지
- git status, git log, git diff 등 읽기 명령만 허용
- 모든 커밋은 사용자가 직접 진행
