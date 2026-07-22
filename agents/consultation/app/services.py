from collections.abc import Callable
from datetime import date, datetime, timezone
from typing import Any

import httpx

from sqlalchemy import bindparam, or_, select, text
from sqlalchemy.orm import Session, aliased

from app.features import ProductFeatureExecutor, StaffFeatureExecutor, UserFinanceFeatureExecutor
from app.features.base import build_history_context
from app.kafka import KafkaEventPublisher
from app.llm import FeatureAnswerFormatter, IntentClassifier, RagAnswerGenerator
from app.rag import ProductRagEngine
from app.models import (
    ChatConsultation,
    ChatMessageHistory,
    ChatbotConsultation,
    ChatbotIntent,
    ChatbotNode,
    ChatbotNodeButton,
    ChatbotNodeFlow,
    ChatbotScenario,
    Consultation,
)
from app.schemas import (
    ButtonResponse,
    ChatbotCategoryResponse,
    ChatbotFeatureExecuteRequest,
    ChatbotFeatureExecuteResponse,
    ChatbotFeatureResponse,
    ChatbotMessageResponse,
    ChatbotStartResponse,
    ChatbotTransferRequest,
    ChatbotTransferResponse,
)

CODE_RECEPTION_METHOD_CHATBOT = 1
CODE_INQUIRY_PRODUCT = 1
CODE_RECEPTION_CHANNEL_CHAT = 1
CODE_CONSULTATION_STATUS_OPEN = 1
CODE_SCENARIO_TYPE_DEFAULT = 1
CODE_CATEGORY_PRODUCT_ADVICE = 1
CODE_NODE_TYPE_MESSAGE = 1
CODE_PROCESS_SCENARIO = 1
CODE_PROCESS_LLM = 2
CODE_SENDER_USER = 1
CODE_SENDER_BOT = 2
CODE_MESSAGE_TYPE_TEXT = 1


