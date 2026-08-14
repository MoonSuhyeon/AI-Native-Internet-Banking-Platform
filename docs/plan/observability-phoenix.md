# 관측 일원화 — 성능은 Prometheus, 품질은 Phoenix

> 에이전트가 **왜 그렇게 판단했는지**를 남기고 채점하는 일. 성능·비용·에러율은
> 이미 Prometheus·Grafana·Loki 가 보고 있으므로 여기서 다루지 않는다.

---

## 지금 상태 — 확인한 사실

### Phoenix 는 아무것도 수집하지 않는다

| 있는 것 | 없는 것 |
|---|---|
| `docker-compose.yml` 에 phoenix 컨테이너 (6006 · 4317) | **계측 코드 0줄** |
| `consultation/requirements.txt` 에 `arize-phoenix-otel`, `openinference-instrumentation-openai`, otel sdk·exporter | tracer provider 등록 지점 |
| `.env.sample` 에 `PHOENIX_ENABLED=false`, `PHOENIX_GRPC_ENDPOINT` | |

`consultation/app/main.py:8` 주석이 `_setup_phoenix` 를 언급하지만 **그 함수는 어디에도 없다.**
컨테이너는 뜨고 UI 도 열리는데 비어 있다. 이 레포가 반복해 온 *조용한 실패*와 같은 모양이다.

### Langfuse 는 절반만 켜져 있고, 켜도 트리가 안 나온다

| 서비스 | `LANGFUSE_ENABLED` |
|---|---|
| `auto-loan-review` · `review-ai-gateway` (Java) | `true` |
| `fraud-investigation-agent` · `consultation` · `goal-agent` (Python) | 미설정 → 기본 `false` → **전부 no-op** |

그리고 `fraud-investigation-agent/src/agent/tracing.py` 의 `trace_node` 는 **span 마다
`Langfuse()` 클라이언트를 새로 만든다.** 부모-자식 관계가 없어 `plan` · `act` · `observe`
가 각각 루트 span 이 된다. 조사 한 건이 하나의 트리로 보이지 않는다.

---

## 이 레포에 그대로 옮길 수 없는 것 둘

### 1. 외부 LLM 심판은 유출 경로를 하나 더 만든다

Phoenix Evals 의 표준 사용법은 `OpenAIModel(model="gpt-4o")` 를 심판으로 쓰는 것이다.
그런데 trace 에는 고객 거래·계좌·인증 이력이 들어 있다. 심판에 넘기는 순간 그것이
외부로 나간다. [`OPEN_ITEMS §3-c`](../OPEN_ITEMS.md) 에 이미 *"Langfuse·Phoenix 가
프롬프트·응답을 그대로 적재한다"* 가 1순위 유출로 올라와 있고, 심판을 붙이면 두 배가 된다.

**대안은 이미 레포 안에 있다.** `tool_matrix.py` 가 도구마다 *어떤 시나리오 쌍을
가르는지*를 결정적으로 적어 두었다.

```python
"get_device_fingerprint": {
    "separates": [(H1_VOICE_PHISHING, H2_ACCOUNT_TAKEOVER), ...],
    "decisive": False,
}
```

즉 **"이 상황에서 이 도구를 고른 것이 맞았나"를 LLM 없이 채점할 수 있다.** 금융에서는
이쪽이 오히려 맞다 — 심판이 결정적이어야 같은 입력에 같은 점수가 나오고, 그래야 감사에서
설명이 된다. LLM 심판은 근거성(groundedness) 하나에만 쓰고, 그것도 내부
`inference-server` 로 돌린다.

### 2. 계측을 늘리면 유출도 같이 늘어난다

표준 순서는 "계측 → 관측 → 평가" 지만, 여기서는 **마스킹이 계측과 같은 PR 에 있어야
한다.** 나중에 붙이면 그 사이에 쌓인 trace 가 전부 PII 사본이다.

---

## 단계

### Phase 0 — 경계를 정한다 (결정)

