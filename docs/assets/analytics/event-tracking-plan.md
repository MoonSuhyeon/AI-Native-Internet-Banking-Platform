# 이벤트 택소노미 & 트래킹 플랜 (Internet Banking)

> **목적**: 인터넷뱅킹 사용자 여정을 프로덕트/마케팅 분석용 이벤트로 정의한 트래킹 플랜.
> **설계 관점**: 백엔드 감사·보안 이벤트 모델(customer-service, `login_attempt`·`certificate_use`·`identity_verification` 등)을 **행동 분석 이벤트로 번역**했다. 각 이벤트에 원본 소스 테이블을 명시해, 규제 도메인에서 검증된 이벤트 스키마가 분석 스택으로 이식됨을 보인다.
> **대상 도구 가정**: Segment/Amplitude/GA4 계열(이벤트 + 프로퍼티 + 아이덴티티 모델).

---

## 1. 설계 원칙 (택소노미 거버넌스)

1. **네이밍**: `object_action`, `snake_case`, 상태변화는 **과거형**(`login_succeeded`), 의도는 **현재/시작형**(`transfer_started`). → 이벤트 스프롤 방지를 위해 동사·객체를 통제 어휘로 고정.
2. **1 이벤트 = 1 사용자 의미 단위**. 구현 편의로 이벤트를 쪼개지 않는다(예: `button_clicked` 남발 금지).
3. **프로퍼티는 사전(dictionary)으로 통제**: 값 집합은 enum으로 고정하고 자유서술 금지(백엔드 `CHECK` 제약과 동일 철학).
4. **PII 등급 명시**: 각 프로퍼티에 PII 여부/처리 규칙을 붙인다(원본에서 `rrn_encrypted` 암호화·`maskEmail` 마스킹을 다룬 연장선).
5. **버전 관리**: 이벤트/프로퍼티 변경은 `v` 증가 + 변경 로그. 파괴적 변경은 새 이벤트로(백엔드 Flyway 마이그레이션과 동일 원칙).

---

## 2. 아이덴티티 모델 (익명 → 식별, 유저 스티칭)

가장 어려운 부분이자 백엔드 설계가 그대로 힘을 발휘하는 지점.

| 단계 | 식별자 | 소스 대응 | 설명 |
|---|---|---|---|
| 익명 | `anonymous_id` (기기/세션) | `login_attempt.customer_id = NULL` + `device_fingerprint_hash` | 가입 전·비로그인 행동. 미등록 기기·미존재 ID 시도도 이벤트로 수집 |
| 본인확인 | `ci`(해시) | `identity_verification.ci_value` | 본인확인 완료 시 신원 확정. **스티칭 키** |
| 식별 | `user_id` (= `customer_id`) | `customer.customer_id` / CI 유니크(V17) | "한 사람 = 한 유저" 보장. `identity_verified` 시점에 `anonymous_id → user_id` alias(`identify` 호출) |

**스티칭 규칙**: `identity_verified` 이벤트에서 `analytics.alias(anonymous_id → user_id)` 후 `analytics.identify(user_id, traits)`. 이후 모든 이벤트는 `user_id`로 귀속. → 익명 세션의 전환 기여가 유실되지 않음.

---

## 3. 글로벌(Super) 프로퍼티 — 모든 이벤트 공통

| 프로퍼티 | 타입 | 값/예시 | PII | 소스 대응 |
|---|---|---|---|---|
| `app_channel` | enum | `WEB` \| `MOBILE` | - | `*_channel_code` |
| `platform` | enum | `ios` \| `android` \| `web` | - | `registered_device.device_os_name` |
| `device_type` | enum | `MOBILE` \| `PC` \| `TABLET` | - | `registered_device.device_type_code` |
| `session_id` | string | UUID | - | `login_session.session_id` |
| `anonymous_id` | string | UUID | - | 기기/세션 |
| `user_id` | string(nullable) | `customer_id` | 유사식별자 | `customer.customer_id` |
| `event_time` | timestamp | ISO-8601 | - | `*_at` (TIMESTAMPTZ) |
| `app_version` | string | `1.4.2` | - | - |

---

## 4. 이벤트 카탈로그

### 4.1 온보딩 / 획득 퍼널

