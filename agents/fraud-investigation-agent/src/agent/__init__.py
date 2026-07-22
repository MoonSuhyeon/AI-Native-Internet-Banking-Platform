"""Fraud Investigation Agent — PoC 패키지.

설계 기준: ../corpus_registry.md §16 (루프 7노드 · 16-2 2축 가설 · 16-3 도구 ·
16-4 Tool Matrix+혼합형 Planner · 16-5 종료조건). 핵심 원칙은 ../CLAUDE.md.

모듈 지도:
- models       : 도메인 모델 + AgentState (LangGraph state)            [Stage 1 구현]
- tool_matrix  : 도구 분별력 사전 (어느 도구가 어느 가설쌍을 가르나)   [stub]
- tools        : AXful 자산을 Tool로 노출 (조회 도구, Stage 6까지 목)  [stub]
- hypotheses   : 가설 초기화·증거 반영·신뢰도 갱신                      [stub]
- llm          : 가설 판단·도구 선택 LLM (Stage 6까지 목)              [stub]
- graph        : LangGraph 루프 (가설→계획→실행→반영→게이트)          [stub]
- recommend    : 종료 후 권고 + 책임 등급(§12) 생성                    [stub]
"""

__all__ = ["models"]
