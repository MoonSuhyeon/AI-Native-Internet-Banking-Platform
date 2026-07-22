# 문서 검증 자동화 에이전트 (doc-agent) 계획서

> 작성일: 2026-05-28
> 상태: 설계 합의 완료, 구현 미착수
> 브랜치: `doc-agent`

---

## 1. 개요 및 목적

대출 신청 프로세스의 수작업 서류 검토를 자동화하는 마이크로서비스 `doc-agent`를 신규 구축한다.

- **자동화 범위**: OCR 텍스트 추출, PII 마스킹, 마스터 테이블 기반 누락·만료 검증, 위변조 시그널 산출, 다음 단계 라우팅
- **자동화 제외**: 위변조 **확정 판정**, HOLD 해제, 형사조치 결정 — 모두 사람만 가능
- **목표 환경**: 로컬 노트북(RAM 16GB, CPU)에서 PoC 가동 가능, 모든 스택 오픈소스
- **포지셔닝**: 본 프로젝트는 포트폴리오 목적. 1금융권 요건을 참고하되 상용 라이선스·유료 클라우드는 회피

---

## 2. 시스템 포지셔닝

기존 서비스와의 관계:

```
[Web 신청]
   │
   ▼
[loan-service] ──(서류 업로드 이벤트, Kafka)──▶ [doc-agent]
                                                      │
                                  ┌───────────────────┤
                                  │                   │
                                  ▼                   ▼
                          [inference-server]    [Ollama (host)]
                          PaddleOCR / Structure  Qwen2.5:3b
                                  │
                                  ▼
                          표준 JSON
                                  │
                                  ▼
                          [ai-service (사전대출심사)]
                                  │
                                  ▼
                          [review-ai-gateway] ─▶ [auto-loan-review]
                                                       │
                                          (위변조 신호 ≥ HOLD)
                                                       ▼
                                          [심사원 큐] ─▶ [감사팀 큐]
```

**경계 원칙**:
- doc-agent는 **별도 마이크로서비스** (`agents/doc-agent`)
- 비즈니스 로직·상태·라우팅·Kafka는 Spring (Java)
- Python 사이드카는 **stateless, 추론만**. DB/Kafka 접근 금지
- 사이드카 장애 시 Spring이 circuit-breaker로 `NEEDS_RESUBMIT` 라우팅

---

## 3. 기술 스택

| 영역 | 선택 | 사유 |
|---|---|---|
| 오케스트레이션 | **Spring Boot Java** | 기존 서비스 통일 |
| 추론 사이드카 | **Python FastAPI** (기존 `inference-server` 재사용) | OCR/모델 생태계 |
| OCR | **PaddleOCR (PP-OCRv4 ko)** | 한국어 정확도 우위 |
| Layout | **PP-StructureV2** | 등기부등본 표 인식, PaddleOCR 생태계 일관 |
| LLM | **Ollama + Qwen2.5:3b (q4)** | RAM 2.5GB, 16GB 로컬에서 쾌적 |
| 구조화 출력 | **Ollama JSON Schema 강제** (`format: <schema>`) | 키 변형 방지, Ollama 0.5+ 기능 |
| 객체 스토리지 | **MinIO** (로컬) / **Cloudflare R2** (클라우드 데모) | S3 호환, 영구 무료 |
| KMS | **HashiCorp Vault Dev** (또는 OpenBao) | Transit 엔진으로 envelope 암호화 |
| 합성 데이터 | AI Hub + **Albumentations** + **TRDG** + DocTamper 방식 | 한글 위변조 공개셋 부재 → 자체 합성 |
| OCR fallback (인터페이스만) | EasyOCR | 운영엔 미사용, 어댑터 자리만 보존 |

**제외 항목**: ControlNet/Stable Diffusion (GPU 필요, 한글 fine-tune 비용, 윤리·법 리스크), Donut (한글 fine-tune 데이터 부재, CPU 느림)

---

## 4. 파이프라인 (5단)