| 이벤트 | 트리거 | 핵심 프로퍼티 (타입 · 통제값) | 소스 대응 |
|---|---|---|---|
| `signup_started` | 가입 플로우 진입 | `entry_point`(enum: `home`\|`promo`\|`invite`) | (프론트) |
| `identity_verification_started` | 본인확인 요청 발송 | `agency`(enum: `NICE`\|`KCB`\|`SCI`\|`PASS`), `carrier`(enum) | `mobile_auth` INSERT |
| `identity_verification_completed` | 본인확인 성공 | `agency`, `is_success`(bool), `duration_ms`(int) | `identity_verification` INSERT |
| `signup_completed` | 고객 생성 완료 | `join_channel`(enum), `has_marketing_optin`(bool) | `customer` INSERT (+ `sms/email_receive_yn`) |

> **활용**: `signup_started → identity_verification_completed → signup_completed` 퍼널 이탈률. 특히 본인확인 기관(`agency`)별 성공률 비교(마케팅이 아니라 **전환 최적화** 인사이트).

### 4.2 인증 / 재방문

| 이벤트 | 트리거 | 핵심 프로퍼티 (타입 · 통제값) | 소스 대응 |
|---|---|---|---|
| `login_attempted` | 로그인 시도 | `auth_method`(enum: `PIN`\|`CERT_FIN`\|`CERT_COMMON`\|`CERT_AXFUL`\|`SMS`\|`PASS`\|`BIO_FACE`\|`BIO_FINGER`), `is_trusted_device`(bool) | `login_attempt` INSERT |
| `login_succeeded` | 로그인 성공 | `auth_method`, `is_new_device`(bool) | `login_attempt.success_yn='T'` |
| `login_failed` | 로그인 실패 | `failure_reason`(enum: `BAD_CREDENTIALS`\|`LOCKED`\|`INACTIVE`\|`PW_EXPIRED`) | `login_attempt.failure_reason_code`(CUST_010~013) |
| `qr_login_approved` | 모바일 QR 승인 | `-` | `qr_login_token.status='APPROVED'` |
| `device_registered` | 신규 기기 등록 | `device_type`(enum) | `registered_device` INSERT |

> **활용**: `auth_method`별 로그인 성공률/재방문율 → 어떤 인증수단 유저가 리텐션이 높은가. `failure_reason` 분포로 이탈 유발 마찰 진단.

### 4.3 활성화 / 핵심 가치 (Aha)

| 이벤트 | 트리거 | 핵심 프로퍼티 (타입 · 통제값) | 소스 대응 |
|---|---|---|---|
| `account_viewed` | 계좌 조회 | `account_count`(int) | (조회 API) |
| `withdrawal_account_registered` | 출금계좌 등록 | `registration_type`(enum: `ONLINE`\|`BRANCH`), `bank_code`(string) | `withdrawal_account` INSERT |
| `transfer_limit_changed` | 이체한도 변경 | `direction`(enum: `DECREASE`\|`INCREASE`), `channel`(enum) | `transfer_limit` UPDATE |
| `transfer_started` | 이체 입력 시작 | `recipient_type`(enum: `SELF`\|`REGISTERED`\|`NEW`) | (프론트) |
| `transfer_completed` | 이체 성공 | `amount_band`(enum: `<10만`\|`10-100만`\|`100-500만`\|`500만+`), `recipient_type` | `certificate_use`(LOGIN/서명) + 결제계 |
| `transfer_failed` | 이체 실패 | `failure_reason`(enum: `LIMIT_EXCEEDED`\|`CERT_FAIL`\|`INSUFFICIENT`) | `certificate_use.result_code` |

> **PII 주의**: 금액은 원값이 아니라 `amount_band`로 버킷화, 수취계좌번호는 수집 금지(`recipient_type`만). → 프로퍼티 단위 PII 통제의 실물 예시.
> **활성화 지표**: `signup_completed → first transfer_completed` 도달률/소요시간(Time-to-Value).

### 4.4 신뢰/보안 (도메인 차별화 이벤트)

| 이벤트 | 트리거 | 핵심 프로퍼티 | 소스 대응 |
|---|---|---|---|
| `fds_alert_triggered` | FDS 룰 히트 | `rule_category`(enum), `action`(enum: `BLOCK`\|`CHALLENGE`\|`MONITOR`) | `fds_detection` INSERT (`fds_rule` 연동) |
| `cert_pin_failed` | 인증서 PIN 실패 | `failure_count`(int) | `certificate_use.result='FAIL_PIN'` |