| 도구 | 무엇을 보나 | 상태 |
|---|---|---|
| Prometheus · Grafana · Loki | 성능 · 비용 · 에러율 | 있음 |
| **Phoenix** | **에이전트 판단의 품질** | **비어 있음** |
| Langfuse | Phoenix 와 겹친다 | 절반만 켜짐 |

**결정: Phoenix 로 모으고 Langfuse 는 걷어낸다.**

- OpenInference 는 OpenTelemetry 표준이라 벤더 중립이다. Langfuse 는 자체 SDK 다.
- 둘 다 두면 계측 지점이 두 벌이 되고, **마스킹도 두 벌** 해야 한다. 한쪽을 빠뜨리면
  그쪽이 유출 경로로 남는다.
- Langfuse 는 Java 두 곳에만 실제로 걸려 있어 옮기는 비용이 작다.

### Phase 1 — Phoenix 를 실제로 켠다 (조사 에이전트부터)

조사 에이전트가 먼저인 이유: 이 레포에서 **자유도가 가장 높은 에이전트**이고(스스로
도구를 고른다), 그래서 "왜 그렇게 판단했나" 가 가장 필요한 곳이다.

| 할 일 | 지금 |
|---|---|
| tracer provider 를 기동 시 **한 번** 등록 | span 마다 클라이언트 생성 |
| span kind 매핑 — `AGENT` → `LLM`(plan) → `TOOL`(act) → `LLM`(observe) | 종류 구분 없음 |
| 조사 1건을 루트 span 으로, 노드를 자식으로 | 전부 루트 |
| `session.id = case_id` | 없음 |

### Phase 2 — 마스킹 (Phase 1 과 같은 PR)

`PiiMaskingUtil` 이 이미 있으나 RAG 색인·자문 의견에만 걸려 있다. span attribute 가
나가는 경로에 processor 를 두고 거기서 거른다.

검증은 이 레포 방식대로 — **마스킹을 빼면 실패하는 테스트**를 같이 넣는다.

### Phase 3 — 결정적 평가

| 지표 | 채점 방법 | LLM 필요 |
|---|---|---|
| 도구 선택 정확도 | `TOOL_MATRIX.separates` 대조 | ✕ |
| 경로 효율성 | `budget_left` 소비량 | ✕ |
| fail-closed 준수 | `decisive: True` 도구가 결정적 사실을 냈는데 루프가 계속됐나 | ✕ |
| 근거성 | 권고가 수집한 증거에 근거하나 | ○ — **내부 추론 서버** |

### Phase 4 — 회귀

`data/cases` 에 사건이 이미 있다. Phoenix Dataset 으로 올리면 프롬프트·모델·도구 표를
바꿀 때 Experiment 로 전후를 비교할 수 있다. 회귀 테스트 역할을 한다.

### Phase 5 — 감사 연결

`agent_audit_log` 는 UPDATE/DELETE 차단 트리거가 걸린 WORM 테이블이다. 여기에 trace id
를 같이 남기면 **"이 권고가 왜 나왔나"를 감사 시점에 재구성**할 수 있다.

일반 서비스에서 Phoenix 는 디버깅 도구지만, 여기서는 **설명책임 장치**다. README 의
`auditability` 가 실제로 뒷받침되는 지점이 여기다.

---

## 순서를 이렇게 잡은 이유

```
Phase 0   겹치는 도구를 먼저 정리 — 안 하면 마스킹을 두 벌 해야 한다
Phase 1 ─┐ 같은 PR 이어야 한다. 계측만 먼저 켜면
Phase 2 ─┘ 그 사이 trace 가 전부 PII 사본이 된다
Phase 3   결정적 채점부터. LLM 심판은 근거성 하나만
Phase 4   평가가 있어야 회귀 비교가 의미를 가진다
Phase 5   감사 연결이 목적이고, 앞의 넷은 준비다
```

## 참고

- [`../OPEN_ITEMS.md`](../OPEN_ITEMS.md) — §3-c 추적 저장소 PII, §7-7 가명처리
- [`../decisions/agent-harness-consolidation.md`](../decisions/agent-harness-consolidation.md) — 에이전트 공통 규약