class ChatbotService:
    def __init__(
        self,
        db: Session,
        events: KafkaEventPublisher,
        *unused_adapters,
        rag_engine: "ProductRagEngine | None" = None,
        openai_api_key: str = "",
        openai_model: str = "gpt-4o-mini",
    ):
        self.db = db
        self.events = events
        self._classifier = IntentClassifier()
        self._formatter = FeatureAnswerFormatter()
        self._rag_engine = rag_engine
        self._rag_generator = RagAnswerGenerator(openai_api_key, openai_model)

    async def start(self, customer_no: str, entry_screen: str, app_version: str) -> ChatbotStartResponse:
        scenario = self._get_active_scenario()
        if not scenario:
            scenario = self._ensure_default_scenario()
            self.db.commit()
        first_node = self._get_first_node(scenario.scenario_id)
        if not first_node:
            raise ValueError("활성화된 챗봇 시나리오가 없습니다.")

        consultation = Consultation(
            customer_no=customer_no,
            reception_method_code_id=CODE_RECEPTION_METHOD_CHATBOT,
            inquiry_type_code_id=CODE_INQUIRY_PRODUCT,
            reception_channel_code_id=CODE_RECEPTION_CHANNEL_CHAT,
            content_summary="챗봇 상담 시작",
            status_code_id=CODE_CONSULTATION_STATUS_OPEN,
            active_yn="Y",
        )
        self.db.add(consultation)
        self.db.flush()

        chatbot = ChatbotConsultation(
            consultation_id=consultation.consultation_id,
            scenario_id=scenario.scenario_id,
            process_method_code_id=CODE_PROCESS_SCENARIO,
            initial_intent=scenario.scenario_name,
            entry_screen=entry_screen,
            app_version=app_version,
        )
        self.db.add(chatbot)
        self.db.flush()
        self._record_message(
            chatbot,
            first_node,
            CODE_SENDER_BOT,
            first_node.response_message,
            None,
            CODE_PROCESS_SCENARIO,
        )
        self.db.commit()
        self.db.refresh(chatbot)

        await self.events.publish(
            "ChatbotConsultationStarted",
            {
                "consultationId": consultation.consultation_id,
                "chatbotConsultationId": chatbot.chatbot_consultation_id,
                "customerNo": customer_no,
            },
        )
        return ChatbotStartResponse(
            consultation_id=consultation.consultation_id,
            chatbot_consultation_id=chatbot.chatbot_consultation_id,
            node_id=first_node.node_id,
            message=first_node.response_message,
            buttons=self._button_responses(first_node.node_id),
        )

    async def handle_message(
        self,
        chatbot_consultation_id: int,
        message: str,
        button_value: str | None,
    ) -> ChatbotMessageResponse:
        chatbot = self.db.get(ChatbotConsultation, chatbot_consultation_id)
        if not chatbot:
            raise ValueError("챗봇 상담을 찾을 수 없습니다.")

        current_node_id = self._latest_node_id(chatbot.chatbot_consultation_id)
        user_msg = message or button_value or ""
        self._record_message(
            chatbot,
            None,
            CODE_SENDER_USER,
            user_msg,
            button_value,
            None,
        )
        await self.events.publish_chatbot_message({
            "chatbot_consultation_id": chatbot.chatbot_consultation_id,
            "sequence_no": chatbot.total_turn_count + 1,
            "message_content": user_msg,
            "button_value": button_value,
            "sender_type_code_id": CODE_SENDER_USER,
        })
        next_node = self._resolve_next_node(chatbot.scenario_id, current_node_id, button_value)

        process_method = "SCENARIO"
        agent_transfer_required = False
        response_feature_code: str | None = None
        response_feature_data: list[dict] = []
        if next_node:
            response_message = next_node.response_message
            node_id = next_node.node_id
            process_code = CODE_PROCESS_SCENARIO

            # ── 상담사 연결 버튼: LLM 호출 없이 바로 이관 ──────────────────────
            # (버그 수정: 이전 코드는 LLM 호출과 상담사 이관이 동시에 발생)
            if self._is_agent_node(next_node):
                process_method = "STAFF_REQUEST"
                process_code = CODE_PROCESS_LLM
                response_message = "상담사 연결을 요청했습니다. 잠시만 기다려 주세요."
                agent_transfer_required = True
                chatbot.agent_connected_yn = "Y"
                self._open_chat_consultation(chatbot)
        else:
            # 버튼 매핑 없음 → intent 분류 후 feature 실행 시도
            # 프론트엔드가 붙이는 [직전 추천 상품: ...] 컨텍스트 annotation을 제거하고 분류
            classify_text = (message or "").split("\n[직전 추천 상품:")[0].strip()

            from app.models import ChatbotGoalSession as _ChatbotGoalSession
            import re as _re
            import os as _os
            import logging as _log
            _savings_log = _log.getLogger("savings_goal.routing")
            if not _savings_log.handlers:
                _h = _log.StreamHandler()
                _h.setFormatter(_log.Formatter("[%(name)s] %(message)s"))
                _savings_log.addHandler(_h)
            _savings_log.setLevel(_log.DEBUG)
            _savings_log.propagate = False

            # ── 금융 조회 하드 룰 키워드 (최우선 적용) ─────────────────────────
            # 이 키워드가 포함된 메시지는 goal session/flow를 완전히 우회하고 classifier로만 처리
            # "이번 달" 단독 제거: 소비분석 질문("이번 달 편의점...")을 금융조회로 오분류하던 문제 수정
            _FINANCIAL_QUERY_KEYWORDS = [
                "잔액", "계좌", "만기", "상품", "내역", "조회", "이자",
                "얼마", "금리", "금액", "입금", "출금", "거래", "적금",
                "예금", "통장", "이번달", "이체",
                "이자율", "연이율", "우대금리", "사용 내역", "사용내역",
                "입출금",
                "계약", "계약 현황", "가입 상품",
                "내 돈", "돈 어디", "묶여", "잠겨", "보유 자금",
            ]

            # 소비분석 키워드: financial_query보다 우선 적용
            _SPENDING_KEYWORDS = [
                "편의점", "카페", "스타벅스", "배달", "배달앱",
                "무신사", "쇼핑", "콘서트", "티켓",
                "썼는데", "썼어", "쓴 것", "쓴거", "사용했어", "결제했어",
                "샀는데", "샀어", "사버렸", "쓰고",
                "이상한 거 있어", "이상한거 있어", "좀 많아", "좀 많긴",
                "소비", "지출", "이번 달 소비", "이번달 소비", "이번 주 소비",
                "패턴",
            ]

            def _is_financial_query(text: str) -> bool:
                return any(kw in text for kw in _FINANCIAL_QUERY_KEYWORDS)

            def _is_spending_query(text: str) -> bool:
                return any(kw in text for kw in _SPENDING_KEYWORDS)

            def _is_savings_goal(text: str) -> bool:
                """명시적 저축/목표 의도가 있을 때만 True. 금액·기간만으로는 판정하지 않음."""
                t = text.replace(" ", "")
                # 금융 조회 키워드가 있으면 goal이 아님
                if _is_financial_query(t):
                    return False
                has_explicit_goal = bool(_re.search(r"모으|모아|목표|저축|저금|모을|쌓|마련|목돈.*만들|돈.*모", t))
                has_amount = bool(_re.search(r"\d+만|\d+억|\d+천만|[일이삼사오육칠팔구]천만|천만|[일이삼사오육칠팔구백]만", t))
                return has_explicit_goal and has_amount

            cid = chatbot.chatbot_consultation_id
            existing_session_obj = self.db.get(_ChatbotGoalSession, cid) if cid else None
            current_stage = existing_session_obj.stage if existing_session_obj else None

            # 금융 조회 하드 룰: goal session 중이어도 조회 요청은 override (세션은 유지)
            is_financial = _is_financial_query(classify_text)

            # ── 금융 도메인 best-effort 라우팅 맵 ────────────────────────────────
            # classifier가 None을 반환하더라도 금융 키워드 기반으로 intent 추론
            _FINANCIAL_BEST_EFFORT: list[tuple[list[str], str]] = [
                (["만기", "만기일", "만기 예정"],                          "MATURITY_SCHEDULE"),
                (["이자", "이자율", "이자 내역"],                          "INTEREST_HISTORY"),
                (["묶여", "잠겨", "내 돈", "보유 자금", "계약", "가입한"], "CONTRACT_STATUS"),
                (["계좌", "통장", "잔액"],                                 "MY_ACCOUNTS"),
                (["금리", "금리가", "이자율"],                             "RATE_GUIDE"),
                (["거래 내역", "사용 내역", "입출금", "지출"],             "SPENDING_PATTERN"),
                (["상품", "예금", "적금"],                                 "PRODUCT_GUIDE"),
            ]

            def _best_effort_financial_intent(text: str) -> str | None:
                for keywords, intent in _FINANCIAL_BEST_EFFORT:
                    if any(kw in text for kw in keywords):
                        return intent
                return None

            is_spending = _is_spending_query(classify_text)

            if is_spending:
                # 소비분석 키워드 우선 — financial_query보다 먼저 검사
                intent_name = "SPENDING_PATTERN"
                forced_by_session = False
                _savings_log.info("[router] spending keyword matched → SPENDING_PATTERN (override financial_query)")
            elif is_financial:
                # classifier로만 분류 — goal session은 건드리지 않고 이번 응답만 조회로 처리
                intent_name = self._classifier.classify(classify_text)
                _savings_log.info("[classifier] intent=%r (financial path)", intent_name)
                # classifier 미매칭 시 best-effort로 intent 추론 (금융 도메인 clarification 금지)
                if intent_name is None:
                    intent_name = _best_effort_financial_intent(classify_text)
                    _savings_log.info("[classifier] best_effort=%r", intent_name)
                forced_by_session = False
            elif existing_session_obj is not None:
                # goal session 진행 중 + 금융 조회 아님 → session 유지
                intent_name = "SAVINGS_GOAL"
                forced_by_session = True
            elif _is_savings_goal(classify_text):
                intent_name = "SAVINGS_GOAL"
                forced_by_session = False
            else:
                intent_name = self._classifier.classify(classify_text)
                forced_by_session = False

            _savings_log.info(
                "[SAVINGS_GOAL] incoming_message=%r current_session_stage=%s "
                "forced_savings_goal_route=%s is_financial_query=%s is_spending_query=%s",
                message, current_stage, forced_by_session, is_financial, is_spending,
            )
            _savings_log.info("[router] selected intent=%r", intent_name)

            intent_record = self._get_intent(chatbot.scenario_id, intent_name) if intent_name else None
            if intent_name:
                customer_no = self._get_customer_no(chatbot)

                # ── Goal Agent 연결 (GOAL_AGENT_ENABLED=true 시 활성화) ──────────
                # 세션이 이미 존재하면 savings_goal.py가 멀티턴 상태를 관리하므로 항상 savings_goal.py 사용
                # 세션이 없는 첫 턴에만 Goal Agent 호출
                use_goal_agent = (
                    intent_name == "SAVINGS_GOAL"
                    and _os.getenv("GOAL_AGENT_ENABLED") == "true"
                    and not forced_by_session
                )
                use_maturity_agent = (
                    intent_name in ("MATURITY_MANAGEMENT", "REINVESTMENT_RECOMMEND")
                    and _os.getenv("MATURITY_AGENT_ENABLED") == "true"
                )
                use_spending_agent = (
                    intent_name == "SPENDING_PATTERN"
                    and _os.getenv("SPENDING_AGENT_ENABLED") == "true"
                )
                _savings_log.info(
                    "[router] use_spending_agent=%s SPENDING_AGENT_ENABLED=%r intent=%r",
                    use_spending_agent, _os.getenv("SPENDING_AGENT_ENABLED"), intent_name,
                )
                if use_goal_agent:
                    feat_result = self._call_goal_agent(
                        message=message,
                        customer_no=customer_no,
                        chatbot_consultation_id=cid,
                    )
                elif use_maturity_agent:
                    feat_result = self._call_maturity_agent(
                        message=message,
                        customer_no=customer_no,
                        chatbot_consultation_id=cid,
                    )
                elif use_spending_agent:
                    _savings_log.info("[spending-agent] entered → _call_spending_agent customer_no=%r", customer_no)
                    feat_result = self._call_spending_agent(
                        message=message,
                        customer_no=customer_no,
                        chatbot_consultation_id=cid,
                    )
                else:
                    feat_result = self._run_feature_for_intent_full(
                        intent_name,
                        message,
                        customer_no=customer_no,
                        chatbot_consultation_id=cid,
                    )

                if feat_result.message and intent_name in ("CASH_FLOW_RECOMMEND", "PRODUCT_COMPARE", "SAVINGS_GOAL", "MATURITY_MANAGEMENT", "MATURITY_SCHEDULE", "REINVESTMENT_RECOMMEND", "SPENDING_PATTERN"):
                    response_message = feat_result.message
                    if feat_result.status == "OK" and feat_result.data and intent_name in ("SAVINGS_GOAL", "PRODUCT_COMPARE"):
                        response_feature_code = intent_name
                        response_feature_data = [d if isinstance(d, dict) else d.__dict__ for d in feat_result.data]
                    # PRODUCT_COMPARE이면서 개인 추천 의도도 포함된 경우 → 추천도 함께 제공
                    if intent_name == "PRODUCT_COMPARE" and customer_no and self._has_personal_recommend_intent(classify_text):
                        try:
                            rec_result = self._run_feature_for_intent_full(
                                "CASH_FLOW_RECOMMEND",
                                message,
                                customer_no=customer_no,
                                chatbot_consultation_id=chatbot.chatbot_consultation_id,
                            )
                            if rec_result.message:
                                response_message = f"{response_message}\n\n---\n\n{rec_result.message}"
                        except Exception:
                            pass
                else:
                    # data가 있으면 포매터로 출력, 없으면 feat_result의 도메인 empty 메시지 사용
                    if feat_result.data:
                        response_message = self._formatter.format(intent_name, feat_result.data)
                    else:
                        response_message = feat_result.message or "조회된 정보가 없습니다."
                process_method = f"FEATURE_{intent_name}"
                process_code = CODE_PROCESS_SCENARIO
                node_id = current_node_id or 0
                agent_transfer_required = False
                if intent_record:
                    chatbot.intent_id = intent_record.intent_id
            else:
                if is_financial:
                    # 금융 도메인은 clarification 금지 — best-effort로 MY_ACCOUNTS fallback
                    # (이 분기에 도달한다는 것은 best-effort도 None을 반환했다는 의미이므로
                    # 가장 일반적인 조회인 MY_ACCOUNTS로 처리)
                    fallback_intent = "MY_ACCOUNTS"
                    customer_no = self._get_customer_no(chatbot)
                    feat_result = self._run_feature_for_intent_full(
                        fallback_intent,
                        message,
                        customer_no=customer_no,
                        chatbot_consultation_id=cid,
                    )
                    if feat_result.data:
                        response_message = self._formatter.format(fallback_intent, feat_result.data)
                    else:
                        response_message = feat_result.message or "조회된 정보가 없습니다."
                    process_method = f"FEATURE_{fallback_intent}_FALLBACK"
                    process_code = CODE_PROCESS_SCENARIO
                    node_id = current_node_id or 0
                    agent_transfer_required = False
                else:
                    # RAG 자유질문 처리: intent 미매칭 시 상담사 연결 전에 RAG 검색 시도
                    rag_answer = self._try_rag_answer(classify_text)
                    if rag_answer:
                        response_message = rag_answer
                        process_method = "FEATURE_FREE_QUESTION_RAG"
                        process_code = CODE_PROCESS_LLM
                        node_id = current_node_id or 0
                        agent_transfer_required = False
                    else:
                        agent_intent = self._get_intent(chatbot.scenario_id, "STAFF_REQUEST")
                        process_method = "STAFF_REQUEST"
                        process_code = CODE_PROCESS_LLM
                        response_message = "상담사 연결을 요청했습니다. 잠시만 기다려 주세요."
                        agent_transfer_required = True
                        node_id = current_node_id or 0
                        chatbot.agent_connected_yn = "Y"
                        if agent_intent:
                            chatbot.intent_id = agent_intent.intent_id
                        self._open_chat_consultation(chatbot)

        chatbot.total_turn_count += 1
        self._record_message(chatbot, next_node, CODE_SENDER_BOT, response_message, None, process_code)
        self.db.commit()

        await self.events.publish(
            "ChatbotMessageHandled",
            {
                "chatbotConsultationId": chatbot.chatbot_consultation_id,
                "message": message,
                "processMethod": process_method,
                "agentTransferRequired": agent_transfer_required,
            },
        )
        if agent_transfer_required:
            await self.events.publish(
                "ChatbotAgentTransferRequested",
                {
                    "chatbotConsultationId": chatbot.chatbot_consultation_id,
                    "consultationId": chatbot.consultation_id,
                },
            )

        return ChatbotMessageResponse(
            consultation_id=chatbot.consultation_id,
            chatbot_consultation_id=chatbot.chatbot_consultation_id,
            node_id=node_id,
            message=response_message,
            buttons=self._button_responses(node_id) if node_id else [],
            process_method=process_method,
            agent_transfer_required=agent_transfer_required,
            feature_code=response_feature_code,
            feature_data=response_feature_data,
        )

    def categories(self) -> list[ChatbotCategoryResponse]:
        names = {
            "PRODUCT_ADVICE": ("금융상품 상담", "금융상품 관련 질문 전체"),
            "USER_FINANCE": ("사용자 금융정보 조회", "사용자 본인 금융정보 조회"),
            "STAFF_SUPPORT": ("직원 업무 지원", "직원용 내부 업무 지원"),
        }
        grouped: dict[str, list[str]] = {code: [] for code in names}
        for feature in self.features():
            grouped.setdefault(feature.category_code, []).append(feature.code)
        return [
            ChatbotCategoryResponse(
                code=code,
                name=name,
                description=description,
                features=grouped.get(code, []),
            )
            for code, (name, description) in names.items()
        ]

    def features(self) -> list[ChatbotFeatureResponse]:
        return [
            ChatbotFeatureResponse(
                code="PRODUCT_GUIDE",
                category_code="PRODUCT_ADVICE",
                name="예금/적금/청약 상품 안내",
                summary="수신 상품 목록과 핵심 조건을 안내합니다.",
                sample_questions=["예금 상품 알려줘", "청약 상품 가입 조건 알려줘"],
                api_status="MOCK_READY",
            ),
            ChatbotFeatureResponse(
                code="RATE_GUIDE",
                category_code="PRODUCT_ADVICE",
                name="금리/우대금리 설명",
                summary="기본금리, 우대금리, 최종 적용금리 설명 흐름을 제공합니다.",
                sample_questions=["우대금리 조건 알려줘", "적금 금리 설명해줘"],
                api_status="MOCK_READY",
            ),
            ChatbotFeatureResponse(
                code="JOIN_CONDITION",
                category_code="PRODUCT_ADVICE",
                name="가입 조건 안내",
                summary="대상 고객, 가입 채널, 기간, 금액 조건을 안내합니다.",
                sample_questions=["이 상품 가입할 수 있어?", "모바일로 가입 가능해?"],
                api_status="MOCK_READY",
            ),
            ChatbotFeatureResponse(
                code="PRODUCT_COMPARE",
                category_code="PRODUCT_ADVICE",
                name="상품 비교",
                summary="상품별 금리, 기간, 한도, 우대 조건 비교 응답을 준비합니다.",
                sample_questions=["정기예금과 적금 비교해줘", "청약이랑 예금 차이 알려줘"],
                api_status="MOCK_READY",
            ),
            ChatbotFeatureResponse(
                code="TERMS_RAG",
                category_code="PRODUCT_ADVICE",
                name="상품 설명서/약관 기반 RAG 응답",
                summary="약관 및 상품 설명서 검색 기반 답변 연결 지점입니다.",
                sample_questions=["중도해지 약관 알려줘", "상품설명서에서 수수료 찾아줘"],
                api_status="RAG_PENDING",
            ),
            ChatbotFeatureResponse(
                code="FAQ",
                category_code="PRODUCT_ADVICE",
                name="FAQ 응답",
                summary="반복 문의에 대한 고정 답변을 제공합니다.",
                sample_questions=["자주 묻는 질문 보여줘", "예금 FAQ 알려줘"],
                api_status="MOCK_READY",
            ),
            ChatbotFeatureResponse(
                code="MY_ACCOUNTS",
                category_code="USER_FINANCE",
                name="내 계좌 조회",
                summary="본인 인증 후 계좌 목록과 잔액 조회로 연결합니다.",
                sample_questions=["내 계좌 보여줘", "잔액 조회해줘"],
                api_status="AUTH_REQUIRED",
            ),
            ChatbotFeatureResponse(
                code="MY_PRODUCTS",
                category_code="USER_FINANCE",
                name="가입 상품 조회",
                summary="고객이 가입한 예금, 적금, 청약 상품을 조회합니다.",
                sample_questions=["내 가입 상품 알려줘", "내 적금 상품 보여줘"],
                api_status="AUTH_REQUIRED",
            ),
            ChatbotFeatureResponse(
                code="CONTRACT_STATUS",
                category_code="USER_FINANCE",
                name="계약 상태 조회",
                summary="계약 상태, 시작일, 만기일, 해지 가능 여부를 조회합니다.",
                sample_questions=["계약 상태 알려줘", "내 예금 계약 살아있어?"],
                api_status="AUTH_REQUIRED",
            ),
            ChatbotFeatureResponse(
                code="MATURITY_SCHEDULE",
                category_code="USER_FINANCE",
                name="만기 예정 조회",
                summary="만기 예정 상품과 예상 만기일을 조회합니다.",
                sample_questions=["곧 만기되는 상품 있어?", "만기일 알려줘"],
                api_status="AUTH_REQUIRED",
            ),
            ChatbotFeatureResponse(
                code="INTEREST_HISTORY",
                category_code="USER_FINANCE",
                name="이자 내역 조회",
                summary="이자 지급 및 예상 이자 내역 조회로 연결합니다.",
                sample_questions=["이자 내역 보여줘", "이번 달 이자 얼마야?"],
                api_status="AUTH_REQUIRED",
            ),
            ChatbotFeatureResponse(
                code="MY_CASH_FLOW",
                category_code="USER_FINANCE",
                name="내 현금 흐름 조회",
                summary="고객 본인의 입출금 거래 내역을 조회합니다.",
                sample_questions=["내 거래 내역 보여줘", "입출금 내역 알려줘", "현금 흐름 조회해줘"],
                api_status="AUTH_REQUIRED",
            ),
            ChatbotFeatureResponse(
                code="MY_TRANSFERS",
                category_code="USER_FINANCE",
                name="최근 이체 내역",
                summary="고객 본인의 최근 이체 거래 내역을 조회합니다.",
                sample_questions=["이체 내역 보여줘", "최근 이체 확인해줘"],
                api_status="AUTH_REQUIRED",
            ),
            ChatbotFeatureResponse(
                code="CASH_FLOW_RECOMMEND",
                category_code="USER_FINANCE",
                name="현금흐름 분석 기반 상품 추천",
                summary="고객의 입출금 패턴을 AI가 분석해 최적의 금융 상품을 추천합니다.",
                sample_questions=[
                    "내 거래 패턴으로 상품 추천해줘",
                    "나한테 맞는 상품 뭐야?",
                    "내 현금 흐름 분석해서 추천해줘",
                    "내 소비 패턴에 맞는 적금 있어?",
                ],
                api_status="LLM_REQUIRED",
            ),
            ChatbotFeatureResponse(
                code="STAFF_CUSTOMER",
                category_code="STAFF_SUPPORT",
                name="고객 정보 조회",
                summary="직원 권한 확인 후 고객 기본 정보를 조회합니다.",
                sample_questions=["고객 연락처 확인"],
                api_status="STAFF_AUTH_REQUIRED",
            ),
            ChatbotFeatureResponse(
                code="STAFF_CONTRACT",
                category_code="STAFF_SUPPORT",
                name="고객 계약 조회",
                summary="직원용 고객 계약 목록 및 계약 상세 조회로 연결합니다.",
                sample_questions=["고객 계약 보여줘", "계약 상태 확인"],
                api_status="STAFF_AUTH_REQUIRED",
            ),
            ChatbotFeatureResponse(
                code="STAFF_ACCOUNT",
                category_code="STAFF_SUPPORT",
                name="고객 계좌 조회",
                summary="직원용 고객 계좌 목록과 상태 조회로 연결합니다.",
                sample_questions=["계좌 상태 확인"],
                api_status="STAFF_AUTH_REQUIRED",
            ),
            ChatbotFeatureResponse(
                code="STAFF_TRANSFER_FLOW",
                category_code="STAFF_SUPPORT",
                name="고객 이체 흐름 조회",
                summary="거래 원장 기반으로 이체 유형, 상대방 정보, 진행 상태를 추적합니다.",
                sample_questions=["이체 흐름 추적", "거래 진행 상태 확인"],
                api_status="STAFF_AUTH_REQUIRED",
            ),
            ChatbotFeatureResponse(
                code="STAFF_CONSULTATION_HISTORY",
                category_code="STAFF_SUPPORT",
                name="상담 이력 조회",
                summary="고객의 과거 상담 이력과 챗봇 전환 이력을 조회합니다.",
                sample_questions=["상담 이력 보여줘", "이전 문의 확인"],
                api_status="STAFF_AUTH_REQUIRED",
            ),
            ChatbotFeatureResponse(
                code="STAFF_CASH_FLOW",
                category_code="STAFF_SUPPORT",
                name="고객 현금 흐름 조회",
                summary="직원 권한으로 고객의 입출금 거래 내역을 조회합니다.",
                sample_questions=["고객 거래 내역 보여줘", "현금 흐름 확인"],
                api_status="STAFF_AUTH_REQUIRED",
            ),
        ]

    def feature_detail(self, feature_code: str) -> ChatbotFeatureResponse | None:
        return next((feature for feature in self.features() if feature.code == feature_code), None)

    def execute_feature(
        self,
        feature_code: str,
        request: ChatbotFeatureExecuteRequest,
    ) -> ChatbotFeatureExecuteResponse:
        handlers: dict[str, Callable[[ChatbotFeatureExecuteRequest], ChatbotFeatureExecuteResponse]] = {
            "PRODUCT_GUIDE": self._execute_product_guide,
            "RATE_GUIDE": self._execute_rate_guide,
            "JOIN_CONDITION": self._execute_join_condition,
            "PRODUCT_COMPARE": self._execute_product_compare,
            "TERMS_RAG": self._execute_terms_search,
            "FAQ": self._execute_faq,
            "MY_ACCOUNTS": self._execute_my_accounts,
            "MY_PRODUCTS": lambda req: self._execute_customer_contracts(
                req, "MY_PRODUCTS", "가입 상품 조회를 완료했습니다.", "조회된 가입 상품이 없습니다."
            ),
            "CONTRACT_STATUS": lambda req: self._execute_customer_contracts(
                req, "CONTRACT_STATUS", "계약 상태 조회를 완료했습니다.", "조회된 계약 상태가 없습니다."
            ),
            "MATURITY_SCHEDULE": self._execute_maturity_schedule,
            "MATURITY_MANAGEMENT": self._execute_maturity_management,
            "REINVESTMENT_RECOMMEND": self._execute_reinvestment_recommend,
            "INTEREST_HISTORY": self._execute_interest_history,
            "MY_CASH_FLOW": self._execute_my_cash_flow,
            "MY_TRANSFERS": self._execute_my_transfers,
            "CASH_FLOW_RECOMMEND": lambda req: UserFinanceFeatureExecutor(self.db).execute_cash_flow_recommend(req),
            "STAFF_CASH_FLOW": self._execute_staff_cash_flow,
            "STAFF_CUSTOMER": self._execute_staff_customer,
            "STAFF_CONTRACT": lambda req: self._execute_customer_contracts(
                req,
                "STAFF_CONTRACT",
                "직원용 고객 계약 조회를 완료했습니다.",
                "조회된 고객 계약이 없습니다.",
                requires_staff_auth=True,
            ),
            "STAFF_ACCOUNT": self._execute_staff_account,
            "STAFF_TRANSFER_FLOW": self._execute_staff_transfer_flow,
            "STAFF_CONSULTATION_HISTORY": self._execute_staff_consultation_history,
            "PRODUCT_SEARCH": self._execute_product_search,
            "SAVINGS_GOAL": self._execute_savings_goal,
            "SPENDING_PATTERN": self._execute_spending_pattern,
        }
        handler = handlers.get(feature_code)
        if not handler:
            return ChatbotFeatureExecuteResponse(
                feature_code=feature_code,
                status="NOT_FOUND",
                message="지원하지 않는 챗봇 기능입니다.",
            )
        import logging as _log
        _log.getLogger("chatbot.feature").info(
            "[FEATURE] code=%s customer_no=%s product_type=%s period=%s amount=%s purpose=%s",
            feature_code, request.customer_no, request.product_type,
            request.period, request.amount, request.purpose,
        )
        result = handler(request)
        top3 = [r for r in (result.data or []) if r.get("row_type") == "recommended_product"][:3]
        _log.getLogger("chatbot.feature").info(
            "[FEATURE RESULT] code=%s top3=%s",
            feature_code,
            [(r.get("rank"), r.get("product_name"), r.get("product_type"), r.get("base_interest_rate")) for r in top3],
        )
        return result

    def _execute_product_guide(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        """상품 안내.

        우선순위:
          0. 명시적 상품 유형 요청(청약/예금/적금): 항상 DB 직접 조회로 타입 필터링
          1. 명시적 상품 유형 요청(청약/예금/적금): DB 직접 필터링
          2. Fallback: DB 전체 상품 목록
        """
        # ── 경로 0: 명시적 상품 유형 → DB 직접 필터링 ──
        query_text = (request.query or "").lower()
        _type_map = {
            "청약": "SUBSCRIPTION",
            "적금": "SAVINGS",
            "예금": "DEPOSIT",
        }
        requested_type = (request.product_type or "").upper()
        product_type_filter = requested_type if requested_type in {"DEPOSIT", "SAVINGS", "SUBSCRIPTION"} else next(
            (db_type for keyword, db_type in _type_map.items() if keyword in query_text),
            None,
        )

        if product_type_filter:
            rows = self._rows(
                """
                SELECT banking_product_id AS product_id,
                       deposit_product_name AS product_name,
                       deposit_product_type AS product_type,
                       description,
                       base_interest_rate,
                       min_join_amount,
                       max_join_amount,
                       min_period_month,
                       max_period_month,
                       deposit_product_status AS product_status
                  FROM deposit_banking_products
                 WHERE deposit_product_status = 'SELLING'
                   AND deposit_product_type = :ptype
                 ORDER BY base_interest_rate DESC NULLS LAST
                 LIMIT 20
                """,
                {"ptype": product_type_filter},
            )
            # 적금·예금은 가입기간 있는 상품만 (통장 제외)
            if product_type_filter in ("SAVINGS", "DEPOSIT"):
                rows = [r for r in rows if r.get("min_period_month") is not None]
            # 금리 범위 반영 (min_base ~ max_base+max_pref)
            pids = [int(r["product_id"]) for r in rows if r.get("product_id")]
            range_map = self._get_rate_ranges(pids)
            for r in rows:
                pid = int(r.get("product_id") or 0)
                rng = range_map.get(pid)
                if rng:
                    r["base_interest_rate"] = rng["max_base"] + rng["max_pref"]
                    r["min_rate"] = rng["min_base"]
            type_label = {"SUBSCRIPTION": "청약", "SAVINGS": "적금", "DEPOSIT": "예금"}[product_type_filter]
            return self._data_response(
                "PRODUCT_GUIDE", rows,
                f"{type_label} 상품 안내 조회를 완료했습니다.",
                f"판매 중인 {type_label} 상품이 없습니다.",
            )

        # ── DB 전체 목록 ──────────────────────────────────────────────────────
        rows = self._rows(
            """
            SELECT banking_product_id AS product_id,
                   deposit_product_name AS product_name,
                   deposit_product_type AS product_type,
                   description,
                   base_interest_rate,
                   min_join_amount,
                   max_join_amount,
                   min_period_month,
                   max_period_month,
                   deposit_product_status AS product_status
              FROM deposit_banking_products
             WHERE deposit_product_status = 'SELLING'
             ORDER BY banking_product_id
             LIMIT 20
            """
        )
        return self._data_response("PRODUCT_GUIDE", rows, "상품 안내 조회를 완료했습니다.", "등록된 수신 상품 데이터가 없습니다.")

    def _try_rag_answer(self, query: str) -> str | None:
        """RAG 엔진으로 자유질문에 답변을 시도한다. 엔진 미준비 또는 오류 시 None 반환."""
        if not self._rag_engine or not self._rag_engine.is_ready():
            return None
        try:
            results = self._rag_engine.search(query, top_k=3)
            if not results:
                return None
            return self._rag_generator.answer(query, results)
        except Exception:
            return None

    def _enrich_rag_results(
        self, rag_results: list[dict[str, Any]], cf: dict[str, Any] | None
    ) -> list[dict[str, Any]]:
        """RAG 검색 결과에 추천 이유와 match_score 를 추가한다."""
        enriched = []
        for rank, r in enumerate(rag_results, start=1):
            reasons: list[str] = []

            ptype       = r.get("deposit_product_type") or r.get("product_type", "")
            min_amt     = float(r.get("min_join_amount") or 0)
            rate        = float(r.get("base_interest_rate") or 0)
            rag_score   = float(r.get("_score", 0))

            if cf:
                total_balance   = cf.get("total_balance", 0)
                monthly_surplus = cf.get("monthly_surplus", 0)

                if ptype == "DEPOSIT" and total_balance >= min_amt:
                    reasons.append(f"보유 잔액({total_balance:,.0f}원)으로 가입 가능")
                if ptype == "SAVINGS" and monthly_surplus >= min_amt:
                    reasons.append(f"월 여유자금({monthly_surplus:,.0f}원)으로 납입 가능")
                if rate >= 3.5:
                    reasons.append("고금리 혜택")

            if not reasons:
                reasons.append(f"질문과 {rag_score:.0%} 유사도로 매칭된 상품")

            # match_score: RAG 유사도(0~1) × 100, 순위 패널티 적용
            match_score = max(10, round(rag_score * 100) - (rank - 1) * 3)

            enriched.append({
                **{k: v for k, v in r.items() if not k.startswith("_")},
                "recommend_reason": ", ".join(reasons),
                "match_score":      match_score,
            })
        return enriched

    def _analyze_customer_cash_flow(self, customer_no: str, months: int = 3) -> dict[str, Any] | None:
        """최근 N개월 외부 거래 기준 현금흐름 분석.

        집계 기준:
        - 최근 3개월 거래만 사용
        - 30일 이상 된 거래 이력이 있는 계좌만 분석 대상 (테스트 계좌 제외)
        - 고객 본인 계좌 간 내부 이체 제외 (counterparty_account_id 기반)
        - TRANSFER 타입은 내부 이체로 간주해 모두 제외
        - 외부 DEPOSIT → inflow, 외부 WITHDRAWAL/WITHDRAW → outflow
        - 잔액: ACTIVE 계좌 합산
        Returns {total_balance, monthly_surplus, monthly_tx_count, has_data, _debug}
        """
        accounts = self._rows(
            "SELECT account_id, balance, account_status FROM deposit_accounts WHERE customer_id = :cno",
            {"cno": customer_no},
        )
        if not accounts:
            return None

        all_ids   = {int(a["account_id"]) for a in accounts}
        active    = [a for a in accounts if str(a.get("account_status", "")).upper() == "ACTIVE"]
        # 전체 ACTIVE 잔액 (참고용)
        total_balance = sum(float(a.get("balance") or 0) for a in active)
        # 신규 상품 투자 가능 잔액: 출금가능 컬럼이 없으면 ACTIVE 잔액 전체를 사용한다.
        investable_balance = sum(
            float(a.get("balance") or 0)
            for a in active
            if a.get("is_withdrawable", True) is not False
        )

        if not all_ids:
            return None

        id_list = ",".join(str(i) for i in all_ids)

        # 본인 계좌번호 목록 (counterparty_account_no 대조용)
        own_acc_numbers = {
            str(r["account_number"])
            for r in self._rows(
                f"SELECT account_number FROM deposit_accounts WHERE account_id IN ({id_list})"
            )
        }

        # 날짜 컷오프를 Python에서 계산 → SQLite·PostgreSQL 모두 호환
        from datetime import datetime, timedelta, timezone
        cutoff = (datetime.now(timezone.utc) - timedelta(days=30 * months)).strftime("%Y-%m-%d")

        tx_rows = self._rows(
            f"""
            SELECT *
              FROM deposit_transactions
             WHERE account_id IN ({id_list})
               AND COALESCE(transaction_at, created_at) >= :cutoff
             """,
            {"cutoff": cutoff},
        )

        if not tx_rows:
            return {
                "total_balance":    total_balance,
                "monthly_surplus":  0.0,
                "monthly_tx_count": 0.0,
                "has_data":         False,
                "_debug":           f"분석계좌={len(all_ids)}개, 거래 없음",
            }

        inflow = outflow = 0.0
        external_tx_count = 0

        for r in tx_rows:
            ttype  = str(r.get("transaction_type") or "")
            status = str(r.get("status") or r.get("transaction_status") or "").upper()
            amount = float(r.get("amount") or 0)
            cp_id  = r.get("counterparty_account_id")

            if status and status not in ("SUCCESS", "COMPLETED"):
                continue
            # TRANSFER 타입 전체 제외
            if ttype == "TRANSFER":
                continue
            # counterparty 기반 내부 이체 제외
            cp_no = str(r.get("counterparty_account_no") or "")
            if cp_id is not None and int(cp_id) in all_ids:
                continue
            if cp_no and cp_no in own_acc_numbers:
                continue
            if ttype == "DEPOSIT":
                inflow += amount
                external_tx_count += 1
            elif ttype in ("WITHDRAWAL", "WITHDRAW"):
                outflow += amount
                external_tx_count += 1

        monthly_surplus  = (inflow - outflow) / months
        monthly_tx_count = external_tx_count / months

        debug = (
            f"분석계좌={len(all_ids)}개 | "
            f"기준=최근{months}개월 | "
            f"전체ACTIVE잔액={total_balance:,.0f}원 | "
            f"투자가능잔액(출금가능)={investable_balance:,.0f}원 | "
            f"외부입금={inflow:,.0f}원 | "
            f"외부출금={outflow:,.0f}원 | "
            f"월잉여금={monthly_surplus:,.0f}원"
        )

        return {
            "total_balance":       investable_balance,   # ROI 계산용: 출금 가능 잔액
            "total_balance_all":   total_balance,         # 참고용: 전체 ACTIVE 잔액
            "monthly_surplus":     monthly_surplus,
            "monthly_tx_count":    monthly_tx_count,
            "has_data":            external_tx_count > 0,
            "_debug":              debug,
        }

    def _execute_rate_guide(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        rows = self._rows(
            """
            SELECT r.rate_id,
                   r.banking_product_id AS product_id,
                   p.deposit_product_name AS product_name,
                   r.rate_type,
                   r.minimum_contract_period,
                   r.maximum_contract_period,
                   r.rate AS interest_rate,
                   r.condition_description
              FROM banking_deposit_product_interest_rates r
              JOIN deposit_banking_products p ON p.banking_product_id = r.banking_product_id
             WHERE p.deposit_product_status = 'SELLING'
               AND p.deposit_product_name NOT LIKE '%장병%'
               AND p.deposit_product_name NOT LIKE '%군인%'
             ORDER BY r.banking_product_id, r.rate_id
             LIMIT 200
            """
        )
        return self._data_response("RATE_GUIDE", rows, "금리/우대금리 조회를 완료했습니다.", "등록된 금리 데이터가 없습니다.")

    def _execute_join_condition(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        rows = self._rows(
            """
            SELECT banking_product_id AS product_id,
                   deposit_product_name AS product_name,
                   min_join_amount,
                   max_join_amount,
                   min_period_month,
                   max_period_month,
                   is_early_termination_allowed,
                   is_tax_benefit_available,
                   deposit_product_status AS product_status
              FROM deposit_banking_products
             ORDER BY banking_product_id
             LIMIT 20
            """
        )
        return self._data_response("JOIN_CONDITION", rows, "가입 조건 조회를 완료했습니다.", "등록된 가입 조건 데이터가 없습니다.")

    def _execute_product_compare(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        query = (request.query or "").strip()
        product_ids = list(request.compare_product_ids or ([request.product_id] if request.product_id else []))

        # ── 비교 분석 에이전트로 위임 (상품명 언급 여부와 무관하게 항상) ─────
        from app.features.product_compare import ProductCompareAgent
        agent = ProductCompareAgent(self.db)
        result = agent.execute(request)
        # 에이전트가 상품을 못 찾은 경우(NEED_INFO)면 개념 비교로 폴백
        if result.status != "NEED_INFO":
            return result

        # ── 개념 비교 질문: "예금 적금 차이" 등 → LLM 또는 고정 텍스트 설명 ──
        _CONCEPT_PAIRS = [
            ({"예금"}, {"적금"}),
            ({"예금"}, {"청약"}),
            ({"적금"}, {"청약"}),
        ]
        has_concept_compare = any(
            all(any(w in query for w in a) for a in pair)
            for pair in _CONCEPT_PAIRS
        )

        if has_concept_compare and not product_ids:
            # 고정 텍스트 응답
            _COMPARE_TEXT: dict[tuple[str, str], str] = {
                ("예금", "적금"): (
                    "📌 예금과 적금의 차이\n\n"
                    "🏦 예금 (정기예금)\n"
                    "• 목돈을 한 번에 맡기고 만기에 원금+이자를 받는 상품\n"
                    "• 이미 목돈이 있을 때 유리\n"
                    "• 가입 금액: 보통 100만원 이상 일시 납입\n"
                    "• 이자: 확정 금리로 만기 일시 지급\n\n"
                    "💰 적금 (정기적금/자유적금)\n"
                    "• 매달 일정 금액을 납입하며 목돈을 모아가는 상품\n"
                    "• 저축 습관을 들이며 목표 금액을 만들 때 유리\n"
                    "• 가입 금액: 소액(1만원~)부터 매달 납입 가능\n"
                    "• 이자: 납입 기간에 따라 복리로 적용\n\n"
                    "✅ 요약: 목돈이 있으면 예금, 매달 조금씩 모으고 싶으면 적금"
                ),
                ("예금", "청약"): (
                    "📌 예금과 청약의 차이\n\n"
                    "🏦 예금: 목돈을 맡기고 이자 수익을 얻는 상품\n"
                    "🏠 청약: 아파트 분양권 취득을 위한 저축 상품 (주택청약종합저축 등)\n\n"
                    "청약은 이자보다는 청약 가점/자격을 위한 목적이 큽니다."
                ),
                ("적금", "청약"): (
                    "📌 적금과 청약의 차이\n\n"
                    "💰 적금: 목돈 마련을 위해 매달 납입, 만기에 원금+이자 수령\n"
                    "🏠 청약: 주택 분양 신청 자격·가점을 위해 납입, 내 집 마련이 목적\n\n"
                    "순수 저축이 목적이면 적금, 주택 구매를 계획 중이라면 청약을 유지하세요."
                ),
            }
            for (a, b), text in _COMPARE_TEXT.items():
                if a in query and b in query:
                    return ChatbotFeatureExecuteResponse(
                        feature_code="PRODUCT_COMPARE",
                        status="OK",
                        message=text,
                        data=[],
                    )
            # 매칭 안 되면 첫 번째 텍스트 사용
            return ChatbotFeatureExecuteResponse(
                feature_code="PRODUCT_COMPARE",
                status="OK",
                message=list(_COMPARE_TEXT.values())[0],
                data=[],
            )

        # ── 특정 상품 ID 비교 ────────────────────────────────────────────────
        if product_ids:
            rows = self._rows(
                """
                SELECT banking_product_id AS product_id,
                       deposit_product_name AS product_name,
                       deposit_product_type AS product_type,
                       base_interest_rate,
                       min_join_amount,
                       max_join_amount,
                       min_period_month,
                       max_period_month
                  FROM deposit_banking_products
                 WHERE banking_product_id IN :product_ids
                 ORDER BY banking_product_id
                """,
                {"product_ids": tuple(product_ids)},
                expanding_params=("product_ids",),
            )
        else:
            rows = self._fallback_compare_recommendations(query)
        return ChatbotFeatureExecuteResponse(
            feature_code="PRODUCT_COMPARE",
            status="OK" if rows else "EMPTY",
            message=self._format_product_compare_rows(rows),
            data=rows,
        )

    def _execute_terms_search(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        query = (request.query or "").strip()

        # SQL LIKE 검색 (빈 쿼리 시 "%" → 전체 반환)
        like = f"%{query}%" if query else "%"
        rows = self._rows(
            """
            SELECT special_term_id,
                   special_term_name,
                   special_term_content,
                   special_term_summary,
                   is_required,
                   status
              FROM deposit_special_terms
             WHERE special_term_name LIKE :query
                OR special_term_content LIKE :query
                OR special_term_summary LIKE :query
             ORDER BY special_term_id
             LIMIT 10
            """,
            {"query": like},
        )
        return self._data_response("TERMS_RAG", rows, "약관 검색을 완료했습니다.", "검색 가능한 약관 데이터가 없습니다.")

    def _execute_faq(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        return ChatbotFeatureExecuteResponse(
            feature_code="FAQ",
            status="OK",
            message="수신 상품 FAQ 응답입니다.",
            data=[
                {"question": "예금과 적금의 차이는 무엇인가요?", "answer": "예금은 목돈을 맡기고, 적금은 정해진 주기로 납입하는 상품입니다."},
                {"question": "우대금리는 어떻게 적용되나요?", "answer": "상품별 우대 조건 충족 여부에 따라 기본금리에 추가됩니다."},
                {"question": "중도해지하면 어떻게 되나요?", "answer": "상품 약관의 중도해지이율이 적용될 수 있어 약관 확인이 필요합니다."},
            ],
        )

    def _execute_my_accounts(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        if not request.customer_no:
            return self._auth_required("MY_ACCOUNTS", "계좌 조회에는 고객번호와 본인 인증이 필요합니다.")
        rows = self._account_rows(request.customer_no)
        return self._data_response(
            "MY_ACCOUNTS", rows, "내 계좌 조회를 완료했습니다.", "조회된 계좌가 없습니다.", requires_auth=True
        )

    # ── 만기 조회 기간 파싱 ─────────────────────────────────────────────────
    @staticmethod
    def _parse_maturity_days(query: str) -> int | None:
        """자연어 쿼리에서 기간 필터(일 수)를 파싱한다.

        예) "23일" → 23, "1개월" → 30, "2달" → 60, "한달" → 30, "30일 이내" → 30
        반환값 None = 필터 없음(전체 조회).
        """
        import re as _re
        q = query.replace(" ", "")
        # 숫자 + 개월/달
        m = _re.search(r"(\d+)[개]?[월달]", q)
        if m:
            return int(m.group(1)) * 30
        # 숫자 + 일
        m = _re.search(r"(\d+)일", q)
        if m:
            return int(m.group(1))
        # 한글 수사
        _KOR = {"한": 1, "두": 2, "세": 3, "네": 4, "다섯": 5, "여섯": 6}
        for kor, n in _KOR.items():
            if f"{kor}달" in q or f"{kor}개월" in q:
                return n * 30
        # 키워드 기반
        if "한달" in q or "이번달" in q:
            return 30
        if "분기" in q:
            return 90
        if "반년" in q:
            return 180
        return None

    def _execute_maturity_schedule(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        from datetime import date as _date, datetime as _dt
        if not request.customer_no:
            return self._auth_required("MATURITY_SCHEDULE", "만기 예정 조회에는 고객번호와 본인 인증이 필요합니다.")
        customer_no = request.customer_no
        rows = self._contract_rows(customer_no)
        if not rows:
            return ChatbotFeatureExecuteResponse(
                feature_code="MATURITY_SCHEDULE", status="OK",
                message="조회된 만기 예정 계약이 없습니다.",
            )

        # 만기일 파싱 후 ASC 정렬
        today = _date.today()

        def _to_date(raw):
            if raw is None:
                return None
            if isinstance(raw, (_date, _dt)):
                return raw if isinstance(raw, _date) else raw.date()
            try:
                return _dt.strptime(str(raw)[:10], "%Y-%m-%d").date()
            except Exception:
                return None

        rows_dated = [(r, _to_date(r.get("maturity_at"))) for r in rows]
        rows_sorted = sorted(
            [(r, d) for r, d in rows_dated if d is not None],
            key=lambda x: x[1],
        )

        # 기간 필터 파싱
        max_days = self._parse_maturity_days(request.query or "")
        sort_only = "빠른" in (request.query or "") or "정렬" in (request.query or "") or "순서" in (request.query or "")

        if max_days is not None:
            filtered = [(r, d) for r, d in rows_sorted if (d - today).days <= max_days]
            if not filtered:
                all_rows = [r for r, _ in rows_sorted]
                return ChatbotFeatureExecuteResponse(
                    feature_code="MATURITY_SCHEDULE", status="OK",
                    message=(
                        f"{max_days}일 이내 만기 예정 계약이 없습니다.\n"
                        f"전체 만기 예정 상품을 보여드릴게요.\n\n"
                        + self._format_maturity_schedule([r for r, _ in rows_sorted])
                    ),
                )
            return ChatbotFeatureExecuteResponse(
                feature_code="MATURITY_SCHEDULE", status="OK",
                message=self._format_maturity_schedule([r for r, _ in filtered]),
            )

        return ChatbotFeatureExecuteResponse(
            feature_code="MATURITY_SCHEDULE", status="OK",
            message=self._format_maturity_schedule([r for r, _ in rows_sorted]),
        )

    def _format_maturity_schedule(self, rows: list) -> str:
        from datetime import date, datetime
        today = date.today()
        lines = ["[만기 예정 조회]"]
        for row in rows[:5]:
            name = row.get("product_name") or "상품"
            amount = float(row.get("join_amount") or 0)
            maturity_raw = row.get("maturity_at")
            if maturity_raw is None:
                continue
            if isinstance(maturity_raw, (date, datetime)):
                mat_date = maturity_raw if isinstance(maturity_raw, date) else maturity_raw.date()
            else:
                try:
                    mat_date = datetime.strptime(str(maturity_raw)[:10], "%Y-%m-%d").date()
                except Exception:
                    continue
            days = (mat_date - today).days
            if days < 0:
                timing = f"{mat_date} (이미 만기)"
            elif days == 0:
                timing = f"{mat_date} (오늘 만기!)"
            elif days <= 30:
                timing = f"{mat_date} ({days}일 후)"
            else:
                timing = f"{mat_date} (약 {days // 30}개월 후)"
            rate = row.get("contract_interest_rate") or row.get("base_interest_rate")
            rate_str = f" · 연 {rate}%" if rate else ""
            lines.append(f"- {name} {amount:,.0f}원{rate_str}\n  만기: {timing}")
        lines.append("\n재투자 추천이 필요하시면 '만기 상품 어떻게 해'라고 입력해 주세요.")
        return "\n".join(lines)

    def _execute_maturity_management(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        if not request.customer_no:
            return self._auth_required("MATURITY_MANAGEMENT", "만기 운용 전략 조회에는 고객번호와 본인 인증이 필요합니다.")
        customer_no = request.customer_no
        rows = self._contract_rows(customer_no)
        if not rows:
            return ChatbotFeatureExecuteResponse(
                feature_code="MATURITY_MANAGEMENT", status="OK",
                message="조회된 만기 예정 계약이 없습니다.",
            )
        return ChatbotFeatureExecuteResponse(
            feature_code="MATURITY_MANAGEMENT", status="OK",
            message=self._format_maturity_strategy(rows),
        )

    def _format_maturity_strategy(self, rows: list) -> str:
        from datetime import date, datetime
        today = date.today()
        lines = ["[만기 운용 전략 안내]"]
        lines.append("만기 후 선택할 수 있는 방법은 크게 세 가지입니다.\n")
        lines.append("① 재예치 — 목돈은 정기예금으로 재가입해 금리를 유지")
        lines.append("② 상품 전환 — 더 높은 금리나 조건이 좋은 다른 상품으로 이동")
        lines.append("③ 유동성 보유 — 당장 쓸 돈이 있거나 현금흐름이 불안정할 때 입출금 통장에 보관")
        if rows:
            lines.append("\n현재 만기 예정 상품:")
            for row in rows[:3]:
                name = row.get("product_name") or "상품"
                amount = float(row.get("join_amount") or 0)
                maturity_raw = row.get("maturity_at")
                if maturity_raw is None:
                    continue
                if isinstance(maturity_raw, (date, datetime)):
                    mat_date = maturity_raw if isinstance(maturity_raw, date) else maturity_raw.date()
                else:
                    try:
                        mat_date = datetime.strptime(str(maturity_raw)[:10], "%Y-%m-%d").date()
                    except Exception:
                        continue
                days = (mat_date - today).days
                timing = f"{days}일 후" if days <= 30 else f"약 {days // 30}개월 후"
                lines.append(f"  - {name} {amount:,.0f}원 ({timing} 만기)")
        lines.append("\n구체적인 상품 추천이 필요하시면 '재투자 추천해줘'라고 입력해 주세요.")
        return "\n".join(lines)

    def _execute_reinvestment_recommend(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        from app.features.maturity_agent import MaturityManagementAgent
        return MaturityManagementAgent(db=self.db).execute(request)

    def _execute_spending_pattern(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        from app.features.spending_pattern_agent import SpendingPatternAgent
        return SpendingPatternAgent(db=self.db).execute(request)

    def _execute_interest_history(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        if not request.customer_no:
            return self._auth_required("INTEREST_HISTORY", "이자 내역 조회에는 고객번호와 본인 인증이 필요합니다.")
        rows = self._rows(
            """
            SELECT h.interest_id,
                   h.contract_id,
                   h.account_id,
                   h.applied_interest_rate,
                   h.interest_amount,
                   h.interest_after_tax AS interest_after_tax_amount,
                   h.interest_paid_at AS paid_at
              FROM deposit_interest_history h
              JOIN deposit_accounts a ON a.account_id = h.account_id
             WHERE a.customer_id = :customer_no
             ORDER BY h.interest_id DESC
             LIMIT 20
            """,
            {"customer_no": request.customer_no},
        )
        return self._data_response(
            "INTEREST_HISTORY", rows, "이자 내역 조회를 완료했습니다.", "조회된 이자 내역이 없습니다.", requires_auth=True
        )

    def _execute_my_cash_flow(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        if not request.customer_no:
            return self._auth_required("MY_CASH_FLOW", "현금 흐름 조회에는 고객번호와 본인 인증이 필요합니다.")
        rows = self._rows(
            """
            SELECT t.transaction_id,
                   a.account_number,
                   t.transaction_type,
                   t.amount,
                   t.transaction_status,
                   t.created_at
              FROM deposit_transactions t
              JOIN deposit_accounts a ON a.account_id = t.account_id
             WHERE a.customer_id = :customer_no
             ORDER BY t.transaction_id DESC
             LIMIT 20
            """,
            {"customer_no": request.customer_no},
        )
        return self._data_response(
            "MY_CASH_FLOW", rows, "현금 흐름 조회를 완료했습니다.", "조회된 거래 내역이 없습니다.", requires_auth=True
        )

    def _execute_my_transfers(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        if not request.customer_no:
            return self._auth_required("MY_TRANSFERS", "이체 내역 조회에는 고객번호가 필요합니다.")
        rows = self._rows(
            """
            SELECT t.transaction_id,
                   a.account_number,
                   t.transaction_type,
                   t.amount,
                   t.transaction_status,
                   t.created_at
              FROM deposit_transactions t
              JOIN deposit_accounts a ON a.account_id = t.account_id
             WHERE a.customer_id = :customer_no
               AND t.transaction_type = 'TRANSFER'
             ORDER BY t.transaction_id DESC
             LIMIT 10
            """,
            {"customer_no": request.customer_no},
        )
        return self._data_response(
            "MY_TRANSFERS", rows, "최근 이체 내역입니다.", "조회된 이체 내역이 없습니다.", requires_auth=True
        )

    def execute_transfer(self, req: ChatbotTransferRequest) -> ChatbotTransferResponse:
        try:
            # 출금 계좌 검증
            src = self.db.execute(
                text("SELECT account_id, account_number, balance, is_withdrawable FROM deposit_accounts WHERE account_id = :aid AND customer_id = :cno"),
                {"aid": req.from_account_id, "cno": req.customer_no},
            ).mappings().first()
            if not src:
                return ChatbotTransferResponse(status="ERROR", message="출금 계좌를 찾을 수 없습니다.")
            if not src["is_withdrawable"]:
                return ChatbotTransferResponse(status="ERROR", message="출금이 불가능한 계좌입니다.")
            if src["balance"] < req.amount:
                return ChatbotTransferResponse(status="ERROR", message=f"잔액이 부족합니다. (현재 잔액: {int(src['balance']):,}원)")
            if req.amount <= 0:
                return ChatbotTransferResponse(status="ERROR", message="이체 금액은 0원보다 커야 합니다.")

            # 수취 계좌 조회
            dst = self.db.execute(
                text("SELECT account_id, account_number, customer_id FROM deposit_accounts WHERE account_number = :ano AND account_status = 'ACTIVE'"),
                {"ano": req.to_account_number},
            ).mappings().first()
            if not dst:
                return ChatbotTransferResponse(status="ERROR", message="수취 계좌를 찾을 수 없습니다.")
            if dst["account_id"] == req.from_account_id:
                return ChatbotTransferResponse(status="ERROR", message="출금 계좌와 수취 계좌가 동일합니다.")

            now = datetime.now(timezone.utc)
            tx_no = f"TXN{now.strftime('%Y%m%d%H%M%S')}{req.from_account_id}"

            # 출금 트랜잭션
            src_balance_after = int(src["balance"]) - req.amount
            result = self.db.execute(
                text("""
                    INSERT INTO deposit_transactions
                        (transaction_number, account_id, transaction_type, direction_type,
                         amount, balance_before, balance_after, available_balance_after,
                         fee_amount, currency, status, channel_type,
                         transaction_memo, transaction_summary, transaction_at,
                         counterparty_account_no, counterparty_account_id,
                         counterparty_customer_id, created_at, updated_at)
                    VALUES
                        (:tx_no, :account_id, 'TRANSFER', 'OUT',
                         :amount, :bal_before, :bal_after, :bal_after,
                         0, 'W', 'SUCCESS', 'CHATBOT',
                         :memo, :summary, :now,
                         :to_acc_no, :to_acc_id,
                         :to_cust_id, :now, :now)
                    RETURNING transaction_id
                """),
                {
                    "tx_no": tx_no + "_OUT",
                    "account_id": req.from_account_id,
                    "amount": req.amount,
                    "bal_before": int(src["balance"]),
                    "bal_after": src_balance_after,
                    "memo": req.memo,
                    "summary": f"{req.to_account_number}으로 이체",
                    "now": now,
                    "to_acc_no": dst["account_number"],
                    "to_acc_id": dst["account_id"],
                    "to_cust_id": dst["customer_id"],
                },
            )
            transaction_id = result.scalar()

            # 수취 트랜잭션
            dst_balance = self.db.execute(
                text("SELECT balance FROM deposit_accounts WHERE account_id = :aid"),
                {"aid": dst["account_id"]},
            ).scalar() or 0
            dst_balance_after = int(dst_balance) + req.amount
            self.db.execute(
                text("""
                    INSERT INTO deposit_transactions
                        (transaction_number, account_id, transaction_type, direction_type,
                         amount, balance_before, balance_after, available_balance_after,
                         fee_amount, currency, status, channel_type,
                         transaction_memo, transaction_summary, transaction_at,
                         counterparty_account_no, counterparty_account_id,
                         counterparty_customer_id, created_at, updated_at)
                    VALUES
                        (:tx_no, :account_id, 'TRANSFER', 'IN',
                         :amount, :bal_before, :bal_after, :bal_after,
                         0, 'W', 'SUCCESS', 'CHATBOT',
                         :memo, :summary, :now,
                         :from_acc_no, :from_acc_id,
                         :cno, :now, :now)
                """),
                {
                    "tx_no": tx_no + "_IN",
                    "account_id": dst["account_id"],
                    "amount": req.amount,
                    "bal_before": int(dst_balance),
                    "bal_after": dst_balance_after,
                    "memo": req.memo,
                    "summary": f"{src['account_number']}에서 이체",
                    "now": now,
                    "from_acc_no": src["account_number"],
                    "from_acc_id": req.from_account_id,
                    "cno": req.customer_no,
                },
            )

            # 잔액 업데이트
            self.db.execute(
                text("UPDATE deposit_accounts SET balance = :bal, updated_at = :now WHERE account_id = :aid"),
                {"bal": src_balance_after, "now": now, "aid": req.from_account_id},
            )
            self.db.execute(
                text("UPDATE deposit_accounts SET balance = :bal, updated_at = :now WHERE account_id = :aid"),
                {"bal": dst_balance_after, "now": now, "aid": dst["account_id"]},
            )
            self.db.commit()

            return ChatbotTransferResponse(
                status="OK",
                message=f"{req.amount:,}원이 {req.to_account_number}으로 이체되었습니다.",
                transaction_id=transaction_id,
                balance_after=src_balance_after,
            )
        except Exception as exc:
            self.db.rollback()
            logger.exception("이체 처리 오류: %s", exc)
            return ChatbotTransferResponse(status="ERROR", message="이체 처리 중 오류가 발생했습니다.")

    def _is_compare_query(self, query: str) -> bool:
        return any(
            word in query
            for word in ("비교", "차이", "뭐가 더", "어느 쪽", "어느쪽", "맞아", "적합")
        )

    def _format_product_compare_rows(self, rows: list[dict[str, Any]]) -> str:
        if not rows:
            return "비교할 상품 정보를 찾을 수 없습니다."

        type_labels = {
            "DEPOSIT": "예금",
            "SAVINGS": "적금",
            "SUBSCRIPTION": "청약",
        }

        def money(value: Any) -> str:
            if value in (None, ""):
                return "-"
            try:
                return f"{float(value):,.0f}"
            except (TypeError, ValueError):
                return str(value)

        lines = ["상품 비교 결과입니다.\n"]
        for idx, row in enumerate(rows[:5], start=1):
            name = row.get("product_name") or row.get("deposit_product_name") or "상품명 없음"
            ptype = row.get("product_type") or row.get("deposit_product_type") or "-"
            ptype_label = type_labels.get(str(ptype), str(ptype))
            rate = row.get("base_interest_rate") or "-"
            min_month = row.get("min_period_month") or "-"
            max_month = row.get("max_period_month") or "-"
            min_amount = row.get("min_join_amount")
            max_amount = row.get("max_join_amount")
            amount_text = ""
            if min_amount is not None or max_amount is not None:
                amount_text = f" / 가입금액 {money(min_amount)}~{money(max_amount)}원"
            lines.append(
                f"{idx}. {name}\n"
                f"   - 유형: {ptype_label}\n"
                f"   - 기본금리: {rate}%\n"
                f"   - 기간: {min_month}~{max_month}개월{amount_text}"
            )
        return "\n".join(lines)

    def _fallback_compare_recommendations(self, query: str = "") -> list[dict[str, Any]]:
        rows = self._rows(
            f"""
            SELECT banking_product_id AS product_id,
                   deposit_product_name AS product_name,
                   deposit_product_type AS product_type,
                   base_interest_rate,
                   min_join_amount,
                   max_join_amount,
                   min_period_month,
                   max_period_month,
                   description
              FROM deposit_banking_products
             WHERE deposit_product_status = 'SELLING'
               AND deposit_product_type IN ('DEPOSIT', 'SAVINGS', 'SUBSCRIPTION')
             ORDER BY
                   CASE deposit_product_type
                       WHEN 'SAVINGS' THEN 1
                       WHEN 'DEPOSIT' THEN 2
                       WHEN 'SUBSCRIPTION' THEN 3
                       ELSE 4
                   END,
                   base_interest_rate DESC NULLS LAST,
                   banking_product_id
             LIMIT 50
            """
        )
        include_limited = any(word in query for word in ("청년", "장병", "군인", "군무원"))
        if not include_limited:
            rows = [
                row for row in rows
                if not any(word in str(row.get("product_name", "")) for word in ("청년", "장병", "군인", "군무원", "Youth"))
            ]

        deposits = [row for row in rows if row.get("product_type") == "DEPOSIT"]
        savings = [row for row in rows if row.get("product_type") == "SAVINGS"]
        subscriptions = [row for row in rows if row.get("product_type") == "SUBSCRIPTION"]

        if "예금" in query and "적금" in query:
            return (savings[:3] + deposits[:2])[:5]
        if "예금" in query and "청약" in query:
            return (deposits[:3] + subscriptions[:2])[:5]
        if "적금" in query and "청약" in query:
            return (savings[:3] + subscriptions[:2])[:5]
        if "예금" in query:
            return deposits[:3]
        if "적금" in query:
            return savings[:3]
        if "청약" in query:
            return subscriptions[:3]
        return (savings[:2] + deposits[:2] + subscriptions[:1])[:5]

    def _compare_recommendation_message(self, query: str, rows: list[dict[str, Any]]) -> str:
        intro = (
            "개인 계좌 정보가 없어 정확한 맞춤 분석은 어렵지만, 일반적인 기준으로 비교해서 나열해드릴게요.\n\n"
            "- 매달 꾸준히 모으는 목적이면 적금이 더 잘 맞습니다.\n"
            "- 이미 목돈이 있다면 예금이 더 잘 맞습니다.\n"
            "- 주택 마련 준비가 목적이면 청약이 더 잘 맞습니다.\n\n"
            "아래는 비교해볼 만한 상품입니다.\n"
        )
        return f"{intro}\n{self._format_product_compare_rows(rows)}"

    def _execute_cash_flow_recommend(
        self, request: ChatbotFeatureExecuteRequest
    ) -> ChatbotFeatureExecuteResponse:
        """현금흐름 분석 → 규칙 기반 개인화 상품 추천.

        흐름:
          1. customer_no 인증 확인
          2. 현금흐름 분석 (잔액·월 잉여자금·거래 빈도)
          3. 판매 중인 수신 상품 조회
          4. 현금흐름·금리·가입금액 점수 기반 추천
        """
        if not request.customer_no:
            return self._auth_required(
                "CASH_FLOW_RECOMMEND",
                "현금흐름 분석 추천에는 고객번호와 본인 인증이 필요합니다.",
            )

        # ── 0. 고객 나이 조회 (customer-service 연동) ────────────────────────
        customer_age = self._get_customer_age(request.customer_no) if request.customer_no else None

        # ── 0b. '둘 중' 같은 지시어가 있으면 이전 대화에서 맥락 보완 ─────────
        resolved_query = self._resolve_ambiguous_query(
            request.query or "",
            request.chatbot_consultation_id,
        )

        # ── 1. 현금흐름 분석 ──────────────────────────────────────────────────
        cf = self._analyze_customer_cash_flow(request.customer_no)
        if cf is None:
            if self._is_compare_query(resolved_query):
                rows = self._fallback_compare_recommendations(resolved_query)
                return ChatbotFeatureExecuteResponse(
                    feature_code="CASH_FLOW_RECOMMEND",
                    status="OK",
                    message=self._compare_recommendation_message(resolved_query, rows),
                    data=rows,
                    requires_auth=True,
                )
            return ChatbotFeatureExecuteResponse(
                feature_code="CASH_FLOW_RECOMMEND",
                status="EMPTY",
                message="계좌 정보를 찾을 수 없습니다.",
                data=[],
                requires_auth=True,
            )

        # ── 2. 판매 중인 수신 상품 목록 (LLM 컨텍스트용) ──────────────────────
        products = self._rows(
            f"""
            SELECT p.banking_product_id AS product_id,
                   p.deposit_product_name,
                   p.deposit_product_type,
                   p.base_interest_rate,
                   p.min_join_amount,
                   p.max_join_amount,
                   p.min_period_month,
                   p.max_period_month,
                   p.is_early_termination_allowed,
                   p.is_tax_benefit_available
              FROM deposit_banking_products p
             WHERE p.deposit_product_status = 'SELLING'
               AND p.deposit_product_type != 'SUBSCRIPTION'
               AND p.deposit_product_name NOT LIKE '%장병%'
               AND p.deposit_product_name NOT LIKE '%군인%'
               AND p.deposit_product_name NOT LIKE '%군무원%'
               AND p.deposit_product_name NOT LIKE '%군무원%'
             ORDER BY p.base_interest_rate DESC NULLS LAST
             LIMIT 50
            """
        )
        # 나이 기반 필터 (customer-service 연동 성공 시 정확한 청년 필터 적용)
        # 연동 실패 시 키워드 필터로 fallback
        if customer_age is not None:
            products = self._filter_by_age(products, customer_age)
        else:
            products = [
                p for p in products
                if not any(kw in str(p.get("deposit_product_name", ""))
                           for kw in ("청년", "Youth", "Young"))
            ]

        # 우대금리 조건 보강 — DB 우선, 없으면 상품명 기반 fallback
        _PREF_COND_FALLBACK: list[tuple[str, str]] = [
            ("맑은하늘",   "맑은하늘 앱 설치 후 인증코드 등록"),
            ("직장인우대", "급여이체 실적 등록"),
            ("자유적금",   "자동이체 설정"),
            ("내맘대로",   "자동이체 설정"),
            ("달러",       "달러 환전 실적 보유"),
            ("청년도약",   "소득 요건 충족 확인"),
            ("수퍼정기",   "비대면 가입"),
            ("정기예금",   "비대면(인터넷·스타뱅킹) 가입"),
            ("꿈적금",     "만기 유지"),
            ("함께적금",   "2인 이상 공동 가입"),
        ]
        pids = [int(p.get("product_id") or p.get("banking_product_id") or 0) for p in products]
        pref_cond_map = self._get_pref_conditions(pids)
        for p in products:
            pid = int(p.get("product_id") or p.get("banking_product_id") or 0)
            info = pref_cond_map.get(pid, {})
            cond = info.get("condition", "") if isinstance(info, dict) else ""
            rate = info.get("rate", 0.0) if isinstance(info, dict) else 0.0
            if not cond:
                name = str(p.get("deposit_product_name") or p.get("product_name", ""))
                for keyword, fallback in _PREF_COND_FALLBACK:
                    if keyword in name:
                        cond = fallback
                        break
            if cond:
                p["pref_condition"] = cond
            if rate:
                p["pref_rate"] = rate

        # ── 3. 규칙 기반 추천 ────────────────────────────────────────────────
        # 은행 상품 추천은 외부 생성형 AI 판단에 맡기지 않는다.
        # 고객 현금흐름, 가입금액, 상품 유형, 금리, 우대조건을 점수화한 결과만 사용한다.
        ranked = self._rank_products(cf, products)
        recommendation = self._format_rank_based_recommend(cf, ranked)

        # ── 4. data 구성: 현금흐름 요약 + 추천 상품 카드 ─────────────────────
        # 화면 카드도 동일한 규칙 기반 순위를 사용한다.
        ranked_for_data = self._rank_products(cf, products)

        product_cards = [
            {
                "row_type":          "recommended_product",
                "rank":              i + 1,
                "product_id":        p.get("product_id") or p.get("banking_product_id"),
                "product_name":      p.get("deposit_product_name") or p.get("product_name", ""),
                "product_type":      p.get("deposit_product_type") or p.get("product_type", ""),
                "base_interest_rate": p.get("base_interest_rate"),
                "min_period_month":  p.get("min_period_month"),
                "max_period_month":  p.get("max_period_month"),
                "min_join_amount":   p.get("min_join_amount"),
                "max_join_amount":   p.get("max_join_amount"),
                "reason":            p.get("_reason", ""),
                "pref_condition":    p.get("pref_condition", ""),
                "pref_rate":         p.get("pref_rate", 0.0),
            }
            for i, p in enumerate(ranked_for_data[:3])
        ]

        data = [
            {
                "row_type":         "cash_flow_summary",
                "total_balance":    cf["total_balance"],
                "monthly_surplus":  cf["monthly_surplus"],
                "monthly_tx_count": cf["monthly_tx_count"],
                "has_data":         cf["has_data"],
                "product_count":    len(products),
                "cf_debug":         cf.get("_debug", ""),
            },
            *product_cards,
        ]

        if (
            not str(recommendation or "").strip()
            or "제공할 수 없습니다" in recommendation
            or "죄송" in recommendation
        ):
            recommendation = self._format_rank_based_recommend(cf, ranked_for_data)

        recommendation = self._append_preferential_rate_notice(recommendation, product_cards)

        return ChatbotFeatureExecuteResponse(
            feature_code="CASH_FLOW_RECOMMEND",
            status="OK",
            message=recommendation,
            data=data,
            requires_auth=True,
        )

    def _append_preferential_rate_notice(
        self,
        message: str,
        product_cards: list[dict[str, Any]],
    ) -> str:
        preferred = [
            card for card in product_cards
            if str(card.get("pref_condition") or "").strip()
        ]
        if not preferred:
            return message

        lines = [
            "",
            "[우대금리 가능 상품 안내]",
            "아래 상품은 조건을 충족하면 기본금리에 우대금리를 추가로 받을 수 있습니다.",
        ]
        for card in preferred:
            name = card.get("product_name") or "상품명 없음"
            condition = card.get("pref_condition")
            pref_rate = card.get("pref_rate") or 0.0
            rate_str = f" (+{pref_rate}%)" if pref_rate else ""
            lines.append(f"- {name}{rate_str}: {condition}")
        lines.append("우대금리는 실제 가입 시점의 조건 충족 여부에 따라 달라질 수 있습니다.")
        return f"{message.rstrip()}\n" + "\n".join(lines)

    def _format_rank_based_recommend(self, cf: dict[str, Any], ranked: list[dict[str, Any]]) -> str:
        """_rank_products 결과를 고객 응답 텍스트로 변환."""
        total_balance   = float(cf.get("total_balance", 0))
        monthly_surplus = float(cf.get("monthly_surplus", 0))
        is_spender      = monthly_surplus <= 0
        is_accumulate   = not is_spender and total_balance < monthly_surplus * 12
        is_wealthy      = not is_spender and total_balance >= monthly_surplus * 12

        if is_spender:
            persona_desc = f"월 잉여자금이 없는 상황(총잔액 {total_balance:,.0f}원)이므로"
        elif is_wealthy:
            persona_desc = f"총잔액 {total_balance:,.0f}원의 목돈 보유형이므로"
        else:
            persona_desc = f"월 잉여자금 {monthly_surplus:,.0f}원의 저축 성장형이므로"

        lines = [
            f"[현금흐름 분석 기반 맞춤 추천]\n",
            f"고객님은 {persona_desc} 아래 상품을 추천드립니다.\n",
        ]

        type_label = {"DEPOSIT": "예금", "SAVINGS": "적금", "SUBSCRIPTION": "청약"}
        for i, p in enumerate(ranked[:3], start=1):
            name  = p.get("deposit_product_name") or p.get("product_name", "")
            ptype = p.get("deposit_product_type") or p.get("product_type", "")
            rate  = p.get("base_interest_rate", "")
            reason = p.get("_reason", "")
            pref_cond = p.get("pref_condition", "")
            lines.append(
                f"{i}위. [{type_label.get(ptype, ptype)}] {name} (금리 {rate}%)"
            )
            if reason:
                lines.append(f"   → {reason}")
            if pref_cond:
                lines.append(f"   ※ 우대금리 받으려면: {pref_cond}")

        if not ranked:
            lines.append("현재 조건에 맞는 추천 상품이 없습니다. 상담사 연결을 이용해 주세요.")

        lines.append("\n더 자세한 상담은 '상담사 연결'을 이용해 주세요.")
        return "\n".join(lines)

    def _rank_products(self, cf: dict[str, Any], products: list[dict[str, Any]], input_period: int = 0) -> list[dict[str, Any]]:
        """100점 채점 모델: 재정적합도(40) + 수익성ROI(30) + 유동성(20) + 부가혜택(10)."""
        total_balance   = float(cf.get("total_balance", 0))
        monthly_surplus = float(cf.get("monthly_surplus", 0))
        monthly_tx      = float(cf.get("monthly_tx_count", 0))

        # 페르소나 분류
        is_spender    = monthly_surplus <= 0
        is_accumulate = not is_spender and total_balance < monthly_surplus * 12
        is_wealthy    = not is_spender and total_balance >= monthly_surplus * 12

        # 상품별 달성가능 금리 조회 (기본 + 우대, 기준기간=12개월)
        pids = [int(p.get("product_id") or p.get("banking_product_id") or 0) for p in products]
        rate_map = self._get_applicable_rates(pids, 12)

        candidates: list[dict[str, Any]] = []

        for p in products:
            ptype     = p.get("deposit_product_type") or p.get("product_type", "")
            min_join  = float(p.get("min_join_amount") or 0)
            min_month = int(p.get("min_period_month") or 1)
            max_month = int(p.get("max_period_month") or min_month)
            is_early  = bool(p.get("is_early_termination_allowed"))
            is_tax    = bool(p.get("is_tax_benefit_available"))
            pid       = int(p.get("product_id") or p.get("banking_product_id") or 0)

            # 달성 가능 금리 (기본 + 우대)
            r_info    = rate_map.get(pid, {})
            base_rate = r_info.get("base") or float(p.get("base_interest_rate") or 0)
            pref_rate = r_info.get("pref", 0.0)
            eff_rate  = round(base_rate + pref_rate, 2)

            # ── 필터링: 청약 제외 + 가입 자격 미달 제외 ─────────────────
            if ptype == "SUBSCRIPTION":
                continue
            if ptype == "DEPOSIT":
                if min_join > 0 and total_balance < min_join:
                    continue
                if is_spender and total_balance == 0:
                    continue
            elif ptype in ("SAVINGS", "SUBSCRIPTION"):
                if monthly_surplus <= 0:
                    continue
                # 안전 마진: 잉여금이 최소 납입액의 2배 미만이면 제외
                if min_join > 0 and monthly_surplus < min_join * 2:
                    continue

            # ── 1. 재정 적합도 (40점) ─────────────────────────────────────
            if ptype == "DEPOSIT":
                denom = max(min_join, 1)
                fit   = min(total_balance / denom, 5) / 5
            else:
                denom = max(min_join * 2, 1)
                fit   = min(monthly_surplus / denom, 5) / 5

            # 페르소나-상품 유형 일치 시 ×1.3 가산
            if ptype == "DEPOSIT" and (is_wealthy or is_spender):
                fit = min(fit * 1.3, 1.0)
            elif ptype in ("SAVINGS", "SUBSCRIPTION") and is_accumulate:
                fit = min(fit * 1.3, 1.0)

            # ── 2. 수익성 ROI ─────────────────────────────────────────────
            # targetPeriod = clamp(입력기간 또는 12, min, max)
            max_join    = float(p.get("max_join_amount") or 0)
            base_period = input_period if input_period > 0 else 12
            target_period = max(min_month, min(max_month, base_period))

            if ptype == "DEPOSIT":
                invest = total_balance if max_join <= 0 else min(total_balance, max_join)
                invest = max(invest, 1)
                expected_interest = invest * eff_rate / 100 * target_period / 12
            else:  # SAVINGS
                invest = monthly_surplus if max_join <= 0 else min(monthly_surplus, max_join)
                invest = max(invest, 1)
                # 적금 만기이자: 월납입 × 기간 × 금리 × (기간+1) / 24  (평균잔액 기반)
                expected_interest = invest * target_period * eff_rate / 100 * (target_period + 1) / 24

            # ── 3. 유동성 매칭 (20점) ─────────────────────────────────────
            if monthly_tx >= 10:          # 고빈도 → 단기 선호
                if min_month <= 12:
                    liquidity = 1.0
                elif min_month <= 24:
                    liquidity = 0.5
                else:
                    liquidity = 0.1
            elif monthly_tx <= 5:         # 저빈도 → 장기 선호
                liquidity = 1.0 if max_month >= 24 else 0.7
            else:
                liquidity = 0.7
            if is_early:
                liquidity = min(liquidity + 0.2, 1.0)

            # ── 4. 부가 혜택 (10점): 비과세(7) + 중도해지(3) ─────────────
            benefit = (0.7 if is_tax else 0.0) + (0.3 if is_early else 0.0)

            candidates.append({
                "product":           p,
                "fit":               fit,
                "expected_interest": expected_interest,
                "liquidity":         liquidity,
                "benefit":           benefit,
                "eff_rate":          eff_rate,
                "base_rate":         base_rate,
                "pref_rate":         pref_rate,
            })

        if not candidates:
            return []

        # 수익성 정규화
        max_interest = max(c["expected_interest"] for c in candidates) or 1
        for c in candidates:
            c["return_score"] = c["expected_interest"] / max_interest

        # 최종 점수 합산 및 정렬 (동점 시 실제 금리 높은 순)
        for c in candidates:
            c["total"] = (
                c["fit"]          * 40 +
                c["return_score"] * 30 +
                c["liquidity"]    * 20 +
                c["benefit"]      * 10
            )

        candidates.sort(key=lambda x: (-x["total"], -x["eff_rate"]))

        result = []
        for c in candidates:
            p = dict(c["product"])
            p["_reason"] = self._make_reason(p, cf, c, input_period)
            p["base_interest_rate"] = c["eff_rate"]   # 표시 금리를 실제 적용금리로
            result.append(p)
        return result

    def _make_reason(self, p: dict[str, Any], cf: dict[str, Any], score_info: dict[str, Any], input_period: int = 0) -> str:
        ptype           = p.get("deposit_product_type") or p.get("product_type", "")
        min_join        = float(p.get("min_join_amount") or 0)
        min_month       = int(p.get("min_period_month") or 1)
        max_month       = int(p.get("max_period_month") or min_month)
        monthly_surplus = float(cf.get("monthly_surplus", 0))
        total_balance   = float(cf.get("total_balance", 0))
        expected        = score_info.get("expected_interest", 0)
        base_rate       = score_info.get("base_rate", 0.0)
        pref_rate       = score_info.get("pref_rate", 0.0)
        eff_rate        = score_info.get("eff_rate", 0.0)

        # 실제 계산에 사용된 기간과 납입금액 (이자 표시에 사용)
        base_period   = input_period if input_period > 0 else 12
        target_period = max(min_month, min(max_month, base_period))

        # pref_condition은 _rank_products에서 이미 fallback까지 포함해 설정됨
        pref_cond = p.get("pref_condition", "")
        pref_note = f" (우대조건: {pref_cond})" if pref_rate > 0 and pref_cond else ""

        rate_str = (
            f"기본 {base_rate}% + 우대 {pref_rate}% = 최대 {eff_rate}%{pref_note}"
            if pref_rate > 0 else f"금리 {eff_rate}%"
        )

        if ptype == "DEPOSIT":
            max_join  = float(p.get("max_join_amount") or 0)
            invest    = total_balance if max_join <= 0 else min(total_balance, max_join)
            usage_pct = int(invest / total_balance * 100) if total_balance > 0 else 0
            return (
                f"{rate_str} · 잔액 {invest:,.0f}원({usage_pct}%) 예치 → "
                f"{target_period}개월 이자 약 {expected:,.0f}원 예상"
            )
        if ptype == "SAVINGS":
            max_join  = float(p.get("max_join_amount") or 0)
            invest    = monthly_surplus if max_join <= 0 else min(monthly_surplus, max_join)
            usage_pct = int(invest / monthly_surplus * 100) if monthly_surplus > 0 else 0
            remainder = monthly_surplus - invest
            return (
                f"{rate_str} · 월 {invest:,.0f}원 납입(잉여자금의 {usage_pct}%), "
                f"납입 후 {remainder:,.0f}원 여유 → {target_period}개월 이자 약 {expected:,.0f}원 예상"
            )
        if ptype == "SUBSCRIPTION":
            payment = max(min_join, 0)
            return (
                f"{rate_str} · 월 {payment:,.0f}원 납입 → "
                f"{min_month}개월 이자 약 {expected:,.0f}원, 주택청약 목적"
            )
        return rate_str

    def _resolve_ambiguous_query(
        self, query: str, chatbot_consultation_id: int | None
    ) -> str:
        """'둘 중', '어느 쪽' 같은 지시어가 있을 때 이전 대화에서 언급된 상품을 추출해 쿼리를 보완한다."""
        AMBIGUOUS_WORDS = [
            "둘 중", "어느 쪽", "어느쪽", "어떤 쪽", "어떤쪽", "둘다", "둘 다",
            "그 중", "그중", "이 중", "이중", "그것", "그게", "그거",
            "뭐가 더 나", "뭐가 더 적합", "뭐가 더 좋", "뭐가 더 유리",
            "어떤 게 더 나", "어느 게 더 나", "어떤 게 더 적합",
            "어느 쪽이 더", "어떤 쪽이 더",
            "더 나은 게", "더 적합한 게", "더 좋은 게",
            "하나만", "딱 하나", "하나 골라", "하나 추천", "하나만 골라", "하나만 추천",
        ]
        if not any(w in query for w in AMBIGUOUS_WORDS):
            return query
        if not chatbot_consultation_id:
            return query

        history_text = self._build_history_context(chatbot_consultation_id, max_turns=5)
        if not history_text:
            return query

        _PRODUCT_TYPE_WORDS = {
            "예금": "예금",
            "적금": "적금",
            "청약": "청약",
            "자유적금": "자유적금",
            "정기적금": "정기적금",
            "정기예금": "정기예금",
        }
        mentioned = [label for word, label in _PRODUCT_TYPE_WORDS.items() if word in history_text]
        # 중복 제거, 순서 유지
        seen: set[str] = set()
        unique_mentioned = [x for x in mentioned if not (x in seen or seen.add(x))]  # type: ignore

        if unique_mentioned:
            context = "/".join(unique_mentioned)
            return f"{query} (이전 대화에서 언급된 상품: {context})"
        return query

    def _rule_based_recommend(
        self, cf: dict[str, Any], products: list[dict[str, Any]], query: str = ""
    ) -> str:
        """LLM 미연결 시 현금흐름 지표 기반 룰 추천 텍스트.

        잔액·잉여자금 크기에 따라 예금/적금/자유적금을 우선 추천한다.
        예금 vs 적금 비교 질문은 별도 판단 로직을 적용한다.
        """
        total_balance   = float(cf.get("total_balance", 0))
        monthly_surplus = float(cf.get("monthly_surplus", 0))
        has_data        = cf.get("has_data", False)

        is_comparison = "예금" in query and "적금" in query

        lines: list[str] = ["[현금흐름 분석 기반 상품 추천]\n"]

        if is_comparison:
            # 예금 vs 적금 비교 질문 전용 로직
            if not has_data:
                lines.append(
                    "거래 내역이 부족해 정확한 패턴 분석이 어렵지만, "
                    "일반적인 기준으로 안내해 드립니다.\n\n"
                    "📌 예금이 적합한 경우\n"
                    "- 이미 목돈(1,000만원 이상)이 있어 한 번에 맡기고 싶을 때\n"
                    "- 정해진 기간 동안 돈을 묶어두고 이자를 받고 싶을 때\n\n"
                    "📌 적금이 적합한 경우\n"
                    "- 매달 일정 금액을 저축하며 목돈을 만들고 싶을 때\n"
                    "- 저축 습관을 들이면서 목표 금액을 모아가고 싶을 때"
                )
            elif total_balance >= 10_000_000:
                lines.append(
                    f"고객님의 총 잔액은 {total_balance:,.0f}원으로, "
                    f"목돈이 있으시므로 **예금**이 더 유리합니다.\n"
                    f"예금은 목돈을 한 번에 예치해 확정 금리를 받을 수 있습니다.\n"
                    f"월 잉여자금({monthly_surplus:,.0f}원)이 있다면 "
                    f"일부를 추가로 적금에 납입하는 방법도 좋습니다."
                )
            elif monthly_surplus >= 300_000:
                lines.append(
                    f"고객님의 월 잉여자금은 {monthly_surplus:,.0f}원으로, "
                    f"매달 꾸준히 저축하기 좋은 패턴입니다. **적금**을 추천드립니다.\n"
                    f"적금은 매달 일정 금액을 납입해 목돈을 만드는 상품입니다.\n"
                    f"현재 잔액({total_balance:,.0f}원)도 어느 정도 있으시니 "
                    f"일부는 단기 예금에 예치하는 것도 고려해 보세요."
                )
            elif monthly_surplus > 0:
                lines.append(
                    f"고객님의 월 잉여자금이 {monthly_surplus:,.0f}원으로 많지 않습니다. "
                    f"부담이 적은 **자유납입 적금**을 추천드립니다.\n"
                    f"자유적금은 납입 금액과 시기를 자유롭게 조절할 수 있어 "
                    f"여유가 있을 때 저축하기 좋습니다."
                )
            else:
                lines.append(
                    "현재 월 잉여자금이 거의 없는 상황입니다.\n"
                    "당장 목돈이 없다면 예금보다 **소액 자유적금**부터 시작하는 것을 권장합니다.\n"
                    "소액이라도 꾸준히 모으면 이후 예금으로 전환할 수 있습니다."
                )
        elif not has_data:
            lines.append(
                "거래 내역이 부족해 정확한 패턴 분석이 어렵습니다. "
                "아래 상품 목록을 참고해 주세요."
            )
        elif total_balance >= 10_000_000:
            lines.append(
                f"총 잔액 {total_balance:,.0f}원 — "
                "목돈이 있어 정기예금 상품을 추천드립니다."
            )
        elif monthly_surplus >= 500_000:
            lines.append(
                f"월 잉여자금 {monthly_surplus:,.0f}원 — "
                "정기 적금 납입에 적합합니다."
            )
        elif monthly_surplus > 0:
            lines.append(
                f"월 잉여자금 {monthly_surplus:,.0f}원 — "
                "소액 자유적금 상품을 추천드립니다."
            )
        else:
            lines.append(
                "현재 잉여자금이 적습니다. "
                "부담이 적은 자유납입 적금을 추천드립니다."
            )

        # 비교 질문은 상품 목록 나열 없이 유형 판단만
        if is_comparison:
            lines.append("\n더 자세한 상품 안내는 '상담사 연결'을 이용해 주세요.")
            return "\n".join(lines)

        # 일반 추천 시 상품 목록 나열
        top = [
            p for p in products
            if (
                (total_balance >= 10_000_000 and p.get("deposit_product_type") == "DEPOSIT")
                or (monthly_surplus >= 100_000 and p.get("deposit_product_type") in ("SAVINGS", "SUBSCRIPTION"))
                or True
            )
        ][:3]

        if top:
            lines.append("\n[추천 상품]")
            for p in top:
                name = p.get("deposit_product_name") or p.get("product_name", "")
                rate = p.get("base_interest_rate", "")
                ptype = p.get("deposit_product_type", "")
                type_label = {"DEPOSIT": "예금", "SAVINGS": "적금", "SUBSCRIPTION": "청약"}.get(ptype, ptype)
                lines.append(f"- [{type_label}] {name}: 기본금리 {rate}%")

        lines.append("\n더 자세한 상담은 '상담사 연결'을 이용해 주세요.")
        return "\n".join(lines)

    def _execute_staff_customer(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        if not request.customer_no or not request.staff_id:
            return self._staff_auth_required("STAFF_CUSTOMER", "직원 고객 정보 조회에는 고객번호와 직원 권한이 필요합니다.")
        rows = self._account_rows(request.customer_no)
        return self._data_response(
            "STAFF_CUSTOMER", rows, "직원용 고객 정보 조회를 완료했습니다.", "조회된 고객 정보가 없습니다.", requires_staff_auth=True
        )

    def _execute_staff_account(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        if not request.customer_no or not request.staff_id:
            return self._staff_auth_required("STAFF_ACCOUNT", "직원 고객 계좌 조회에는 고객번호와 직원 권한이 필요합니다.")
        rows = self._account_rows(request.customer_no)
        return self._data_response(
            "STAFF_ACCOUNT", rows, "직원용 고객 계좌 조회를 완료했습니다.", "조회된 고객 계좌가 없습니다.", requires_staff_auth=True
        )

    def _execute_staff_transfer_flow(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        if not request.customer_no or not request.staff_id:
            return self._staff_auth_required("STAFF_TRANSFER_FLOW", "이체 흐름 조회에는 고객번호와 직원 권한이 필요합니다.")
        rows = self._rows(
            """
            SELECT t.transaction_id,
                   t.transaction_number,
                   a.account_number,
                   a.customer_id AS customer_no,
                   t.transaction_type,
                   t.transaction_status,
                   t.amount,
                   t.created_at
              FROM deposit_transactions t
              JOIN deposit_accounts a ON a.account_id = t.account_id
             WHERE a.customer_id = :customer_no
             ORDER BY t.transaction_id DESC
             LIMIT 20
            """,
            {"customer_no": request.customer_no},
        )
        return self._data_response(
            "STAFF_TRANSFER_FLOW", rows, "이체 흐름 조회를 완료했습니다.", "조회된 이체 내역이 없습니다.", requires_staff_auth=True
        )

    def _execute_staff_consultation_history(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        if not request.customer_no or not request.staff_id:
            return self._staff_auth_required("STAFF_CONSULTATION_HISTORY", "상담 이력 조회에는 고객번호와 직원 권한이 필요합니다.")
        rows = self._rows(
            """
            SELECT consultation_id,
                   customer_no,
                   content_summary,
                   status_code_id,
                   answer_summary,
                   consulted_at,
                   completed_at
              FROM consultation
             WHERE customer_no = :customer_no
             ORDER BY consultation_id DESC
             LIMIT 20
            """,
            {"customer_no": request.customer_no},
        )
        return self._data_response(
            "STAFF_CONSULTATION_HISTORY", rows, "상담 이력 조회를 완료했습니다.", "조회된 상담 이력이 없습니다.", requires_staff_auth=True
        )

    # ── 금리 헬퍼 ─────────────────────────────────────────────────────────────

    def _get_customer_age(self, customer_id: str) -> int | None:
        """customer-service /api/v1/customers/me 에서 birthDate를 읽어 만 나이를 반환."""
        from app.config import get_settings
        url = f"{get_settings().customer_service_url}/api/v1/customers/me"
        try:
            with httpx.Client(timeout=2.0) as client:
                resp = client.get(url, headers={"X-Customer-Id": str(customer_id)})
            if resp.status_code != 200:
                return None
            body = resp.json()
            birth_date_str = (body.get("data") or body).get("birthDate")
            if not birth_date_str:
                return None
            # 형식: "19900101" 또는 "1990-01-01"
            birth_date_str = birth_date_str.replace("-", "")
            birth = date(int(birth_date_str[:4]), int(birth_date_str[4:6]), int(birth_date_str[6:8]))
            today = date.today()
            age = today.year - birth.year - ((today.month, today.day) < (birth.month, birth.day))
            return age
        except Exception:
            return None

    def _filter_by_age(self, products: list[dict[str, Any]], age: int | None) -> list[dict[str, Any]]:
        """고객 나이 기반으로 가입 자격이 없는 상품 제외.

        - 청년 전용(target_group_id=3, 만 19~34세): 나이가 34 초과이면 제외
        - 현역병 전용(target_group_id=4): 나이 정보만으로 판단 불가 → 이름 키워드 유지
        - 나이 정보 없으면 기존 키워드 필터만 적용
        """
        if age is None:
            return products

        youth_ids = {
            int(r["banking_product_id"])
            for r in self._rows("""
                SELECT banking_product_id
                  FROM banking_deposit_product_target_groups
                 WHERE target_group_id = 3
            """)
        }

        filtered = []
        for p in products:
            pid = int(p.get("product_id") or p.get("banking_product_id") or 0)
            if pid in youth_ids and not (19 <= age <= 34):
                continue  # 청년 전용 + 나이 미충족
            filtered.append(p)
        return filtered

    def _get_applicable_rates(self, product_ids: list[int], period: int) -> dict[int, dict[str, Any]]:
        """주어진 기간에 적용되는 실제 BASE + PREFERENTIAL 금리를 반환."""
        if not product_ids:
            return {}
        id_list = ",".join(str(i) for i in product_ids)
        rows = self._rows(
            f"""
            SELECT banking_product_id, rate_type,
                   COALESCE(SUM(rate), 0) AS total_rate
              FROM banking_deposit_product_interest_rates
             WHERE banking_product_id IN ({id_list})
               AND minimum_contract_period <= :period
               AND maximum_contract_period >= :period
             GROUP BY banking_product_id, rate_type
            """,
            {"period": max(period, 1)},
        )
        result: dict[int, dict[str, Any]] = {}
        for r in rows:
            pid = r["banking_product_id"]
            if pid not in result:
                result[pid] = {"base": 0.0, "pref": 0.0}
            if r["rate_type"] == "BASE":
                result[pid]["base"] = float(r["total_rate"])
            elif r["rate_type"] == "PREFERENTIAL":
                result[pid]["pref"] = float(r["total_rate"])
        return result

    def _get_rate_ranges(self, product_ids: list[int]) -> dict[int, dict[str, Any]]:
        """상품별 전체 기간에 걸친 최소~최대 적용 가능 금리 범위를 반환."""
        if not product_ids:
            return {}
        id_list = ",".join(str(i) for i in product_ids)
        rows = self._rows(
            f"""
            SELECT banking_product_id,
                   MIN(CASE WHEN rate_type='BASE' THEN rate END) AS min_base,
                   MAX(CASE WHEN rate_type='BASE' THEN rate END) AS max_base,
                   MAX(CASE WHEN rate_type='PREFERENTIAL' THEN rate END) AS max_pref
              FROM banking_deposit_product_interest_rates
             WHERE banking_product_id IN ({id_list})
             GROUP BY banking_product_id
            """
        )
        return {
            r["banking_product_id"]: {
                "min_base": float(r["min_base"] or 0),
                "max_base": float(r["max_base"] or 0),
                "max_pref": float(r["max_pref"] or 0),
            }
            for r in rows
        }

    def _get_pref_conditions(self, product_ids: list[int]) -> dict[int, dict]:
        """상품별 우대금리 조건 설명 및 합산 금리 조회."""
        if not product_ids:
            return {}
        id_list = ",".join(str(i) for i in product_ids)
        rows = self._rows(
            f"""
            SELECT banking_product_id,
                   STRING_AGG(condition_description, ' / ' ORDER BY rate_id) AS pref_conditions,
                   SUM(rate) AS total_pref_rate
              FROM banking_deposit_product_interest_rates
             WHERE banking_product_id IN ({id_list})
               AND rate_type = 'PREFERENTIAL'
               AND condition_description IS NOT NULL
             GROUP BY banking_product_id
            """
        )
        return {
            r["banking_product_id"]: {
                "condition": r["pref_conditions"],
                "rate": float(r["total_pref_rate"] or 0.0),
            }
            for r in rows
        }

    # ── 파싱 헬퍼 ─────────────────────────────────────────────────────────────
    @staticmethod
    def _parse_period(value: str | None) -> int:
        """기간 파싱: '12개월' → 12, '1년' → 12, '6' → 6."""
        if not value:
            return 0
        import re
        v = str(value).strip()
        year_match = re.search(r"(\d+)\s*년", v)
        if year_match:
            return int(year_match.group(1)) * 12
        month_match = re.search(r"(\d+)", v)
        if month_match:
            return int(month_match.group(1))
        return 0

    @staticmethod
    def _parse_amount(value: str | float | int | None) -> float:
        """금액 파싱: '100만원' → 1000000, '1,000,000' → 1000000, '50만' → 500000."""
        if value is None:
            return 0.0
        if isinstance(value, (int, float)):
            return float(value)
        import re
        v = str(value).replace(",", "").strip()
        man_match = re.search(r"(\d+(?:\.\d+)?)\s*만", v)
        if man_match:
            return float(man_match.group(1)) * 10_000
        num_match = re.search(r"(\d+(?:\.\d+)?)", v)
        if num_match:
            return float(num_match.group(1))
        return 0.0

    def _execute_product_search(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        """_rank_products 기반 100점 채점 모델로 상품 추천."""
        period  = self._parse_period(str(request.period) if request.period else None)
        amount  = self._parse_amount(request.amount)
        ptype   = request.product_type  # DEPOSIT / SAVINGS / SUBSCRIPTION / None (없으면 전체)

        # 청약 선택 시 단순 안내만 (채점 없이)
        if ptype == "SUBSCRIPTION":
            rows = self._rows("""
                SELECT p.banking_product_id AS product_id, p.deposit_product_name AS product_name,
                       p.deposit_product_type AS product_type, p.base_interest_rate,
                       p.min_period_month, p.max_period_month, p.min_join_amount, p.max_join_amount,
                       p.description
                  FROM deposit_banking_products p
                 WHERE p.deposit_product_status = 'SELLING'
                   AND p.deposit_product_type = 'SUBSCRIPTION'
                 ORDER BY p.base_interest_rate DESC NULLS LAST LIMIT 5
            """)
            data = [
                {"row_type": "recommended_product", "rank": i + 1,
                 "product_id": p.get("product_id") or p.get("banking_product_id"),
                 "product_name": p.get("product_name", ""), "product_type": "SUBSCRIPTION",
                 "base_interest_rate": p.get("base_interest_rate"),
                 "min_period_month": p.get("min_period_month"), "max_period_month": p.get("max_period_month"),
                 "min_join_amount": p.get("min_join_amount"), "max_join_amount": p.get("max_join_amount"),
                 "reason": "청약 상품 안내"}
                for i, p in enumerate(rows[:3])
            ]
            if not data:
                return self._data_response("PRODUCT_SEARCH", [], "", "청약 상품 정보를 찾을 수 없습니다.")
            return ChatbotFeatureExecuteResponse(
                feature_code="PRODUCT_SEARCH", status="OK",
                message="청약 상품 안내입니다. 가입조건을 확인하세요.", data=data,
            )

        # ── DEPOSIT / SAVINGS / 전체 → _rank_products 기반 채점 ──────────────
        EXCLUDE_KEYWORDS = ['장병', '군인', '군무원', '청년', 'Youth', 'Young']
        kw_conds = " AND ".join(f"p.deposit_product_name NOT LIKE '%{kw}%'" for kw in EXCLUDE_KEYWORDS)

        type_cond = ""
        if ptype in ("DEPOSIT", "SAVINGS"):
            type_cond = f"AND p.deposit_product_type = '{ptype}'"
        else:
            # 유형 미지정: DEPOSIT + SAVINGS 전체 (청약 제외)
            type_cond = "AND p.deposit_product_type IN ('DEPOSIT', 'SAVINGS')"

        products = self._rows(
            f"""
            SELECT p.banking_product_id        AS product_id,
                   p.deposit_product_name      AS deposit_product_name,
                   p.deposit_product_type      AS deposit_product_type,
                   p.base_interest_rate,
                   p.min_period_month, p.max_period_month,
                   p.min_join_amount,  p.max_join_amount,
                   p.is_early_termination_allowed,
                   p.is_tax_benefit_available
              FROM deposit_banking_products p
             WHERE p.deposit_product_status = 'SELLING'
               AND p.min_period_month IS NOT NULL
               AND {kw_conds}
               {type_cond}
             ORDER BY p.base_interest_rate DESC NULLS LAST
             LIMIT 50
            """
        )

        # 고객 나이 조회 → 나이 기반 상품 필터 (customer-service 연동)
        customer_age = self._get_customer_age(request.customer_no) if request.customer_no else None
        if customer_age is not None:
            products = self._filter_by_age(products, customer_age)
        # (키워드 필터는 kw_conds SQL에서 이미 처리됨)

        # 고객 현금흐름 조회 (없으면 입력 금액으로 대체 cf 구성)
        cf = None
        if request.customer_no:
            cf = self._analyze_customer_cash_flow(request.customer_no)
        if cf is None:
            if ptype == "DEPOSIT":
                cf = {"total_balance": amount or 1_000_000, "monthly_surplus": 0.0,
                      "monthly_tx_count": 5.0, "has_data": False}
            else:
                cf = {"total_balance": 0.0, "monthly_surplus": amount or 300_000,
                      "monthly_tx_count": 5.0, "has_data": False}

        # 사용자가 직접 금액을 입력했으면 cf를 해당 금액으로 덮어씀
        # → "내 잔액이 얼마든 이 금액으로 가입 가능한 상품만 보여줘" 의도 반영
        if amount and amount > 0:
            if ptype == "DEPOSIT":
                cf = {**cf, "total_balance": amount}
            elif ptype == "SAVINGS":
                cf = {**cf, "monthly_surplus": amount}
            else:
                # 전체(DEPOSIT+SAVINGS) 선택 시 양쪽 모두 입력 금액으로 설정
                cf = {**cf, "total_balance": amount, "monthly_surplus": amount}

        # 우대금리 조건 보강
        _PREF_COND_FALLBACK: list[tuple[str, str]] = [
            ("맑은하늘",   "맑은하늘 앱 설치 후 인증코드 등록"),
            ("직장인우대", "급여이체 실적 등록"),
            ("자유적금",   "자동이체 설정"),
            ("내맘대로",   "자동이체 설정"),
            ("달러",       "달러 환전 실적 보유"),
            ("청년도약",   "소득 요건 충족 확인"),
            ("수퍼정기",   "비대면 가입"),
            ("정기예금",   "비대면(인터넷·스타뱅킹) 가입"),
            ("꿈적금",     "만기 유지"),
            ("함께적금",   "2인 이상 공동 가입"),
        ]
        pids = [int(p.get("product_id") or p.get("banking_product_id") or 0) for p in products]
        pref_cond_map = self._get_pref_conditions(pids)
        for p in products:
            pid = int(p.get("product_id") or p.get("banking_product_id") or 0)
            info = pref_cond_map.get(pid, {})
            cond = info.get("condition", "") if isinstance(info, dict) else ""
            rate = info.get("rate", 0.0) if isinstance(info, dict) else 0.0
            if not cond:
                name = str(p.get("deposit_product_name") or p.get("product_name", ""))
                for keyword, fallback in _PREF_COND_FALLBACK:
                    if keyword in name:
                        cond = fallback
                        break
            if cond:
                p["pref_condition"] = cond
            if rate:
                p["pref_rate"] = rate

        ranked = self._rank_products(cf, products, input_period=period)

        if not ranked:
            # 최소가입금액 때문에 필터링된 건지 확인해서 구체적 안내
            if amount and amount > 0 and products:
                min_amounts = [float(p.get("min_join_amount") or 0) for p in products if p.get("min_join_amount")]
                if min_amounts:
                    lowest_min = min(min_amounts)
                    type_label = "월 납입액" if ptype == "SAVINGS" else "가입금액"
                    return self._data_response(
                        "PRODUCT_SEARCH", [], "",
                        f"입력하신 {type_label} {int(amount):,}원은 해당 상품들의 최소 가입금액({int(lowest_min):,}원~)보다 적습니다.\n"
                        f"금액을 {int(lowest_min):,}원 이상으로 다시 입력해 주세요.",
                    )
            return self._data_response("PRODUCT_SEARCH", [], "", "조건에 맞는 상품이 없습니다.")

        top3 = ranked[:3]
        data = [
            {
                "row_type":          "recommended_product",
                "rank":              i + 1,
                "product_id":        p.get("product_id") or p.get("banking_product_id"),
                "product_name":      p.get("deposit_product_name") or p.get("product_name", ""),
                "product_type":      p.get("deposit_product_type") or p.get("product_type", ""),
                "base_interest_rate": p.get("base_interest_rate"),
                "min_period_month":  p.get("min_period_month"),
                "max_period_month":  p.get("max_period_month"),
                "min_join_amount":   p.get("min_join_amount"),
                "max_join_amount":   p.get("max_join_amount"),
                "reason":            p.get("_reason", ""),
                "description":       p.get("description", ""),
                "pref_condition":    p.get("pref_condition", ""),
                "pref_rate":         p.get("pref_rate", 0.0),
            }
            for i, p in enumerate(top3)
        ]

        type_label = {"DEPOSIT": "예금", "SAVINGS": "적금"}.get(ptype or "", "맞춤 상품")
        msg_parts = []
        if period > 0:
            msg_parts.append(f"{period}개월")
        if amount > 0:
            msg_parts.append(f"{'월납입' if ptype=='SAVINGS' else '가입금액'} {int(amount):,}원")
        cond_str = " / ".join(msg_parts) if msg_parts else "현금흐름 분석"
        names = " · ".join(d["product_name"] for d in data)
        message = f"{cond_str} 기준 {type_label} 추천: {names}"

        return ChatbotFeatureExecuteResponse(
            feature_code="PRODUCT_SEARCH", status="OK",
            message=message,
            data=data,
        )

    def _execute_savings_goal(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        from app.features.savings_goal import SavingsGoalFeatureExecutor
        executor = SavingsGoalFeatureExecutor(db=self.db)
        return executor.execute_savings_goal(request)

    def _execute_staff_cash_flow(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        if not request.customer_no or not request.staff_id:
            return self._staff_auth_required("STAFF_CASH_FLOW", "고객 현금 흐름 조회에는 고객번호와 직원 권한이 필요합니다.")
        rows = self._rows(
            """
            SELECT t.transaction_id,
                   a.account_number,
                   a.customer_id AS customer_no,
                   t.transaction_type,
                   t.amount,
                   t.transaction_status,
                   t.created_at
              FROM deposit_transactions t
              JOIN deposit_accounts a ON a.account_id = t.account_id
             WHERE a.customer_id = :customer_no
             ORDER BY t.transaction_id DESC
             LIMIT 20
            """,
            {"customer_no": request.customer_no},
        )
        return self._data_response(
            "STAFF_CASH_FLOW", rows, "고객 현금 흐름 조회를 완료했습니다.", "조회된 거래 내역이 없습니다.", requires_staff_auth=True
        )

    def _execute_customer_contracts(
        self,
        request: ChatbotFeatureExecuteRequest,
        feature_code: str,
        ok_message: str,
        empty_message: str,
        requires_staff_auth: bool = False,
    ) -> ChatbotFeatureExecuteResponse:
        if requires_staff_auth and (not request.customer_no or not request.staff_id):
            return self._staff_auth_required(feature_code, "계약 조회에는 고객번호와 직원 권한이 필요합니다.")
        if not requires_staff_auth and not request.customer_no:
            return self._auth_required(feature_code, "계약 조회에는 고객번호와 본인 인증이 필요합니다.")
        rows = self._contract_rows(request.customer_no or "")
        return self._data_response(
            feature_code,
            rows,
            ok_message,
            empty_message,
            requires_auth=not requires_staff_auth,
            requires_staff_auth=requires_staff_auth,
        )

    def _account_rows(self, customer_no: str) -> list[dict[str, Any]]:
        return self._rows(
            """
            SELECT account_id,
                   account_number,
                   customer_id AS customer_no,
                   account_type,
                   account_alias,
                   balance,
                   currency,
                   account_status,
                   opened_at,
                   closed_at
              FROM deposit_accounts
             WHERE customer_id = :customer_no
             ORDER BY account_id
             LIMIT 20
            """,
            {"customer_no": customer_no},
        )

    def _contract_rows(self, customer_no: str) -> list[dict[str, Any]]:
        return self._rows(
            """
            SELECT c.contract_id,
                   c.contract_number AS contract_no,
                   c.customer_id AS customer_no,
                   c.join_amount,
                   c.contract_interest_rate,
                   c.started_at,
                   c.maturity_at,
                   c.contract_status,
                   p.banking_product_id AS product_id,
                   p.deposit_product_name AS product_name,
                   p.deposit_product_type AS product_type
              FROM deposit_contracts c
              LEFT JOIN deposit_banking_products p ON p.banking_product_id = c.banking_product_id
             WHERE c.customer_id = :customer_no
             ORDER BY c.contract_id
             LIMIT 20
            """,
            {"customer_no": customer_no},
        )

    def _rows(
        self,
        sql: str,
        params: dict[str, Any] | None = None,
        expanding_params: tuple[str, ...] = (),
    ) -> list[dict[str, Any]]:
        try:
            statement = text(sql)
            for param in expanding_params:
                statement = statement.bindparams(bindparam(param, expanding=True))
            result = self.db.execute(statement, params or {})
            return [dict(row._mapping) for row in result]
        except Exception:
            self.db.rollback()
            return []

    def _data_response(
        self,
        feature_code: str,
        rows: list[dict[str, Any]],
        ok_message: str,
        empty_message: str,
        requires_auth: bool = False,
        requires_staff_auth: bool = False,
    ) -> ChatbotFeatureExecuteResponse:
        return ChatbotFeatureExecuteResponse(
            feature_code=feature_code,
            status="OK" if rows else "EMPTY",
            message=ok_message if rows else empty_message,
            data=rows,
            requires_auth=requires_auth,
            requires_staff_auth=requires_staff_auth,
        )

    def _auth_required(self, feature_code: str, message: str) -> ChatbotFeatureExecuteResponse:
        return ChatbotFeatureExecuteResponse(
            feature_code=feature_code,
            status="AUTH_REQUIRED",
            message=message,
            requires_auth=True,
        )

    def _staff_auth_required(self, feature_code: str, message: str) -> ChatbotFeatureExecuteResponse:
        return ChatbotFeatureExecuteResponse(
            feature_code=feature_code,
            status="STAFF_AUTH_REQUIRED",
            message=message,
            requires_staff_auth=True,
        )

    def seed_default_scenario(self) -> tuple[int, int]:
        scenario = self._ensure_default_scenario()
        self.db.commit()
        first = self._get_first_node(scenario.scenario_id)
        return scenario.scenario_id, first.node_id if first else 0

    def _ensure_default_scenario(self) -> ChatbotScenario:
        scenario = self._get_active_scenario()
        if not scenario:
            scenario = ChatbotScenario(
                scenario_name="기본 수신 상담",
                scenario_desc="금융상품 상담, 사용자 금융정보 조회, 직원 업무 지원 챗봇 시나리오",
                scenario_type_code_id=CODE_SCENARIO_TYPE_DEFAULT,
                consultation_category_code_id=CODE_CATEGORY_PRODUCT_ADVICE,
                reception_channel_code_id=CODE_RECEPTION_CHANNEL_CHAT,
                active_yn="Y",
            )
            self.db.add(scenario)
            self.db.flush()

        start = self._ensure_node(
            scenario.scenario_id,
            "상담 시작",
            "안녕하세요. 필요한 상담 유형을 선택해 주세요.",
            1,
        )
        for spec in self._default_flow_specs():
            node = self._ensure_node(
                scenario.scenario_id,
                spec["node_name"],
                spec["response_message"],
                int(spec["sort_order"]),
            )
            self._ensure_button(start.node_id, spec["button_text"], spec["button_value"], int(spec["sort_order"]))
            self._ensure_flow(start.node_id, node.node_id, int(spec["sort_order"]), str(spec["button_value"]))
        self._deactivate_legacy_start_options(start.node_id, {spec["button_value"] for spec in self._default_flow_specs()})
        self._ensure_default_intents(scenario.scenario_id)
        return scenario

    def _default_flow_specs(self) -> list[dict[str, Any]]:
        return [
            {
                "button_text": "금융상품 상담",
                "button_value": "PRODUCT_ADVICE",
                "node_name": "금융상품 상담",
                "response_message": "예금/적금/청약, 금리, 가입 조건, 상품 비교, 약관 기반 응답과 FAQ를 안내합니다.",
                "sort_order": 1,
            },
            {
                "button_text": "사용자 금융정보 조회",
                "button_value": "USER_FINANCE",
                "node_name": "사용자 금융정보 조회",
                "response_message": "본인 계좌, 가입 상품, 계약 상태, 만기 예정, 이자 내역 조회를 지원합니다.",
                "sort_order": 2,
            },
            {
                "button_text": "직원 업무 지원",
                "button_value": "STAFF_SUPPORT",
                "node_name": "직원 업무 지원",
                "response_message": "직원용 고객 정보, 계약, 계좌, 이체 흐름, 상담 이력 조회를 지원합니다.",
                "sort_order": 3,
            },
            {
                "button_text": "상담사 연결",
                "button_value": "AGENT",
                "node_name": "상담사 연결",
                "response_message": "상담사 연결이 필요한 문의로 접수하겠습니다.",
                "sort_order": 4,
            },
        ]

    def _ensure_node(self, scenario_id: int, node_name: str, response_message: str, sort_order: int) -> ChatbotNode:
        node = self.db.scalars(
            select(ChatbotNode).where(
                ChatbotNode.scenario_id == scenario_id,
                ChatbotNode.node_name == node_name,
            )
        ).first()
        if node:
            return node
        node = ChatbotNode(
            scenario_id=scenario_id,
            node_type_code_id=CODE_NODE_TYPE_MESSAGE,
            node_name=node_name,
            response_message=response_message,
            sort_order=sort_order,
            active_yn="Y",
        )
        self.db.add(node)
        self.db.flush()
        return node

    def _ensure_button(self, node_id: int, button_text: str, button_value: str, sort_order: int) -> None:
        button = self.db.scalars(
            select(ChatbotNodeButton).where(
                ChatbotNodeButton.node_id == node_id,
                ChatbotNodeButton.button_value == button_value,
            )
        ).first()
        if not button:
            self.db.add(
                ChatbotNodeButton(
                    node_id=node_id,
                    button_text=button_text,
                    button_value=button_value,
                    sort_order=sort_order,
                    active_yn="Y",
                )
            )

    def _ensure_flow(self, current_node_id: int, next_node_id: int, sort_order: int, branch_value: str) -> None:
        flow = self.db.scalars(
            select(ChatbotNodeFlow).where(
                ChatbotNodeFlow.current_node_id == current_node_id,
                ChatbotNodeFlow.next_node_id == next_node_id,
            )
        ).first()
        if not flow:
            self.db.add(
                ChatbotNodeFlow(
                    current_node_id=current_node_id,
                    next_node_id=next_node_id,
                    sort_order=sort_order,
                    chatbot_flow_type_cd="BUTTON",
                    branch_criteria_cd="BUTTON_VALUE",
                    branch_value=branch_value,
                    active_yn="Y",
                )
            )

    def _deactivate_legacy_start_options(self, node_id: int, allowed_values: set[str]) -> None:
        for button in self.db.scalars(select(ChatbotNodeButton).where(ChatbotNodeButton.node_id == node_id)).all():
            if button.button_value not in allowed_values:
                button.active_yn = "N"

    def _get_active_scenario(self) -> ChatbotScenario | None:
        return self.db.scalars(
            select(ChatbotScenario)
            .where(ChatbotScenario.active_yn == "Y", ChatbotScenario.scenario_id.is_not(None))
            .order_by((ChatbotScenario.scenario_name == "기본 수신 상담").desc(), ChatbotScenario.scenario_id)
        ).first()

    def _get_first_node(self, scenario_id: int) -> ChatbotNode | None:
        return self.db.scalars(
            select(ChatbotNode)
            .where(ChatbotNode.scenario_id == scenario_id, ChatbotNode.active_yn == "Y")
            .order_by(ChatbotNode.sort_order, ChatbotNode.node_id)
        ).first()

    def _resolve_next_node(
        self,
        scenario_id: int | None,
        current_node_id: int | None,
        button_value: str | None,
    ) -> ChatbotNode | None:
        if not current_node_id or not button_value:
            return None
        NextNode = aliased(ChatbotNode)
        flow = self.db.scalars(
            select(ChatbotNodeFlow)
            .join(NextNode, NextNode.node_id == ChatbotNodeFlow.next_node_id)
            .where(
                ChatbotNodeFlow.current_node_id == current_node_id,
                ChatbotNodeFlow.branch_value == button_value,
                ChatbotNodeFlow.active_yn == "Y",
                NextNode.scenario_id == scenario_id,
            )
            .order_by(ChatbotNodeFlow.sort_order)
        ).first()
        return self.db.get(ChatbotNode, flow.next_node_id) if flow else None

    def _button_responses(self, node_id: int) -> list[ButtonResponse]:
        buttons = self.db.scalars(
            select(ChatbotNodeButton)
            .where(ChatbotNodeButton.node_id == node_id, ChatbotNodeButton.active_yn == "Y")
            .order_by(ChatbotNodeButton.sort_order, ChatbotNodeButton.id)
        ).all()
        return [ButtonResponse(id=button.id, text=button.button_text, value=button.button_value) for button in buttons]

    def _record_message(
        self,
        chatbot: ChatbotConsultation,
        node: ChatbotNode | None,
        sender_type_code_id: int,
        message_content: str,
        button_value: str | None,
        process_method_code_id: int | None,
    ) -> None:
        last_sequence = self.db.execute(
            select(ChatMessageHistory.sequence_no)
            .where(ChatMessageHistory.chatbot_consultation_id == chatbot.chatbot_consultation_id)
            .order_by(ChatMessageHistory.sequence_no.desc())
            .limit(1)
        ).scalar_one_or_none()
        self.db.add(
            ChatMessageHistory(
                chatbot_consultation_id=chatbot.chatbot_consultation_id,
                node_id=node.node_id if node else None,
                sequence_no=(last_sequence or 0) + 1,
                sender_type_code_id=sender_type_code_id,
                message_type_code_id=CODE_MESSAGE_TYPE_TEXT,
                message_content=message_content,
                button_value=button_value,
                process_method_code_id=process_method_code_id,
            )
        )

    def _latest_node_id(self, chatbot_consultation_id: int) -> int | None:
        return self.db.execute(
            select(ChatMessageHistory.node_id)
            .where(ChatMessageHistory.chatbot_consultation_id == chatbot_consultation_id)
            .where(ChatMessageHistory.node_id.is_not(None))
            .order_by(ChatMessageHistory.sequence_no.desc())
            .limit(1)
        ).scalar_one_or_none()

    def _open_chat_consultation(self, chatbot: ChatbotConsultation) -> None:
        exists = self.db.scalars(
            select(ChatConsultation).where(ChatConsultation.chatbot_consultation_id == chatbot.chatbot_consultation_id)
        ).first()
        if not exists:
            self.db.add(
                ChatConsultation(
                    consultation_id=chatbot.consultation_id,
                    chatbot_consultation_id=chatbot.chatbot_consultation_id,
                    total_turn_count=0,
                    active_yn="Y",
                    agent_requested_at=datetime.now(timezone.utc),  # 대기열 조회용
                )
            )

    def _is_agent_node(self, node: ChatbotNode) -> bool:
        return node.node_name == "상담사 연결"

    def _run_feature_for_intent(
        self,
        feature_code: str,
        message: str,
        customer_no: str | None = None,
        chatbot_consultation_id: int | None = None,
    ) -> list[dict]:
        return self._run_feature_for_intent_full(
            feature_code, message, customer_no, chatbot_consultation_id
        ).data or []

    def _call_goal_agent(
        self,
        message: str,
        customer_no: str | None,
        chatbot_consultation_id: int | None,
    ):
        """
        Goal Agent(/agent/goal/chat)를 HTTP로 호출한다.
        응답 후 DB 세션을 저장하여 다음 턴이 savings_goal.py로 처리될 수 있게 한다.
        실패 시 기존 savings_goal.py 로 fallback.
        """
        import os as _os
        import logging as _log
        from app.schemas import ChatbotFeatureExecuteResponse
        from app.features.savings_goal import _parse_amount, _parse_months
        from app.models import ChatbotGoalSession as _GoalSession

        _agent_log = _log.getLogger("savings_goal.routing")
        goal_agent_url = _os.getenv("GOAL_AGENT_URL", "http://host.docker.internal:8000")
        goal_agent_api_key = _os.getenv("GOAL_AGENT_API_KEY", "")
        endpoint = f"{goal_agent_url}/agent/goal/chat"

        try:
            import httpx as _httpx
            _headers = {}
            if goal_agent_api_key:
                _headers["X-Internal-Token"] = goal_agent_api_key
            resp = _httpx.post(
                endpoint,
                json={
                    "customer_id": customer_no or "ANONYMOUS",
                    "message": message,
                },
                headers=_headers,
                timeout=30.0,
            )
            resp.raise_for_status()
            data = resp.json()

            need_more = data.get("need_more_info", False)
            if need_more:
                reply = data.get("follow_up_question") or data.get("message") or ""
                status = "NEED_INFO"
            else:
                reply = data.get("message") or ""
                status = "OK"

            # 다음 턴 세션 연속성을 위해 DB 세션 저장
            # savings_goal.py가 다음 "백만원" 같은 답변을 ASKED_MONTHLY로 처리할 수 있게 함
            if chatbot_consultation_id:
                goal_amount = _parse_amount(message)
                goal_months = _parse_months(message)

                def _upsert_goal_session(cid: int, stage: str, ga, gm) -> None:
                    obj = self.db.get(_GoalSession, cid)
                    if obj:
                        obj.stage = stage
                        obj.goal_amount = ga or obj.goal_amount
                        obj.goal_months = gm or obj.goal_months
                        obj.customer_no = customer_no or None
                        obj.monthly_surplus = None
                        obj.monthly_payment = None
                    else:
                        self.db.add(_GoalSession(
                            chatbot_consultation_id=cid, stage=stage,
                            goal_amount=ga or 0, goal_months=gm or 0,
                            customer_no=customer_no or None,
                        ))
                    self.db.flush()

                if not need_more and goal_amount and goal_months:
                    # 목표 확인 완료 → 다음 턴은 월납입 질문 답변
                    _upsert_goal_session(chatbot_consultation_id, "ASKED_MONTHLY", goal_amount, goal_months)
                    _agent_log.info(
                        "[SAVINGS_GOAL] agent_session_saved cid=%s stage=ASKED_MONTHLY goal=%s months=%s",
                        chatbot_consultation_id, goal_amount, goal_months,
                    )
                elif need_more:
                    # 아직 목표 수집 중 → ASKING_GOAL 저장
                    _upsert_goal_session(chatbot_consultation_id, "ASKING_GOAL", _parse_amount(message), _parse_months(message))
                    _agent_log.info(
                        "[SAVINGS_GOAL] agent_session_saved cid=%s stage=ASKING_GOAL",
                        chatbot_consultation_id,
                    )

            return ChatbotFeatureExecuteResponse(
                feature_code="SAVINGS_GOAL",
                status=status,
                message=reply,
                data=[],
            )
        except Exception as _exc:
            # fallback: 기존 savings_goal.py
            _log.getLogger(__name__).warning("Goal Agent 호출 실패, fallback: %s", _exc)
            return self._run_feature_for_intent_full(
                "SAVINGS_GOAL",
                message,
                customer_no=customer_no,
                chatbot_consultation_id=chatbot_consultation_id,
            )

    def _call_maturity_agent(
        self,
        message: str,
        customer_no: str | None,
        chatbot_consultation_id: int | None,
    ):
        """
        Maturity Agent(/agent/maturity/chat)를 HTTP로 호출한다.
        MATURITY_AGENT_ENABLED=true 인 경우에만 호출.
        실패 시 기존 MATURITY_MANAGEMENT feature executor로 fallback.
        """
        import os as _os
        import logging as _log
        from app.schemas import ChatbotFeatureExecuteResponse

        goal_agent_url = _os.getenv("GOAL_AGENT_URL", "http://host.docker.internal:8000")
        goal_agent_api_key = _os.getenv("GOAL_AGENT_API_KEY", "")
        endpoint = f"{goal_agent_url}/agent/maturity/chat"

        try:
            import httpx as _httpx
            _headers = {}
            if goal_agent_api_key:
                _headers["X-Internal-Token"] = goal_agent_api_key
            resp = _httpx.post(
                endpoint,
                json={
                    "customer_id": customer_no or "ANONYMOUS",
                    "message": message,
                },
                headers=_headers,
                timeout=30.0,
            )
            resp.raise_for_status()
            data = resp.json()

            need_more = data.get("need_more_info", False)
            if need_more:
                reply = data.get("follow_up_question") or data.get("message") or ""
                status = "NEED_INFO"
            else:
                reply = data.get("message") or ""
                if not reply:
                    # message 필드가 없는 경우 scenarios 요약 생성
                    maturities = data.get("maturing_contracts", [])
                    scenarios = data.get("scenarios", [])
                    if maturities:
                        target = maturities[0]
                        lines = [f"[만기 알림] '{target.get('product_name', '예금')}' 만기가 {target.get('days_until_maturity', 0)}일 후입니다!"]
                        if scenarios:
                            best = max(scenarios, key=lambda s: s.get("gain", 0))
                            lines.append(f"추천 시나리오: {best.get('label', '-')} (예상 이익 {best.get('gain', 0):,.0f}원)")
                        reply = "\n".join(lines)
                    else:
                        reply = "만기 예정 계약을 조회했습니다."
                status = "OK"

            return ChatbotFeatureExecuteResponse(
                feature_code="MATURITY_MANAGEMENT",
                status=status,
                message=reply,
                data=[],
            )
        except Exception as _exc:
            _log.getLogger(__name__).warning("Maturity Agent 호출 실패, fallback: %s", _exc)
            return self._run_feature_for_intent_full(
                "MATURITY_MANAGEMENT",
                message,
                customer_no=customer_no,
                chatbot_consultation_id=chatbot_consultation_id,
            )

    def _call_spending_agent(
        self,
        message: str,
        customer_no: str | None,
        chatbot_consultation_id: int | None,
    ):
        """
        Spending Agent(/agent/spending/chat)를 HTTP로 호출한다.
        SPENDING_AGENT_ENABLED=true 인 경우에만 호출.
        실패 시 기존 SpendingPatternAgent로 fallback.
        """
        import os as _os
        import logging as _log
        from app.schemas import ChatbotFeatureExecuteResponse

        goal_agent_url = _os.getenv("GOAL_AGENT_URL", "http://host.docker.internal:8000")
        goal_agent_api_key = _os.getenv("GOAL_AGENT_API_KEY", "")
        endpoint = f"{goal_agent_url}/agent/spending/chat"

        try:
            import httpx as _httpx
            _headers = {}
            if goal_agent_api_key:
                _headers["X-Internal-Token"] = goal_agent_api_key
            resp = _httpx.post(
                endpoint,
                json={
                    "customer_id": customer_no or "ANONYMOUS",
                    "message": message,
                },
                headers=_headers,
                timeout=_httpx.Timeout(connect=2.0, read=30.0, write=5.0, pool=5.0),
            )
            resp.raise_for_status()
            data = resp.json()

            need_more = data.get("need_more_info", False)
            if need_more:
                reply = data.get("follow_up_question") or data.get("message") or ""
                status = "NEED_INFO"
            else:
                reply = data.get("message") or ""
                if not reply:
                    anomalies = data.get("anomalies", [])
                    total = data.get("this_month_total", 0)
                    saving = data.get("total_estimated_saving", 0)
                    lines = [f"[지출 패턴] 이번 달 총 지출: {total:,.0f}원"]
                    if anomalies:
                        lines.append(f"이상 지출 {len(anomalies)}건 감지됨")
                    if saving > 0:
                        lines.append(f"절약 가능 금액: {saving:,.0f}원/월")
                    reply = "\n".join(lines)
                status = "OK"

            return ChatbotFeatureExecuteResponse(
                feature_code="SPENDING_PATTERN",
                status=status,
                message=reply,
                data=[],
            )
        except Exception as _exc:
            _log.getLogger(__name__).warning("Spending Agent 호출 실패, fallback: %s", _exc)
            return self._run_feature_for_intent_full(
                "SPENDING_PATTERN",
                message,
                customer_no=customer_no,
                chatbot_consultation_id=chatbot_consultation_id,
            )

    def _run_feature_for_intent_full(
        self,
        feature_code: str,
        message: str,
        customer_no: str | None = None,
        chatbot_consultation_id: int | None = None,
    ) -> "ChatbotFeatureExecuteResponse":
        """intent에 해당하는 feature를 실행해 전체 결과를 반환한다."""
        from app.schemas import ChatbotFeatureExecuteRequest
        req = ChatbotFeatureExecuteRequest(
            query=message,
            customer_no=customer_no,
            chatbot_consultation_id=chatbot_consultation_id,
        )
        return self.execute_feature(feature_code, req)

    def _get_customer_no(self, chatbot: "ChatbotConsultation") -> str | None:
        """챗봇 상담에서 고객번호를 조회한다."""
        consultation = self.db.get(Consultation, chatbot.consultation_id)
        return consultation.customer_no if consultation else None

    _PERSONAL_RECOMMEND_KEYWORDS = [
        "나한테", "나에게", "내게", "저한테", "저에게",
        "적합", "맞는", "맞춤", "추천", "나한", "나에게",
        "나한테 맞", "나에게 맞", "내 상황", "내 패턴",
        "어떤 게 나", "뭐가 나", "어느 게 나", "어느 쪽",
    ]

    def _has_personal_recommend_intent(self, text: str) -> bool:
        """개인 맞춤 추천 의도가 포함된 질문인지 확인한다."""
        return any(kw in text for kw in self._PERSONAL_RECOMMEND_KEYWORDS)

    def _get_intent(self, scenario_id: int | None, intent_name: str) -> ChatbotIntent | None:
        """intent_name으로 DB에서 챗봇의도 레코드를 조회한다."""
        return self.db.scalars(
            select(ChatbotIntent).where(
                ChatbotIntent.intent_name == intent_name,
                ChatbotIntent.active_yn == "Y",
                ChatbotIntent.scenario_id == scenario_id,
            )
        ).first()

    def _build_history_context(self, chatbot_consultation_id: int, max_turns: int = 5) -> str:
        """최근 대화 이력(사용자·챗봇 교대)을 LLM context 문자열로 변환한다.

        max_turns: 최근 N 턴(사용자+챗봇 쌍)을 포함.
        """
        rows = list(
            self.db.scalars(
                select(ChatMessageHistory)
                .where(ChatMessageHistory.chatbot_consultation_id == chatbot_consultation_id)
                .order_by(ChatMessageHistory.sequence_no.desc())
                .limit(max_turns * 2)
            ).all()
        )
        if not rows:
            return ""
        lines: list[str] = ["[대화 이력]"]
        for row in reversed(rows):
            label = "사용자" if row.sender_type_code_id == CODE_SENDER_USER else "챗봇"
            lines.append(f"{label}: {row.message_content}")
        return "\n".join(lines)


    def _ensure_default_intents(self, scenario_id: int) -> None:
        """챗봇의도 기본 레코드를 시딩한다."""
        intent_specs = [
            {"intent_name": "RATE_GUIDE",      "intent_desc": "금리/우대금리 안내",      "process_method_code_id": CODE_PROCESS_SCENARIO, "priority": 1},
            {"intent_name": "JOIN_CONDITION",   "intent_desc": "가입 조건 안내",          "process_method_code_id": CODE_PROCESS_SCENARIO, "priority": 2},
            {"intent_name": "PRODUCT_COMPARE",  "intent_desc": "상품 비교",               "process_method_code_id": CODE_PROCESS_SCENARIO, "priority": 3},
            {"intent_name": "TERMS_RAG",        "intent_desc": "약관/중도해지 안내",       "process_method_code_id": CODE_PROCESS_SCENARIO, "priority": 4},
            {"intent_name": "PRODUCT_GUIDE",       "intent_desc": "상품 목록/추천 안내",        "process_method_code_id": CODE_PROCESS_SCENARIO, "priority": 5},
            {"intent_name": "FAQ",               "intent_desc": "자주 묻는 질문",             "process_method_code_id": CODE_PROCESS_SCENARIO, "priority": 6},
            {"intent_name": "MATURITY_MANAGEMENT",   "intent_desc": "만기 운용 전략 안내",        "process_method_code_id": CODE_PROCESS_SCENARIO, "priority": 7},
            {"intent_name": "MATURITY_SCHEDULE",     "intent_desc": "만기 예정 일정 조회",        "process_method_code_id": CODE_PROCESS_SCENARIO, "priority": 7},
            {"intent_name": "REINVESTMENT_RECOMMEND","intent_desc": "만기 재투자 상품 추천",       "process_method_code_id": CODE_PROCESS_SCENARIO, "priority": 7},
            {"intent_name": "SPENDING_PATTERN",      "intent_desc": "지출 패턴 이상 감지 및 경고", "process_method_code_id": CODE_PROCESS_SCENARIO, "priority": 7},
            {"intent_name": "CASH_FLOW_RECOMMEND","intent_desc": "현금흐름 기반 상품 추천",    "process_method_code_id": CODE_PROCESS_LLM,      "priority": 7},
            {"intent_name": "LLM_FALLBACK",      "intent_desc": "LLM 자유 응답",              "process_method_code_id": CODE_PROCESS_LLM,      "priority": 8},
            {"intent_name": "STAFF_REQUEST",     "intent_desc": "상담사 이관",                "process_method_code_id": CODE_PROCESS_LLM,      "priority": 9},
            {"intent_name": "STAFF_ERROR_FALLBACK", "intent_desc": "오류로 인한 상담사 이관", "process_method_code_id": CODE_PROCESS_LLM,      "priority": 10},
        ]
        for spec in intent_specs:
            exists = self.db.scalars(
                select(ChatbotIntent).where(
                    ChatbotIntent.intent_name == spec["intent_name"],
                    ChatbotIntent.scenario_id == scenario_id,
                )
            ).first()
            if not exists:
                self.db.add(ChatbotIntent(
                    scenario_id=scenario_id,
                    intent_name=spec["intent_name"],
                    intent_desc=spec["intent_desc"],
                    process_method_code_id=spec["process_method_code_id"],
                    confidence_threshold=70,
                    priority=spec["priority"],
                    test_yn="N",
                    active_yn="Y",
                ))


# ──────────────────────────────────────────────────────────────────────────────
# 인간 상담원 채팅 서비스
# ──────────────────────────────────────────────────────────────────────────────

CODE_SENDER_USER = 1
CODE_SENDER_BOT = 2
CODE_SENDER_AGENT = 3

_SENDER_LABEL = {
    CODE_SENDER_USER: "USER",
    CODE_SENDER_BOT: "BOT",
    CODE_SENDER_AGENT: "AGENT",
}


def _chat_status(chat: ChatConsultation) -> str:
    if chat.active_yn == "N":
        return "ENDED"
    if chat.agent_connected_at:
        return "CONNECTED"
    return "WAITING"


class ChatService:
    """상담사 채팅 상담 관리 서비스.

    흐름:
      1. ChatbotService 가 상담사 이관을 감지하면 ChatConsultation 생성 (WAITING)
      2. 상담사가 get_waiting_queue() 로 목록 확인
      3. connect_agent() 로 수락 → CONNECTED
      4. send_message() 로 양방향 메시지 교환
      5. end_chat() 로 종료 → ENDED

    Kafka 이벤트:
      - AgentTransferRequested  (chatbot_service 에서 이미 발행)
      - AgentConnected
      - ChatMessageSent
      - ChatEnded
    """

    def __init__(self, db: Session, events: KafkaEventPublisher):
        self.db = db
        self.events = events

    # ── 조회 ────────────────────────────────────────────────────────────────

    def get_waiting_queue(self) -> list[dict[str, Any]]:
        """상담사 수락 대기 중인 채팅 목록 (JOIN consultation 으로 customer_no 포함)."""
        rows = self.db.execute(
            select(
                ChatConsultation.chat_consultation_id,
                ChatConsultation.consultation_id,
                ChatConsultation.chatbot_consultation_id,
                ChatConsultation.agent_requested_at.label("waiting_since"),
                Consultation.customer_no,
            )
            .join(Consultation, Consultation.consultation_id == ChatConsultation.consultation_id)
            .where(
                ChatConsultation.active_yn == "Y",
                ChatConsultation.agent_connected_at.is_(None),
                ChatConsultation.agent_requested_at.is_not(None),
            )
            .order_by(ChatConsultation.agent_requested_at)
        )
        return [dict(row._mapping) for row in rows]

    def get_consultation(self, chat_consultation_id: int) -> ChatConsultation:
        chat = self.db.get(ChatConsultation, chat_consultation_id)
        if not chat:
            raise ValueError(f"채팅 상담을 찾을 수 없습니다. id={chat_consultation_id}")
        return chat

    def get_messages(self, chat_consultation_id: int) -> list[ChatMessageHistory]:
        """챗봇 메시지 + 상담사 메시지를 통합하여 시간 순으로 반환."""
        chat = self.get_consultation(chat_consultation_id)

        if chat.chatbot_consultation_id:
            condition = or_(
                ChatMessageHistory.chat_consultation_id == chat_consultation_id,
                ChatMessageHistory.chatbot_consultation_id == chat.chatbot_consultation_id,
            )
        else:
            condition = ChatMessageHistory.chat_consultation_id == chat_consultation_id

        return list(
            self.db.scalars(
                select(ChatMessageHistory)
                .where(condition)
                .order_by(ChatMessageHistory.chat_message_history_id)
            ).all()
        )

    def get_history(self, limit: int = 50, customer_no: str | None = None) -> list[dict]:
        from sqlalchemy import func as _func
        q = (
            select(
                ChatConsultation.chat_consultation_id,
                ChatConsultation.consultation_id,
                Consultation.customer_no,
                ChatConsultation.employee_id,
                ChatConsultation.agent_requested_at,
                ChatConsultation.agent_connected_at,
                ChatConsultation.chat_ended_at,
                ChatConsultation.satisfaction_score,
                _func.count(ChatMessageHistory.chat_message_history_id).label("message_count"),
            )
            .join(Consultation, Consultation.consultation_id == ChatConsultation.consultation_id)
            .outerjoin(
                ChatMessageHistory,
                ChatMessageHistory.chat_consultation_id == ChatConsultation.chat_consultation_id,
            )
            .group_by(
                ChatConsultation.chat_consultation_id,
                ChatConsultation.consultation_id,
                Consultation.customer_no,
                ChatConsultation.employee_id,
                ChatConsultation.agent_requested_at,
                ChatConsultation.agent_connected_at,
                ChatConsultation.chat_ended_at,
                ChatConsultation.satisfaction_score,
            )
            .order_by(ChatConsultation.chat_consultation_id.desc())
            .limit(limit)
        )
        if customer_no:
            q = q.where(Consultation.customer_no == customer_no)

        rows = self.db.execute(q).mappings().all()
        result = []
        for r in rows:
            if r["chat_ended_at"]:
                status = "ENDED"
            elif r["agent_connected_at"]:
                status = "CONNECTED"
            elif r["agent_requested_at"]:
                status = "WAITING"
            else:
                status = "UNKNOWN"
            result.append({**dict(r), "status": status})
        return result

    def request_agent_chat(self, customer_no: str) -> ChatConsultation:
        """고객이 직접 상담사 채팅을 요청한다. 이미 활성 요청이 있으면 그것을 반환한다."""
        from app.models import Consultation as _Consultation

        # 이미 WAITING(미연결) 상태인 요청이 있으면 재사용
        existing = self.db.scalars(
            select(ChatConsultation)
            .join(Consultation, Consultation.consultation_id == ChatConsultation.consultation_id)
            .where(
                Consultation.customer_no == customer_no,
                ChatConsultation.active_yn == "Y",
                ChatConsultation.agent_connected_at.is_(None),
                ChatConsultation.chat_ended_at.is_(None),
            )
            .order_by(ChatConsultation.chat_consultation_id.desc())
        ).first()
        if existing:
            return existing

        consultation = _Consultation(customer_no=customer_no)
        self.db.add(consultation)
        self.db.flush()

        now = datetime.now(timezone.utc)
        chat = ChatConsultation(
            consultation_id=consultation.consultation_id,
            chatbot_consultation_id=None,
            employee_id=None,
            agent_requested_at=now,
            agent_connected_at=None,
            active_yn="Y",
        )
        self.db.add(chat)
        self.db.commit()
        self.db.refresh(chat)
        return chat

    # ── 상태 변경 ────────────────────────────────────────────────────────────

    async def connect_agent(self, chat_consultation_id: int, employee_id: int) -> ChatConsultation:
        """상담사가 대기 중인 상담을 수락한다.

        Kafka: AgentConnected 이벤트 발행 (consultation.chat.events)
        """
        chat = self.get_consultation(chat_consultation_id)
        if chat.agent_connected_at:
            raise ValueError("이미 상담사가 연결된 상담입니다.")

        now = datetime.now(timezone.utc)
        chat.employee_id = employee_id
        chat.agent_connected_at = now
        chat.chat_started_at = now
        if chat.agent_requested_at:
            requested_at = chat.agent_requested_at
            if requested_at.tzinfo is None:
                requested_at = requested_at.replace(tzinfo=timezone.utc)
            delta = (now - requested_at).total_seconds()
            chat.waiting_seconds = int(delta)

        self.db.commit()
        self.db.refresh(chat)

        consultation = self.db.get(Consultation, chat.consultation_id)
        customer_no = consultation.customer_no if consultation else "UNKNOWN"

        await self.events.publish_chat(
            "AgentConnected",
            {
                "chatConsultationId": chat_consultation_id,
                "consultationId": chat.consultation_id,
                "employeeId": employee_id,
                "customerNo": customer_no,
            },
        )
        return chat

    async def send_message(
        self,
        chat_consultation_id: int,
        message: str,
        sender_type_code_id: int,
    ) -> ChatMessageHistory:
        """상담사 또는 고객이 메시지를 전송한다.

        sender_type_code_id: 1=USER, 2=BOT, 3=AGENT
        Kafka: ChatMessageSent 이벤트 발행
        """
        chat = self.get_consultation(chat_consultation_id)
        if chat.active_yn == "N":
            raise ValueError("이미 종료된 상담입니다.")

        last_seq = self.db.execute(
            select(ChatMessageHistory.sequence_no)
            .where(ChatMessageHistory.chat_consultation_id == chat_consultation_id)
            .order_by(ChatMessageHistory.sequence_no.desc())
            .limit(1)
        ).scalar_one_or_none()

        msg = ChatMessageHistory(
            chat_consultation_id=chat_consultation_id,
            chatbot_consultation_id=None,          # 상담사 메시지는 chatbot_id 미설정
            sequence_no=(last_seq or 0) + 1,
            sender_type_code_id=sender_type_code_id,
            message_type_code_id=CODE_MESSAGE_TYPE_TEXT,
            message_content=message,
            process_method_code_id=None,
        )
        self.db.add(msg)
        chat.total_turn_count += 1
        self.db.commit()
        self.db.refresh(msg)

        await self.events.publish_chat(
            "ChatMessageSent",
            {
                "chatConsultationId": chat_consultation_id,
                "senderType": _SENDER_LABEL.get(sender_type_code_id, "UNKNOWN"),
                "message": message,
            },
        )
        return msg

    async def end_chat(
        self,
        chat_consultation_id: int,
        satisfaction_score: int | None = None,
    ) -> ChatConsultation:
        """상담을 종료한다.

        Kafka: ChatEnded 이벤트 발행
        """
        chat = self.get_consultation(chat_consultation_id)
        if chat.active_yn == "N":
            # 이미 종료된 상담이라도 만족도 점수만 업데이트 허용
            if satisfaction_score is not None:
                chat.satisfaction_score = satisfaction_score
                self.db.commit()
                return chat
            raise ValueError("이미 종료된 상담입니다.")

        now = datetime.now(timezone.utc)
        chat.chat_ended_at = now
        chat.active_yn = "N"
        if satisfaction_score is not None:
            chat.satisfaction_score = satisfaction_score
        if chat.chat_started_at:
            started_at = chat.chat_started_at
            if started_at.tzinfo is None:
                started_at = started_at.replace(tzinfo=timezone.utc)
            delta = (now - started_at).total_seconds()
            chat.chat_seconds = int(delta)

        consultation = self.db.get(Consultation, chat.consultation_id)
        if consultation:
            consultation.completed_at = now

        self.db.commit()
        self.db.refresh(chat)

        await self.events.publish_chat(
            "ChatEnded",
            {
                "chatConsultationId": chat_consultation_id,
                "consultationId": chat.consultation_id,
                "satisfactionScore": satisfaction_score,
            },
        )
        return chat
