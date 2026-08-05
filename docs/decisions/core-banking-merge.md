# 수신·결제 서비스 병합 (core-banking) — 설계 결정 기록

## 문제 정의

자행이체는 **하나의 사실**이다. "A계좌에서 빠지고 B계좌에 들어간다."
원자적으로 성립하거나, 아예 일어나지 않아야 한다.

그런데 현재 이 하나의 사실이 두 서비스와 두 데이터베이스에 쪼개져 있다.

```
payment-service (payment-db)          deposit-service (deposit-db)
  PaymentOrchestratorImpl
    │
    ├─ DepositBalanceClient.withdraw() ──HTTP──> 출금 (deposit-db 트랜잭션 1)
    │
    ├─ (payment-db 에 거래 기록)                 ← 여기서 실패하면?
    │
    ├─ DepositBalanceClient.deposit()  ──HTTP──> 입금 (deposit-db 트랜잭션 2)
    │
    └─ 실패 시 .cancel() ──────────────HTTP──> 보상
```

출금과 입금이 별개 트랜잭션이라 그 사이에 실패하면 **보상 호출(`cancel`)로 되돌린다**.
보상 방식에는 두 가지 구멍이 있다.

1. **보상 호출 자체가 실패할 수 있다.** 네트워크가 끊기거나 deposit-service 가 죽으면
   출금만 된 상태로 남는다. 재시도 큐도 없다.
2. **보상 전까지 잔액이 틀린 상태로 노출된다.** 출금은 커밋됐고 입금은 아직인 구간에서
   조회가 들어오면 존재하지 않는 손실이 보인다.

즉 **분산 트랜잭션을 감수할 이유가 없는 곳에서 감수하고 있다.** 두 DB 모두 우리 것이고
같은 호스트에 있다. 경계를 나눌 기술적 강제가 없는데도 나뉘어 있다.

### 왜 이렇게 나뉘었나 — 그때는 맞았다

경계는 트랜잭션 요구가 아니라 **담당자 분담**을 따라 그어졌다.
`docs/deposit-payment-api-spec.md` 는 이를 명시한다.

> payment-service(이체계)가 deposit-service(수신계)에 요구하는 API 전체 목록.
> **deposit-service 담당자가 이 명세를 기준으로 구현한다.**

같은 흔적이 코드에도 남아 있다.

- 모든 호출이 `X-Caller-Service: payment-service` 헤더를 요구한다 — 호출자가 남이라는 전제
- 엔드포인트에 A-1, B-3 같은 번호가 붙어 협상 항목으로 관리됐다
- `D-REQ-3/4 해소 전: deposit 에 /limits endpoint 없음 → mock 의존` — 상대가 아직
  만들지 않아 Mock 으로 막아둔 상태. 한 사람이 짰다면 그냥 메서드를 추가했을 일이다

팀 작업에서 이 선택은 정당했다. 명세를 먼저 합의하면 양쪽이 병렬로 작업할 수 있고,
잔액이 틀리면 수신계·지시가 안 나가면 결제계로 책임이 갈린다. 두 사람이 같은 테이블을
동시에 고치는 것보다 낫다.

**바뀐 것은 설계의 옳고 그름이 아니라 제약이다.** 담당자 경계가 사라졌는데 그 흔적인
HTTP 경계만 남아, 비용(분산 트랜잭션)은 계속 내면서 편익(병렬 작업·책임 분리)은
받지 못하는 상태가 됐다.

### 결정적 증거 — 원자적 이체는 이미 있었다

수신계 `TransactionService.transfer()` 는 처음부터 이 일을 원자적으로 한다.
`@Transactional` 이고, 멱등키를 받고, `fromAccountId <= toAccountId` 로 락 순서를 정해
교착까지 막는다. INTERNAL 이면 양쪽 계좌를, EXTERNAL 이면 출금만 처리한다.

결제계는 **기능이 없어서가 아니라 경계 너머라서** 이것을 쓰지 못하고 출금·입금을
따로 호출했다. 경계가 잘못 그어졌다는 것을 이보다 잘 보여주는 증거는 없다.

---

## 결정

**deposit-service 와 payment-service 를 `core-banking` 하나로 합친다.
단일 데이터베이스 안에 스키마를 둘로 유지하고, 자행이체를 로컬 트랜잭션으로 만든다.**

```
core-banking  (core_banking DB)
  ├─ schema: deposit   (계좌·원장·수신상품 — 49 테이블)
  └─ schema: payment   (지급지시·정산 — 8 테이블)

  TransferService.transferInternal()
    @Transactional  ← 하나의 트랜잭션
      출금  (deposit.account_balance)
      기록  (payment.payment_instruction)
      입금  (deposit.account_balance)
```

### 왜 스키마를 합치지 않고 둘로 두는가

