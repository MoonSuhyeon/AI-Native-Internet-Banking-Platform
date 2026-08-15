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

### Langfuse 는 켜져 있는 것처럼 보였을 뿐이다

> **정정.** 처음엔 "자바 두 곳은 켜져 있다"고 적었다. 아니었다. compose 의
> `LANGFUSE_ENABLED: "true"` 를 **읽는 코드가 없다.** 자바의 실제 스위치는
> `HARNESS_TRACING_ENABLED` 이고 compose 가 설정하지 않아 꺼져 있었으며,
> 엔드포인트도 `localhost:4318` 이라 아무 데도 가지 않았다. 죽은 설정이
> "자바 추적이 꺼져 있다" 는 사실을 가리고 있었다.

| 서비스 | 실제 상태 |
|---|---|
| `auto-loan-review` · `review-ai-gateway` (Java) | `HARNESS_TRACING_ENABLED` 미설정 → **꺼짐**. 다만 코드는 이미 OTLP·`gen_ai.*` 표준이라 벤더 중립 |
| `fraud-investigation-agent` · `consultation` · `goal-agent` (Python) | `LANGFUSE_ENABLED` 미설정 → **전부 no-op** |

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

### Phase 0 — 경계를 정한다 ✅

| 도구 | 무엇을 보나 | 상태 |
|---|---|---|
| Prometheus · Grafana · Loki | 성능 · 비용 · 에러율 | 있음 |
| **Phoenix** | **에이전트 판단의 품질** | **비어 있음** |
| Langfuse | Phoenix 와 겹친다 | 절반만 켜짐 |

**결정: Phoenix 로 모으고 Langfuse 는 걷어낸다.**

- OpenInference 는 OpenTelemetry 표준이라 벤더 중립이다. Langfuse 는 자체 SDK 다.
- 둘 다 두면 계측 지점이 두 벌이 되고, **마스킹도 두 벌** 해야 한다. 한쪽을 빠뜨리면
  그쪽이 유출 경로로 남는다.
- 자바는 이미 OTLP 라 옮길 것이 없었다. 파이썬 쪽 `@observe` 7곳·`langfuse_context`
  3곳만 하네스 계약으로 바꾸면 됐다. langfuse·langfuse-db 컨테이너(1.28GB)도 걷어냈다.

### Phase 1 — Phoenix 를 실제로 켠다 ✅ (조사 에이전트부터)

조사 에이전트가 먼저인 이유: 이 레포에서 **자유도가 가장 높은 에이전트**이고(스스로
도구를 고른다), 그래서 "왜 그렇게 판단했나" 가 가장 필요한 곳이다.

| 할 일 | 지금 |
|---|---|
| tracer provider 를 기동 시 **한 번** 등록 | span 마다 클라이언트 생성 |
| span kind 매핑 — `AGENT` → `LLM`(plan) → `TOOL`(act) → `LLM`(observe) | 종류 구분 없음 |
| 조사 1건을 루트 span 으로, 노드를 자식으로 | 전부 루트 |
| `session.id = case_id` | 없음 |

### Phase 2 — 마스킹 ✅ (Phase 1 과 같은 커밋)

`PiiMaskingUtil` 이 이미 있으나 RAG 색인·자문 의견에만 걸려 있다. span attribute 가
나가는 경로에 processor 를 두고 거기서 거른다.

검증은 이 레포 방식대로 — **마스킹을 빼면 실패하는 테스트**를 같이 넣는다.

### Phase 3 — 결정적 평가 ✅

`src/agent/evaluation.py` · `tests/test_evaluation_baseline.py` · `scripts/evaluate_cases.py`

판정 6종으로 나눴다. 앞의 셋은 예산을 쓸 값어치가 있었다는 뜻이고, 뒤의 셋은 각각
다른 이유로 흘렸다는 뜻이다 — 뭉뚱그리면 원인별로 고칠 수 없다.