각 단계는 결과를 Kafka 토픽에 부분 산출물로 기록한다 (기존 Kafka 모니터링 인프라 재활용, #32).

| 단계 | 컴포넌트 | 책임 | 실패 시 처리 |
|---|---|---|---|
| **L1. Ingest** | pikepdf (PDF 정규화), OpenCV (디스큐) | 다중 페이지 분리, 회전 보정, 해상도 체크 (<200dpi 거부) | 재제출 (상황 A) |
| **L2. Classify** | 키워드 룰 + CLIP zero-shot | 등본·재직증명서·신분증 자동 판별 | 사용자에게 서류명 선택 요청 |
| **L3. OCR + Masking** | PaddleOCR + Regex | 텍스트 + bbox 추출, PII 1차 마스킹 | 신뢰도 < 0.7 영역은 LLM 입력 제외 |
| **L4. Extract (LLM)** | Qwen2.5:3b + JSON Schema | bbox·텍스트 → 구조화 필드 매핑 | JSON 파싱 실패 시 1회 retry → 룰 fallback |
| **L5. Verify** | Spring Rule Engine + Forgery Detector | 마스터 테이블 대조, 위변조 시그널 집계, 라우팅 결정 | 점수 기반 라우팅 |

Kafka 토픽: `doc-agent.submission.received`, `.classified`, `.extracted`, `.verified`, `.routed`

---

## 5. 위변조 시그널 (4 카테고리)

**핵심 원칙**: doc-agent는 **위변조 판정을 내리지 않는다**. 의심 신호의 점수·증거만 산출하고 사람이 확정한다.

### 5.1 메타데이터 시그널
- PDF `/Producer`, `/Creator` 필드 (Photoshop, GIMP 등 등장 시 +0.4)
- `ModifyDate > CreateDate + 5분` 이상 → +0.2
- EXIF Software 태그
- **단독 판정 불가** (스캐너·모바일 앱은 정상적으로 편집 도구 사용)

### 5.2 시각 시그널 (PIL/OpenCV, CPU OK)
- **ELA** (Error Level Analysis): 재저장 압축 차이로 편집 영역 검출
- **Copy-Move 탐지**: 동일 패턴 블록 (도장 복붙)
- **폰트 일관성**: 한 필드 내 글자 높이·간격 분산 임계치 초과
- **노이즈 패턴 불연속**: 페이지 내 노이즈 프로파일 차이

### 5.3 의미 시그널 (LLM/Rule)
- 서류 간 이름·주소·소유자 불일치
- 날짜 모순 (발급일 > 오늘, 등본 발급일 < 신청일 -90일 등)
- 소득 vs 직급 Z-score 이상
- **체크섬**: 주민번호 검증식, 사업자번호 검증식

### 5.4 외부 검증
- **운전면허 진위확인**: 공공데이터포털 (도로교통공단) 실연동, 일 1,000회 한도, 결과 1일 캐시
- 주민등록증·외국인증: Mock 어댑터 (실제 키 발급 불가, 인터페이스만 동일)
- `IdentityVerificationPort` 추상화 → 어댑터 교체로 운영 전환

### 5.5 점수 합산 및 라우팅

| 자동 점수 | 시스템 동작 | 최종 확정 |
|---|---|---|
| < 0.3 | **AUTO_PASS** — ai-service 전달 | 자동 (사후 샘플링 감사) |
| 0.3 ~ 0.7 | **NEEDS_RESUBMIT** — 형식 문제로 가정, 재제출 안내 | 자동 |
| ≥ 0.7 또는 체크섬 실패 | **HOLD** — 잠금, 심사원 큐, 고객엔 "확인 중" 표시 | **사람만 위변조 확정/해제** |
| 심사원이 위변조 확정 | **LOCKED + 감사팀 이관** | 사람 (감사팀)만 형사조치 결정 |

> **임계치 0.3 / 0.7 / 0.9는 잠정값.** Phase D-4 golden set 구축 후 ROC 곡선 + 비용 함수(FN cost ≫ FP cost)로 확정. 운영 후 월 1회 재캘리브레이션.

**자동 UNLOCK 경로 없음.** 사람만 호출하는 별도 엔드포인트로만 해제 가능.

---

## 6. DDL — 마스터 테이블 및 운영 로그

### 6.1 LOAN_PRODUCT_DOCUMENTS (마스터)
```sql
CREATE TABLE LOAN_PRODUCT_DOCUMENTS (
  product_id          VARCHAR(10),
  product_name        VARCHAR(100),
  req_doc_code        VARCHAR(10),
  req_doc_name        VARCHAR(100),
  is_essential        BOOLEAN,
  valid_days          INT,            -- NULL이면 만료 없음
  accepted_formats    VARCHAR(50),    -- 'pdf,jpg,png'
  min_dpi             INT DEFAULT 200,
  issuer_type         VARCHAR(20),    -- 'GOV24'|'COMPANY'|'BANK'
  auto_verify_enabled BOOLEAN,        -- false면 무조건 심사원 라우팅
  retention_days      INT,
  PRIMARY KEY (product_id, req_doc_code)
);
```

### 6.2 LOAN_DOCUMENT_SUBMISSION (운영 로그)
```sql
CREATE TABLE LOAN_DOCUMENT_SUBMISSION (
  submission_id     UUID PRIMARY KEY,
  application_id    VARCHAR(50),
  doc_code          VARCHAR(10),
  raw_object_key    VARCHAR(500),   -- MinIO/R2 경로, KMS 암호화
  masked_object_key VARCHAR(500),
  forgery_score     NUMERIC(3,2),
  verify_status     VARCHAR(20),    -- AUTO_PASS|NEEDS_RESUBMIT|HOLD|LOCKED|CLEARED
  reviewer_id       VARCHAR(50),
  retention_until   DATE,
  legal_hold        BOOLEAN DEFAULT FALSE,
  created_at        TIMESTAMP,
  updated_at        TIMESTAMP
);
```

### 6.3 LOAN_FORGERY_SIGNAL (시그널 로그, 향후 학습 데이터)
```sql
CREATE TABLE LOAN_FORGERY_SIGNAL (
  signal_id     BIGSERIAL PRIMARY KEY,
  submission_id UUID REFERENCES LOAN_DOCUMENT_SUBMISSION,
  category      VARCHAR(20),  -- META|VISUAL|SEMANTIC|EXTERNAL
  signal_type   VARCHAR(50),  -- META_EDIT_TOOL, ELA_HIGH, COPY_MOVE, SSN_CHECKSUM_FAIL ...
  score         NUMERIC(3,2),
  evidence      JSONB,
  detected_at   TIMESTAMP
);
```

### 6.4 예시 데이터
| product_id | product_name | req_doc_code | req_doc_name | is_essential | valid_days | issuer_type |
|---|---|---|---|---|---|---|
| P001 | 직장인 신용대출 | DOC_01 | 신분증 | Y | NULL | GOV24 |
| P001 | 직장인 신용대출 | DOC_02 | 주민등록등본 | Y | 90 | GOV24 |
| P001 | 직장인 신용대출 | DOC_03 | 재직증명서 | Y | 30 | COMPANY |
| P001 | 직장인 신용대출 | DOC_04 | 근로소득원천징수영수증 | Y | 365 | COMPANY |
| P002 | 주택담보대출 | DOC_05 | 부동산 등기부등본 | Y | 90 | GOV24 |
| P002 | 주택담보대출 | DOC_06 | 매매계약서 | Y | NULL | PRIVATE |

---

## 7. 표준 출력 JSON 스키마

doc-agent → ai-service 전달 표준. 필드별 confidence·source_doc·routing 포함.

```json
{
  "schema_version": "1.0",
  "submission_id": "uuid",
  "application_id": "LN-2026-...",
  "document_verification": {
    "status": "SUCCESS|NEEDS_RESUBMIT|HOLD|LOCKED",
    "overall_confidence": 0.94,
    "missing_documents": [
      {"code": "DOC_03", "reason": "EXPIRED", "expired_at": "2026-04-01"}
    ],
    "forgery": {
      "ai_signal_score": 0.12,
      "ai_signal_summary": "메타데이터 정상, 시각 시그널 정상",
      "signals": [
        {"category": "META", "type": "META_EDIT_TOOL", "score": 0.4, "evidence": "Producer=Photoshop"}
      ],
      "human_review_status": "NOT_REQUIRED",
      "human_reviewer_id": null,
      "human_decided_at": null
    },
    "consistency_check": {
      "name_matched": true,
      "address_matched": true,
      "owner_matched": true,
      "mismatches": []
    }
  },
  "extracted_data": {
    "applicant": {
      "name": {"value": "홍*동", "confidence": 0.98, "source_doc": "DOC_01"},
      "masked_ssn": {"value": "900101-1******", "checksum_valid": true, "source_doc": "DOC_01"}
    },
    "financial_info": {
      "annual_income": {"value": 50000000, "unit": "KRW", "source_doc": "DOC_04", "confidence": 0.91},
      "income_source_verified": true
    },
    "collateral_info": {
      "property_address": {"value": "서울시 ...", "source_doc": "DOC_05"},
      "prior_bond_amount": {"value": 120000000, "source_doc": "DOC_05"},
      "is_clean_title": true
    }
  },
  "routing": {
    "next_step": "AI_PRE_REVIEW|HUMAN_REVIEWER|FRAUD_AUDIT",
    "reason_code": "OK",
    "sla_minutes": 5
  },
  "audit": {
    "processed_at": "2026-05-28T10:00:00+09:00",
    "pipeline_version": "doc-agent-0.3.0",
    "models": ["paddleocr-ko-3.0", "qwen2.5:3b-q4"]
  }
}
```

`human_review_status`: `NOT_REQUIRED | PENDING | CLEARED | CONFIRMED_FORGERY`

---

## 8. PII·보안

### 8.1 원본 저장
- 원본: `s3://doc-agent-raw/{yyyy}/{mm}/{application_id}/{submission_id}/original.pdf` (Vault Transit 봉투암호화)
- 마스킹본: `s3://doc-agent-masked/.../masked.pdf` (파이프라인 사용)
- 로컬 = MinIO, 클라우드 데모 = Cloudflare R2 (10GB 영구 무료, S3 API 호환)
- 코드는 AWS SDK S3 인터페이스 단일 — endpoint 환경변수만 변경

### 8.2 KMS — HashiCorp Vault
- Dev 모드 컨테이너, Transit 엔진
- Spring `spring-cloud-vault` 의존성으로 envelope 암호화
- 운영 전환 시 동일 API로 AWS KMS / Azure Key Vault 교체 가능

### 8.3 마스킹·LLM 안전
- Regex로 PII 치환 후 bbox 참조만 LLM에 전달 → 모델이 주민번호 원본을 못 봄
- 모델 출력에서 SSN 패턴 후필터 (prompt injection 대비)
- Logback PII 마스킹 필터 (주민번호·전화·계좌번호 패턴)

---

## 9. 보존 정책

법적 근거:
- 전자금융거래법: 거래기록 5년
- 신용정보법: 신용정보 거래종료 후 5년
- 특금법(자금세탁): 의심거래 5년
- 사기죄 공소시효: 10년 (특경법 15년)

| 케이스 | 보존기간 | 메커니즘 |
|---|---|---|
| 정상 거절 | 30일 | `retention_until = created_at + 30d` |
| 정상 승인 | **5년** | `retention_until = approved_at + 5y` |
| 사기 의심 (HOLD 발생) | **10년** | 별도 보안 영역 이관, `retention_until = hold_at + 10y` |
| 형사 입건 | **무기한** | `legal_hold = TRUE`, hold 동안 retention_until 무시 |

배치 잡 (기존 EOD 배치 패턴 활용): 일 1회 `retention_until < today AND legal_hold = FALSE` 항목 파기.

> 15년 일괄 보존은 법적으론 안전하나 **개인정보 최소수집·최소보관 원칙 위반 소지**. 차등 + legal_hold가 표준.

---

## 10. 신원확인 어댑터

```java
interface IdentityVerificationPort {
  VerificationResult verify(IdentityType type, IdentityPayload payload);
}
```

| 어댑터 | 실연동 여부 | 출처 |
|---|---|---|
| `DriverLicenseVerificationAdapter` | **실연동** | 공공데이터포털 (도로교통공단), 일 1,000회 |
| `ResidentCardVerificationAdapter` | Mock | 행안부 협약 미보유 |
| `ForeignerCardVerificationAdapter` | Mock | 인터페이스만 동일 |

결과는 Redis(또는 Postgres 캐시 테이블) TTL 1일 캐시. 동일 인물 재신청 시 호출량 절감.

---

## 11. 합성 데이터셋

한국어 신분증·등본 위변조 공개셋 부재 → 자체 합성 필수.

### 11.1 모듈 구조 (기존 `synthetic-data-generator`와 분리)
```
synthetic-docs/                       (신규)
├── README.md                         AI Hub 다운로드 가이드, 라이선스 표기
├── scripts/
│   ├── sample_normal.py              AI Hub "공공행정문서 OCR" 샘플링
│   ├── augment_realistic.py          Albumentations 현실 변형
│   ├── forge_text_replace.py         PIL inpaint + 텍스트 치환 (DocTamper 참조)
│   ├── forge_copy_move.py            Copy-Move 합성
│   ├── forge_metadata.py             PDF Producer / EXIF 오염
│   ├── gen_font_pairs.py             TRDG 폰트 일관성 페어
│   └── build_goldenset.py            라벨 CSV
└── data/                             .gitignore
    ├── normal/                       (AI Hub 다운로드본)
    ├── forged/                       (합성 결과)
    └── goldenset.csv
```

### 11.2 합성 분포 (잠정)
- 정상 100 → Albumentations 증강 → 300건 (회전·그림자·JPEG·perspective)
- 위조 ~55건
  - 텍스트 치환 25
  - Copy-Move 15
  - 메타데이터 오염 10
  - ControlNet/SD: **제외**

### 11.3 라이선스
- AI Hub: 비영리·시연 OK, **원본 재배포 금지** → `.gitignore`, 사용자 직접 다운로드
- DocTamper / TRDG / Albumentations: MIT/Apache, 합성 코드만 레포 포함
- README에 출처·라이선스 명시 의무

---

## 12. 회귀 평가 기준

골든셋 기반 회귀를 CI에서 자동 실행. 모델·임계치 변경 PR은 반드시 회귀 통과.

**잠정 SLA** (Phase D-4 골든셋 구축 후 ROC로 확정):
- 정상 통과율 ≥ **95%** (한국어 OCR SOTA 기준)
- 위조 탐지율 ≥ **80%** (다중 시그널 합산, DocTamper baseline 73~85% 참조)
- 오탐(FP) ≤ **5%** (심사원 업무량 한도)

테스트 격리: `loan-service` 패턴 따라 배치 테스트별 연도 분리 (2030 / 2040 / ...).

---

## 13. 관측성 — 가이드 문서

대시보드 구현은 다른 담당자가 진행. **doc-agent는 메트릭 노출 코드(Micrometer)와 가이드 문서만** 제공.

### 13.1 메트릭 명세 (별도 문서 `docs/monitoring/doc-agent-metrics.md`)

| 메트릭 | 타입 | 라벨 | 의미 |
|---|---|---|---|
| `doc_agent_submissions_total` | Counter | doc_type, status | 처리 건수 |
| `doc_agent_ocr_latency_seconds` | Histogram | doc_type, engine | OCR 지연 |
| `doc_agent_llm_latency_seconds` | Histogram | model | LLM 지연 |
| `doc_agent_llm_json_parse_failures_total` | Counter | model | JSON Schema 위반 (Qwen 운영 최대 리스크) |
| `doc_agent_forgery_score` | Histogram | doc_type | 위변조 점수 분포 (편향 모니터링) |
| `doc_agent_routing_total` | Counter | decision | AUTO_PASS / RESUBMIT / HOLD 분포 |
| `doc_agent_identity_api_calls_total` | Counter | adapter, result | 외부 진위확인 호출량 (한도 관리) |

### 13.2 SLO 후보
- p95 처리시간 ≤ 30초
- LLM JSON 파싱 실패율 ≤ 1%
- 일 HOLD 비율 ≤ 5% (운영 안정 지표)

PromQL 쿼리 예시·권장 알람 임계치는 가이드 문서에 별도 작성.

---

## 14. 도커 배치

### 14.1 메모리 분배 (16GB 노트북)
```
OS + Docker Desktop         4GB
기존 Spring 서비스 5~6개     6GB  (각 -Xmx384m, SerialGC)
PostgreSQL / Kafka / ZK     2GB
ollama (host, Qwen2.5:3b)   2.5GB
inference-server (PaddleOCR) 1.5GB
                          ─────
                           16GB → 빠듯
```

### 14.2 전략
- **Ollama만 호스트 직접 설치** (mmap 효율, 컨테이너보다 메모리 절약)
- doc-agent 컨테이너는 `host.docker.internal:11434` 호출
- 나머지 모두 컨테이너
- Spring 서비스 JVM 다이어트: `-Xmx384m -XX:+UseSerialGC`
- compose profile로 무거운 서비스 토글: `docker compose --profile doc up`

### 14.3 신규 컨테이너
```yaml
services:
  doc-agent:
    profiles: ["doc"]
    mem_limit: 1g
  inference-server:        # 기존 재사용, PaddleOCR 엔드포인트 추가
    profiles: ["doc", "ai"]
    mem_limit: 2g
  minio:
    profiles: ["doc"]
    mem_limit: 512m
  vault:
    image: hashicorp/vault:1.15
    command: server -dev -dev-root-token-id=root
    profiles: ["doc"]
    mem_limit: 256m
```

---

## 15. 단계적 롤아웃

[feedback_one_step_at_a_time.md] 원칙: 한 단계 끝낼 때마다 커밋 + 보고 후 멈춤.

| Phase | 범위 | 완료 기준 |
|---|---|---|
| **D-0** | 서비스 스캐폴딩, Kafka 토픽, DDL (3종), MinIO/Vault compose | 마이그레이션 적용, 헬스체크 |
| **D-1** | L1~L3 (Ingest·Classify·OCR·Masking) + JSON 스켈레톤 | 등본 1종 정상 추출, PII 마스킹 검증 |
| **D-2** | L4 Extract (Ollama Qwen2.5:3b + JSON Schema) + 필드별 confidence | 직장인 신용대출 4종 서류 추출 |
| **D-3** | L5 Verify — 룰 정합성·만료일·체크섬, 운전면허 진위확인 실연동 | 누락/만료 라우팅 (상황 A) 동작 |
| **D-4** | 위변조 시그널 (메타·ELA·Copy-Move·의미), 골든셋 구축, 임계치 ROC 확정 | 회귀 SLA 통과, HOLD 라우팅 동작 |
| **D-5** | ai-service / review-ai-gateway 연계, 감사팀 큐, 심사원 UNLOCK 엔드포인트 | E2E 신청→심사 한 사이클 |
| **D-6** | 주담대 서류군 확장 (등기부등본 PP-Structure, 매매계약서) | P002 상품 전체 커버 |

커밋 규칙: `feat(doc-agent): ...` 와 `test(doc-agent): ...` 분리 ([feedback_split_feat_test_commits.md]).

---

## 16. 리스크 및 미해결 항목

### 16.1 기술 리스크
1. **Qwen2.5:3b 한글 JSON 안정성**: Ollama JSON Schema 강제로 1차 방어. 정확도 부족 시 EXAONE-3.5-2.4B 또는 Qwen2.5:7b-q4로 승급 (RAM 재배분 필요)
2. **16GB RAM 한계**: 전체 동시 가동 시 빠듯. compose profile로 무거운 서비스 토글하는 워크플로 정착 필요
3. **등기부등본 표 인식**: PP-StructureV2 한국어 갑구/을구 정확도 사전 검증 필요 (D-6 진입 전)
4. **외부 API 한도**: 운전면허 진위확인 일 1,000회 — 캐시 + 백오프 필수

### 16.2 비기술 리스크
1. **AI Hub 라이선스**: 비영리·시연 OK이나 실서비스 전환 시 재계약 필요. 포트폴리오 단계엔 무관
2. **자동 위변조 오탐 = 분쟁 소지**: 시스템 자동 LOCK ❌ — 사람만 확정 원칙 고수
3. **MinIO AGPL**: 실서비스 전환 시 SeaweedFS 등 대체 검토. 포트폴리오엔 무관

### 16.3 미해결 (구현 진입 전 결정 필요)
- [ ] `inference-server`(기존)에 PaddleOCR 엔드포인트 추가 vs 별도 컨테이너 분리 — 기존 서버 코드 확인 후 결정
- [ ] Kafka 이벤트 페이로드 스키마 (Avro vs JSON) — 기존 토픽 컨벤션 확인 후 결정
- [ ] 심사원 UI는 기존 `web`에 통합인지 별도 페이지인지

---

## 부록 A. 참고 자료

- AI Hub "공공행정문서 OCR" / "금융 특화 문서 OCR" / "한국어 글자체"
- DocTamper (ICCV'23, MIT)
- PaddleOCR / PP-StructureV2 (Apache 2.0)
- Ollama JSON Schema (0.5+)
- HashiCorp Vault Dev / OpenBao
- 공공데이터포털 — 도로교통공단 운전면허 진위확인
- Cloudflare R2 무료 한도 (10GB)

## 부록 B. 관련 메모리·기존 문서

- [feedback_commit_no_ai_attribution.md] 커밋·PR에 AI 흔적 금지
- [feedback_split_feat_test_commits.md] feat/test 분리 커밋
- [feedback_one_step_at_a_time.md] 한 단계씩 진행 후 보고
- [feedback_test_isolation_dates.md] 배치 테스트 연도 격리
- 기존 문서: `docs/plan/pre-review-agent-plan.md`, `docs/plan/llm-pipeline.md`, `docs/plan/loan_eod_batch_plan_v2.md`