같은 데이터베이스 안이면 스키마가 달라도 하나의 트랜잭션이 걸린다. 목표는 이미 달성된다.
반대로 49개 테이블을 한 스키마로 밀어넣으면 이름 충돌을 손으로 풀어야 하는데, 그렇게 해서
얻는 것이 없다.

**도메인 경계는 스키마로 남기고, 트랜잭션 경계만 합친다.** 이것이 최소 변경으로 목적을
달성하는 지점이다.

---

## Saga 는 그대로 둔다 — 이 결정의 핵심

병합은 "마이크로서비스가 나쁘다"는 주장이 아니다. **경계를 일관성 요구에 맞추는 것**이다.
그래서 타행이체(KFTC/BOK)의 Saga·Outbox·멱등키는 **하나도 걷어내지 않는다.**

| | 자행이체 | 타행이체 |
|---|---|---|
| 상대 계좌 | 우리 DB | 상대 은행 DB |
| 트랜잭션을 걸 수 있나 | 걸 수 있다 | 걸 수 없다 |
| 채택 | **로컬 ACID** | **Saga + Outbox + 멱등키** |

둘을 가르는 기준은 "우리가 트랜잭션을 걸 수 있는 범위인가" 하나다.
걸 수 있으면 걸고, 없으면 보상한다. 지금 코드는 양쪽 모두 보상 방식이라,
**걸 수 있는데도 안 걸고 있는 쪽**이 존재한다.

---

## B은행(다온뱅크)을 실제 계좌로 전환한다

현재 `payment-service-b` 는 `mock` 프로필로 뜨고, 수신을 `DepositBalanceClientMock` 이
처리한다. 그 구현은 아무것도 저장하지 않는다.

```java
// DepositBalanceClientMock.deposit()
BigDecimal before = BigDecimal.valueOf(1000000L);   // 하드코딩
return new BalanceTxData(..., before, before.add(amount), ...);
```

**두 번 송금해도 잔액이 같다.** 결과적으로 지금의 타행이체 검증은 어떤 버그가 있어도
통과한다 — 돈이 도착했는지 확인할 대상 자체가 없기 때문이다.

**실패할 수 없는 검증은 증거가 아니다.** core-banking 을 A·B 두 번 배포하고 B도 실제
계좌를 쓰게 하면, 그 순간 타행이체 테스트가 반증 가능해진다.

### Mock 에 박힌 시나리오는 삭제가 아니라 이관한다

Mock 은 스텁 겸 **시나리오 픽스처** 역할을 겸하고 있다. 지우면 시나리오가 함께 사라진다.

| 계좌번호 | 용도 | 이관 방식 |
|---|---|---|
| `99990000000001` | 1회 한도 100원 → LIMIT_EXCEEDED | 실제 계좌 + 실제 한도 시드 |
| `12345678909999` | 입금 시 시스템 장애(경합) | 장애 주입 지점을 테스트에서 명시 |
| `11011100000` | 20억 잔액 → BOK 거액이체 | 실제 계좌 + 잔액 시드 |

### 로그인은 넣지 않는다

인증은 customer-service 소관이라 B에 로그인을 두면 서비스가 하나 더 붙는다.
그런데 **로그인이 추가로 증명하는 것이 없다** — 돈의 도착 여부는 B의 계좌조회로 확인되고,
로그인은 그것을 사람 눈으로 보여주는 경로일 뿐이다. B의 화면을 시연할 필요가 생기면
그때 붙인다.

참고로 deposit-service 는 `CustomerServiceClient` 클래스를 갖고 있으나 **호출하는 코드가
0건**이다. 따라서 계좌·이체만 두는 데는 추가 서비스가 필요 없다.

---

## 순서

동작이 실제로 바뀌는 지점을 뒤로 미루고, 각 단계마다 기존 테스트가 초록인지 확인한다.

1. `core-banking` 모듈 골격 + 소스 이관 — 컴파일만 맞춘다(동작 변화 없음)
2. DB 통합 — `core_banking` 단일 DB, 스키마 둘, Flyway location 분리
3. **Feign 2개 제거 → 직접 호출, 자행이체를 단일 트랜잭션으로** ← 동작이 바뀌는 유일한 지점
4. B 실계좌 전환 + Mock 시나리오 시드 이관
5. compose·gateway·CI 정리

---

## 버리는 것

- `DepositAccountClient`, `DepositBalanceClient` 와 각 Mock
- 자행이체 경로의 보상 호출(`cancel`) — 트랜잭션 롤백이 대신한다
- `deposit-db`, `payment-db` 컨테이너 (→ `core-banking-db-a`, `core-banking-db-b`)

## 유지하는 것

- KFTC/BOK Saga, Outbox, 멱등키 — 타행이체에는 여전히 필요하다
- 타행이체 경로의 `cancel` — 상대 은행 실패 시 우리 쪽을 되돌리는 유일한 수단
- deposit / payment 스키마 분리