| 판정 | 뜻 |
|---|---|
| `DECISIVE_PROBE` | 결정적 사실을 낼 수 있는 도구 — 언제 불러도 정당 |
| `INFORMATIVE` | 경합 중인 시나리오 쌍을 가른다 |
| `TAG_REVEALING` | 시나리오는 못 가르나 안 켜진 태그를 켤 수 있다 |
| `SETTLED` | 가르긴 하는데 그 쌍이 이미 닫혔다 — **플래너 오판** |
| `CONTEXT_ONLY` | 애초에 가르는 게 없는 baseline 도구 |
| `REDUNDANT` | 이미 부른 도구 — 순수 낭비 |

채점하려면 **선택 시점에 무엇이 경합 중이었는지**가 있어야 한다. `reason` 은 LLM 이
쓴 문장이라 그것만으로는 타당했는지 확인할 수 없어서, `ToolLogEntry` 에 `competing`
과 `tags_on` 을 같이 남기게 했다. 감사 기록이 서술에서 검증 가능한 것으로 바뀐다.

**첫 실측 (목 플래너, 7건)**

```
case_death        1회 100%   DECISIVE_PROBE×1
case_deceased     1회 100%   DECISIVE_PROBE×1
case_h1           2회 100%   INFORMATIVE×2
case_h1_flipped   2회  50%   INFORMATIVE×1 · SETTLED×1
case_h2           2회  50%   INFORMATIVE×1 · SETTLED×1
case_h5           6회  50%   INFORMATIVE×3 · SETTLED×2 · CONTEXT_ONLY×1
case_provisional  6회  50%   INFORMATIVE×3 · SETTLED×2 · CONTEXT_ONLY×1
```

7건 중 4건이 예산 절반을 판별에 못 쓴다. fail-closed 는 7건 다 지켜졌고 중복 호출은
없다. **이건 목 플래너의 성적이지 실제 LLM 의 성적이 아니다** — 스크립트가 어느
플래너로 돈 것인지 첫 줄에 찍는 이유다.

남은 것: 근거성(groundedness)만 LLM 심판이 필요하고, 그것도 내부 `inference-server`
로 돌린다.

### Phase 4 — 회귀

`data/cases` 에 사건이 이미 있다. Phoenix Dataset 으로 올리면 프롬프트·모델·도구 표를
바꿀 때 Experiment 로 전후를 비교할 수 있다. 회귀 테스트 역할을 한다.

### Phase 5 — 감사 연결 ✅

`AgentAuditEntry.trace_id` 는 계약에 **"감사와 추적을 잇는 유일한 연결고리"** 라고
적혀 있었다. 실제로는 이랬다.

| 자리 | 들어가던 값 |
|---|---|
| 조사 권고(`record_investigation`) | **아예 안 넣음** → 항상 NULL |
| 조사 실행(API 경로) | LangGraph **스레드 id** → Phoenix 에서 못 찾는 값 |
| 조사 실행(`approve_and_execute`) | 스레드 id 조차 안 넘김 |
| 상담·목표 에이전트 | 파라미터만 있고 **아무도 안 넘김** → 항상 NULL |

**빈 값보다 틀린 값이 나쁘다.** 빈 값은 "없다" 를 말하지만, 틀린 값은 있다고 말한 뒤
따라가면 없다.

이제 `current_trace_id()` 가 OTel 추적 id 를 준다. 추적이 꺼져 있으면 `None` 이고
그대로 남긴다 — 다른 식별자로 채우면 이어지지 않는 링크가 이어지는 것처럼 보인다.
스레드 id 는 버리지 않고 `request_json` 에 남긴다(그 용도로는 유효하다).

**받는 시점이 중요하다.** 감사 기록은 실행이 끝난 뒤에 쓰이는데 그때는 span 이 닫혀
있어 `None` 이 나온다. 그래서 span 안에서 미리 받아 상태에 실어 보낸다.

상담·목표 에이전트는 감사 호출부가 **어떤 span 안에도 없었다.** 턴 하나를 `AGENT`
span 으로 감싸 그 안에서 받게 했다. 이 과정에서 `observe` 가 **비동기 함수를 못
감싸는** 것도 드러났다 — 상담 턴이 async 라, 동기 래퍼로 감싸면 span 이 코루틴을
만들자마자 닫혀 아무것도 안 남고 예외도 안 난다.

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