> **활용**: 보안 마찰(`fds_alert_triggered`, `CHALLENGE`)이 이체 전환/리텐션에 주는 영향을 정량화. "안전 vs 편의" 트레이드오프를 데이터로. 투자앱이 못 하는 신뢰 서사와 연결.

### 4.5 생애주기 / 리텐션

| 이벤트 | 트리거 | 핵심 프로퍼티 | 소스 대응 |
|---|---|---|---|
| `customer_status_changed` | 상태 전환 | `from_status`, `to_status`(enum: `ACTIVE`\|`DORMANT`\|`SUSPENDED`\|`CLOSED`), `is_system_auto`(bool) | `customer_status_history` INSERT |
| `dormant_reactivated` | 휴면 해제 | `dormant_days`(int) | status: `DORMANT→ACTIVE` |

> **활용**: 휴면 전환 선행 신호(마지막 `login_succeeded` 후 경과) → 이탈 예측/윈백 캠페인 트리거.

---

## 5. 프로퍼티 사전 (재사용 enum)

| 프로퍼티 | 타입 | 통제값 |
|---|---|---|
| `app_channel` | enum | `WEB`, `MOBILE` |
| `auth_method` | enum | `PIN`, `CERT_FIN`, `CERT_COMMON`, `CERT_AXFUL`, `SMS`, `PASS`, `BIO_FACE`, `BIO_FINGER` |
| `agency` | enum | `NICE`, `KCB`, `SCI`, `PASS` |
| `failure_reason`(login) | enum | `BAD_CREDENTIALS`, `LOCKED`, `INACTIVE`, `PW_EXPIRED` |
| `amount_band` | enum | `LT_100K`, `100K_1M`, `1M_5M`, `GTE_5M` |
| `recipient_type` | enum | `SELF`, `REGISTERED`, `NEW` |
| `customer_status` | enum | `ACTIVE`, `DORMANT`, `SUSPENDED`, `CLOSED` |

---

## 6. 대표 퍼널 정의

**온보딩 퍼널** (전환율·단계 이탈):
`signup_started → identity_verification_started → identity_verification_completed → signup_completed`

**활성화 퍼널** (Time-to-Value):
`signup_completed → login_succeeded → account_viewed → transfer_completed(first)`

**세그먼트 예시**: `auth_method = CERT_AXFUL`(자사 인증서) 유저 vs 그 외 → 30일 리텐션·이체 빈도 비교.

---

## 7. PII / 거버넌스 규칙

| 등급 | 예시 프로퍼티 | 규칙 |
|---|---|---|
| **금지 수집** | 주민번호, 수취계좌번호, CI 원문 | 이벤트에 절대 미포함. CI는 해시만 스티칭 내부용 |
| **버킷/마스킹** | 금액→`amount_band`, 이메일→도메인만 | 원값 대신 파생값. (`maskEmail` 처리 연장선) |
| **유사식별자** | `user_id`, `device_fingerprint_hash` | 분석 워크스페이스 접근 통제 |

**이벤트 리뷰 프로세스**: 신규 이벤트/프로퍼티는 (1) 네이밍 규칙 검증 → (2) enum 통제값 확정 → (3) PII 등급 지정 → (4) 버전 태깅 후 트래킹 플랜에 등재. → 백엔드 스키마 마이그레이션 리뷰와 동일한 게이트.

---

## 8. 이 문서가 증명하는 역량 (요약)

- **통제 어휘 기반 이벤트 정의** — 자유서술이 아닌 enum 강제(백엔드 `CHECK` → 분석 프로퍼티 사전).
- **아이덴티티 레졸루션** — 익명→본인확인(CI)→식별 스티칭을 실제 스키마로 뒷받침.
- **PII 등급별 프로퍼티 거버넌스** — 규제 도메인 경험의 직접 이식.
- **퍼널·생애주기 모델링** — 상태 이력 테이블 → 리텐션/전환 분석 이벤트.
- **버전 관리형 택소노미 진화** — Flyway 마이그레이션 사고방식의 분석 버전.
