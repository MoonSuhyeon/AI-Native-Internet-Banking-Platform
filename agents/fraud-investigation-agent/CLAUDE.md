# Fraud Investigation Agent — PoC 작업 지침

이 디렉터리는 **Fraud Investigation Agent** PoC다. 설계 기준 문서는
[`README.md`](README.md)(요약)와 [`corpus_registry.md`](corpus_registry.md)이며,
특히 **§16 전체**가 구현 기준이다:

- §16-1 루프 7노드 (가설→계획→실행→반영→게이트→권고→HITL)
- §16-2 2축 가설 (시나리오=배타·경쟁 / 태그=공존·on-off)
- §16-3 도구 레이어 (AXful 자산을 Tool로)
- §16-4 Tool Matrix + 혼합형 Planner (Matrix 참고 + LLM 선택)
- §16-5 종료조건 (결정적 사실 → 확정/기각 → 예산 소진 → 경합 재계획)

레포 공통 가이드(`../docs/AI_GUIDELINES.md`)와 루트 `../CLAUDE.md`도 적용된다.
상충 시: 사용자 메시지 > 본 파일 > 루트 CLAUDE.md > 공통 가이드.

---

## 핵심 설계 원칙 (반드시 지킬 것)

1. **결정적 사실은 코드.** 조사 중 사망·후견이 확인되면 LLM 판단과 무관하게 즉시
   종료한다(**fail-closed**). `AgentState.decisive_fact` 가 채워지면 게이트는 예산·신뢰도를
   보지 않고 종료. LLM은 "가설 판단·도구 선택"에만 관여한다.

2. **도구 선택은 혼합형.** Tool Matrix(분별력 사전)를 LLM에 참고로 주고, **LLM이 고르되
   선택 이유를 매번 `tool_log` 에 남긴다.** 목적은 설명가능성·감사·재현. 순수 LLM(설명 불가)도,
   순수 매트릭스(전문가시스템)도 아니다.

3. **동작은 에이전트가 직접 안 한다.** 지급정지·STR은 **권고(ProposedAction)만** 만든다.
   실제 발동은 **HITL(사람 승인) + RBAC**. 에이전트 출력은 제안에서 끝난다.

4. **조사 예산 상한으로 무한 루프 차단.** `budget_left`(도구 호출·재계획 횟수)가 소진되면
   확신이 없어도 종료하고 **부분결과를 분석가에게 인계**(fail-soft).

5. **기본 mock, 실제 LLM 은 opt-in.** `TRIAGE_LLM_PROVIDER` 미설정 시 mock 이라 테스트는
   실제 API 를 치지 않는다. openai/anthropic 선택 시에만 실호출. 벡터·RAG·캐시 안 씀,
   사실은 DB/딕셔너리 조회로만. 벤더 SDK 는 **지연 import**(패키지 import 안정성 유지),
   **API 키는 env 만**(`OPENAI_API_KEY`/`ANTHROPIC_API_KEY`) — 코드·깃 금지(`.env.example` 참조).

---

## 구조

```
fraud-investigation-agent/
├── CLAUDE.md            # 본 파일
├── requirements.txt
├── pytest.ini
├── src/agent/
│   ├── models.py        # 도메인 모델 + AgentState (LangGraph state)  [구현]
│   ├── tool_matrix.py   # 분별력 사전                                  [stub]
│   ├── tools.py         # AXful 자산 조회 도구 (목)                    [stub]
│   ├── hypotheses.py    # 가설 초기화·갱신                             [stub]
│   ├── llm.py           # 가설 판단·도구 선택 (목)                     [stub]
│   ├── graph.py         # LangGraph 7노드 루프                         [stub]
│   └── recommend.py     # 권고 + 책임 등급(§12)                        [stub]
├── scripts/             # CLI 러너 등
├── tests/
└── data/cases/          # 목 사건 데이터
```

## 개발 메모

- 테스트: `pytest` (루트는 이 디렉터리, `pytest.ini` 가 `src` 를 import 경로에 추가).
- import 검증: `PYTHONPATH=src python -c "from agent import models"`.
- 커밋·푸시는 명시 요청 시에만. 푸시 명령은 사용자가 직접 실행.
