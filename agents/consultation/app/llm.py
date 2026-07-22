# ──────────────────────────────────────────────────────────────────────────────
# Intent 분류
# ──────────────────────────────────────────────────────────────────────────────

# 우선순위 순서 — 앞쪽이 먼저 매칭됨
# CASH_FLOW_RECOMMEND 는 PRODUCT_GUIDE 보다 앞에 위치해야 한다
# (더 구체적인 "내 패턴/현금흐름 분석 기반" 의도이므로)
_INTENT_PRIORITY: list[str] = [
    "SAVINGS_GOAL",
    "CONTRACT_STATUS",       # 개인 보유 상품 조회 — PRODUCT_GUIDE보다 최우선
    "REINVESTMENT_RECOMMEND",
    "MATURITY_MANAGEMENT",
    "MATURITY_SCHEDULE",
    "SPENDING_PATTERN",
    "MY_ACCOUNTS",
    "INTEREST_HISTORY",
    "PRODUCT_SEARCH",
    "RATE_GUIDE",
    "JOIN_CONDITION",
    "PRODUCT_COMPARE",
    "TERMS_RAG",
    "CASH_FLOW_RECOMMEND",
    "PRODUCT_GUIDE",
    "FAQ",
]

_INTENT_KEYWORDS: dict[str, list[str]] = {
    "SPENDING_PATTERN": [
        "지출 패턴", "소비 패턴", "지출 분석", "소비 분석",
        "얼마나 썼", "얼마 썼", "돈 어디에 썼", "돈 어디 썼", "돈을 어디에",
        "이번 달 지출", "이번달 지출", "월별 지출", "지출 내역",
        "소비 현황", "지출 현황", "과소비", "지출이 늘", "지출 늘었",
        "많이 썼", "너무 썼", "지출 경고", "지출 알림",
        "지출", "소비",
        # 거래·사용 내역 커버리지 추가
        "거래 내역", "거래내역", "거래 조회", "이번달 거래", "이번 달 거래",
        "사용 내역", "사용내역", "이번달 사용", "이번 달 사용",
        "입출금 내역", "입출금내역", "입출금 조회",
        "카드 내역", "결제 내역", "내역 조회", "내역 보여",
        # 소비 장소·채널 키워드
        "편의점", "카페", "스타벅스", "배달", "배달앱",
        "무신사", "쇼핑", "콘서트", "티켓",
        # 소비 행위 표현
        "썼는데", "썼어", "쓴 것", "쓴거", "사용했어", "결제했어",
        "샀는데", "샀어", "사버렸", "쓰고",
        # 소비 이상 감지 표현
        "이상한 거 있어", "이상한거 있어", "좀 많아", "좀 많긴", "많긴 해",
        "패턴", "이번 주 소비", "이번 달 소비", "이번달 소비",
    ],
    "REINVESTMENT_RECOMMEND": [
        "재투자", "재예치", "재가입", "다시 가입",
        "만기 추천", "만기 상품 추천", "만기 운용 추천",
        "어디에 넣", "어디다 넣", "어디에 재",
    ],
    "MATURITY_MANAGEMENT": [
        "만기 후 어떻게", "만기되면 어떻게", "만기 상품 어떻게", "만기 되면 어떻게",
        "만기됐", "만기 됐", "만기됐는데", "만기 됐는데",
        "만기되면", "만기 되면", "만기 후",
        "만기 운용", "갱신", "자동 갱신",
    ],
    "MATURITY_SCHEDULE": [
        "만기일", "만기 예정", "만기 언제", "만기일 언제",
        "곧 만기", "다음 달 만기", "이번 달 만기", "만기 상품",
        "만기 알려", "만기 확인", "만기 조회", "만기 내역",
        "언제 만기", "만기 날짜", "만기 일정",
        "만기 남은", "만기 빠른", "빨리 만기", "빨리 끝나는",
        "만기 순서", "만기 정렬", "만기 가까운", "곧 끝나는",
        "곧 끝남", "끝나가는", "끝나는 상품", "만기 다가오는", "만료 임박",
        "일 남은", "개월 남은", "달 남은", "이내 만기",
        "만기 이내", "만기 이하", "만기 이전",
    ],
    "SAVINGS_GOAL": [
        "모으고", "모으려", "모아야", "모을래", "모을게", "모아볼", "모아서",
        "모아야", "모아두", "모으면", "모으나", "모을 수", "모을수",
        "저축 목표", "저축목표", "목표 금액", "목표금액",
        "저축 계획", "저축계획", "얼마 모으", "얼마나 모으",
        "몇 개월이면", "몇년이면",
        "만원 목표", "억 목표", "천만원 목표", "원 목표",
        "개월안에", "개월 안에", "년안에", "년 안에", "달안에", "달 안에",
        "목표 달성", "목표로", "목표 세워", "목표를 세",
        "원 모으", "만원 모", "억 모",
    ],
    "CONTRACT_STATUS": [
        "계약 현황", "계약현황", "계약 조회", "계약조회", "계약 확인",
        "가입 상품 조회", "가입상품조회", "내 계약", "내계약",
        "가입 계약", "계약 목록", "계약 내역",
        # 개인 보유 맥락
        "가입한", "내가 가입한", "내 상품 뭐", "보유 상품",
        "내가 가진", "내 금융 상품", "내 상품", "보유한 상품",
        "내가 보유", "내가 가입", "가입한 상품",
        # 자산/돈 상태 표현
        "내 돈", "돈 어디", "자금 어디", "보유 자금",
        "묶여", "잠겨", "잠김", "사용 못", "어디 묶",
        "돈이 어디", "내 자금", "자금 현황",
    ],
    "MY_ACCOUNTS": [
        "내 계좌", "내계좌", "계좌 보여", "계좌 조회", "계좌조회",
        "계좌 목록", "계좌 알려", "내 통장", "통장 보여", "잔액 조회",
        "잔액 보여", "잔액 알려", "내 잔액",
        # 단독·짧은 표현 추가
        "계좌 뭐", "계좌 어떤", "계좌 있", "계좌 알려줘", "계좌 보여줘",
        "계좌 확인", "통장 알려", "통장 조회", "통장 확인", "통장 목록",
        "내 통장 뭐", "잔액", "계좌",
    ],
    "INTEREST_HISTORY": [
        "이자 내역", "이자내역", "이자 조회", "이자 확인", "이자 얼마 받",
        "이자 받은", "이자 지급", "이자 내역 보여", "이자 내역 알려",
        "이번 달 이자", "이자 내역 조회",
    ],
    "PRODUCT_SEARCH": [
        "이상 상품", "이하 상품", "% 이상", "% 이하",
        "금리 높은 상품", "높은 금리 상품", "금리 낮은 상품", "낮은 금리 상품",
        "금리 3", "금리 2", "금리 4", "금리 5",
        "상품 검색", "상품 찾아", "조건 맞는 상품", "조건에 맞는 상품",
        "이상인 상품", "이하인 상품", "넘는 상품", "초과하는 상품",
    ],
    "RATE_GUIDE": [
        "금리 목록", "금리 보여", "금리 알려줘", "이자율 알려", "금리 현황",
        "금리 알려", "금리 어떻게", "금리가 어떻게", "금리가 얼마", "금리 얼마",
        "이자 얼마", "이자율이", "금리 비교", "어떤 금리", "금리 정보",
        "모든 금리", "전체 금리", "상품 금리", "금리 보여줘",
        "금리 말해줘", "금리들", "금리 다", "금리 전부",
        "우대금리", "우대 금리", "우대금리 조건", "우대금리 알려",
        # "금리가 뭐야", "금리란" 등 기본 질의 커버리지 추가
        "금리가 뭐", "금리 뭐야", "금리란", "금리가 무슨", "금리 궁금",
        "금리가", "이자율", "연이율", "연 이율",
    ],
    "JOIN_CONDITION": [
        "가입 조건", "가입조건", "가입 자격", "가입 대상",
        "가입할 수 있", "가입 가능", "가입이 가능",
    ],
    "PRODUCT_COMPARE": [
        "비교해줘", "비교해 줘", "어떻게 달라",
        "차이", "차이가", "차이점", "다른 점", "다른점", "다른가", "다른지",
        "뭐가 달라", "뭐가 다른", "어떻게 다른", "차이를 알려", "차이 알려",
        "무슨 차이", "어떤 차이", "어떤 점이 다른",
    ],
    "TERMS_RAG": [
        "약관", "중도해지", "수수료",
    ],
    "CASH_FLOW_RECOMMEND": [
        "내 패턴", "현금흐름 분석", "내 소비 패턴", "내 거래 패턴",
        "나한테 맞는", "나에게 맞는", "분석해서 추천", "패턴 분석",
        "내 상황에 맞는", "내 상황에 적합", "맞춤 추천",
        "장점순", "유리한 순", "좋은 순", "추천 순", "랭킹", "순위대로",
        "순위별", "순위별로", "순서별", "1위부터", "상품 순위", "추천 순위",
        "어떤 게 좋아", "어떤게 좋아", "뭐가 좋아", "뭐가 나아", "뭐가 더 좋",
        "어느 게 좋", "어느게 좋", "어떤 상품이 좋",
        "뭐가 더 적합", "어떤 게 적합", "어느 게 적합", "어느 쪽이 적합",
        "뭐가 좋을지", "어느 것이 좋", "어느 게 더", "어떤 게 더",
        "뭐가 더 나", "어떤 게 나", "어느 게 나",
        "무엇이 좋", "무엇이 더", "무엇이 적합",
    ],
    "PRODUCT_GUIDE": [
        "상품 목록", "상품 보여줘", "예금 상품 알려", "적금 상품 알려", "청약 상품 알려",
        "적금 종류", "예금 종류", "청약 종류", "상품 종류", "종류 알려", "어떤 종류",
        "적금 뭐가 있", "예금 뭐가 있", "청약 뭐가 있", "어떤 상품 있", "상품 뭐가 있",
        "적금 알려줘", "예금 알려줘", "청약 알려줘",
        "적금 목록", "예금 목록", "청약 목록",
        "적금 소개", "예금 소개", "상품 소개",
        "상품 추천", "상품 있", "예금 상품 있", "적금 상품 있",
    ],
    "FAQ": [
        "자주 묻는", "faq", "FAQ",
    ],
}


_PRODUCT_TYPES = ["예금", "적금", "청약", "저축"]
_PERSONAL_RECOMMEND_WORDS = [
    "나한테", "나에게", "내게", "내한테", "저한테", "저에게",
    "나한", "나에", "나는", "저는", "내가",
]
_RECOMMEND_WORDS = [
    "맞는", "맞춰", "맞춤", "적합", "적합한", "알맞", "알맞은",
    "추천", "좋은", "좋아", "좋아요", "나은", "낫", "유리",
    "어울리", "어울려", "맞아", "맞지", "골라", "선택",
]


class IntentClassifier:
    """키워드 기반 우선순위 intent 분류기."""

    def classify(self, message: str) -> str | None:
        msg = message.lower()

        has_personal = any(w in msg for w in _PERSONAL_RECOMMEND_WORDS)
        has_recommend = any(w in msg for w in _RECOMMEND_WORDS)

        import re as _re

        # 만기 날짜 필터 패턴 선처리
        if _re.search(r"만기.{0,6}(이내|이하|이전|남은|까지)", msg) or _re.search(r"\d+[일달월].{0,4}(이내|이하|남은).{0,4}만기", msg):
            return "MATURITY_SCHEDULE"

        # 자산 상태 의미 기반 선처리 — "내/보유/가입" + "돈/자금/상품" 또는 "묶임/잠김" 포함
        _OWNERSHIP = ["내", "내가", "보유", "가입"]
        _ASSET = ["돈", "자금", "상품", "금액"]
        _LOCKED = ["묶여", "잠겨", "잠김", "어디 있", "어디있", "어디 묶", "사용 못"]
        if any(lk in msg for lk in _LOCKED):
            return "CONTRACT_STATUS"
        if any(ow in msg for ow in _OWNERSHIP) and any(at in msg for at in _ASSET):
            return "CONTRACT_STATUS"

        # 개인 소유 맥락 키워드 — PRODUCT_GUIDE 제외 판정에 사용
        _PERSONAL_OWNERSHIP = ["내가 가입", "가입한", "보유 상품", "보유한", "내 상품", "내 금융", "내가 가진"]
        has_personal_ownership = any(w in msg for w in _PERSONAL_OWNERSHIP)

        # 1. 등록된 키워드 매칭 — 단, PRODUCT_COMPARE/PRODUCT_GUIDE는 개인 소유 맥락 제외
        for feature_code in _INTENT_PRIORITY:
            if any(kw in msg for kw in _INTENT_KEYWORDS[feature_code]):
                # 비교 질문 + 개인 추천 의도: 명시적 비교 키워드("비교", "차이")가 있으면 PRODUCT_COMPARE 유지
                if feature_code == "PRODUCT_COMPARE" and (has_personal or has_recommend):
                    has_explicit_compare = any(w in msg for w in ["비교", "차이", "다른 점", "다른점"])
                    if not has_explicit_compare:
                        return "CASH_FLOW_RECOMMEND"
                # PRODUCT_GUIDE: 개인 소유 맥락("가입한", "보유", "내 상품") → CONTRACT_STATUS로 강제
                if feature_code == "PRODUCT_GUIDE" and has_personal_ownership:
                    return "CONTRACT_STATUS"
                return feature_code

        has_product = any(p in msg for p in _PRODUCT_TYPES)
        has_personal = any(w in msg for w in _PERSONAL_RECOMMEND_WORDS)
        has_recommend = any(w in msg for w in _RECOMMEND_WORDS)

        # 2. "둘 중" / "어느 쪽" / "뭐가 더" 비교 후속 질문 → 이전 대화 맥락으로 현금흐름 추천
        _COMPARE_FOLLOW_UP = [
            "둘 중", "어느 쪽", "어느쪽", "어떤 쪽", "어떤쪽", "둘다", "둘 다",
            "그 중", "그중", "이 중", "이중",
            "뭐가 더 나", "뭐가 더 적합", "뭐가 더 좋", "뭐가 더 유리",
            "어떤 게 더 나", "어느 게 더 나", "더 나은 게", "더 적합한 게",
            "하나만", "딱 하나", "하나 골라", "하나 추천",
        ]
        if any(w in msg for w in _COMPARE_FOLLOW_UP):
            # "비교"가 명시적으로 있으면 PRODUCT_COMPARE로 처리
            if any(w in msg for w in ["비교", "차이", "다른 점"]):
                return "PRODUCT_COMPARE"
            return "CASH_FLOW_RECOMMEND"

        # 3. "예금"과 "적금"이 같이 나올 때: 추천 의도가 있을 때만 CASH_FLOW_RECOMMEND
        #    (차이/비교 질문은 이미 위 PRODUCT_COMPARE 키워드에서 처리됨)
        if "예금" in msg and "적금" in msg and (has_personal or has_recommend):
            return "CASH_FLOW_RECOMMEND"

        # 4. 패턴 기반: 상품 + 나한테/나에게 + 맞는/적합/추천 조합
        if has_product and has_personal and has_recommend:
            return "CASH_FLOW_RECOMMEND"

        # 5. 상품 단독 입력 ("적금", "예금", "청약") → 상품 목록 안내
        _PRODUCT_ONLY = ["예금", "적금", "청약"]
        if any(msg.strip() == p or msg.strip().startswith(p + " ") or msg.strip().endswith(" " + p)
               for p in _PRODUCT_ONLY):
            return "PRODUCT_GUIDE"

        # 6. 토큰 기반 파편 입력 처리 ─────────────────────────────────────────
        # "돈 자산 가입", "계좌 상품" 등 단어 나열 형태를 토큰 단위로 분류
        tokens = set(_re.split(r"[\s,./·\-]+", msg))
        tokens.discard("")

        # 토큰 → intent 우선순위 매핑 (CONTRACT_STATUS > MY_ACCOUNTS > 기타)
        _TOKEN_INTENT: list[tuple[set[str], str]] = [
            ({"묶여", "잠겨", "잠김"},                                              "CONTRACT_STATUS"),
            ({"계약", "가입", "자산", "보유"},                                       "CONTRACT_STATUS"),
            ({"돈", "자금"},                                                         "CONTRACT_STATUS"),
            ({"계좌", "통장", "잔액"},                                               "MY_ACCOUNTS"),
            ({"만기"},                                                               "MATURITY_SCHEDULE"),
            ({"이자"},                                                               "INTEREST_HISTORY"),
            ({"금리"},                                                               "RATE_GUIDE"),
            ({"지출", "소비", "거래"},                                               "SPENDING_PATTERN"),
            ({"상품", "예금", "적금"},                                               "PRODUCT_GUIDE"),
        ]

        # CONTRACT_STATUS 파편 입력 특별 처리:
        # "자산", "가입", "보유", "계좌", "상품" 중 하나 + "돈"/"자금" 조합이면 CONTRACT_STATUS
        _CONTRACT_TRIGGERS  = {"자산", "가입", "보유", "계약"}
        _ASSET_TOKENS       = {"돈", "자금", "금액"}
        _FALLBACK_BLOCK     = {"돈", "자산", "계좌", "가입", "상품", "보유"}  # fallback 금지 토큰

        if tokens & _CONTRACT_TRIGGERS or (tokens & _ASSET_TOKENS and tokens & {"계좌", "상품", "가입", "보유"}):
            return "CONTRACT_STATUS"

        _MATURITY_TOKENS = {"만기", "끝나는", "끝나가는", "끝남", "만료", "만기일"}
        for trigger_tokens, intent in _TOKEN_INTENT:
            if tokens & trigger_tokens:
                if intent == "PRODUCT_GUIDE" and tokens & _MATURITY_TOKENS:
                    return "MATURITY_SCHEDULE"
                return intent

        # 7. fallback 방지: 금융 토큰이 하나라도 있으면 MY_ACCOUNTS로 best-effort
        if tokens & _FALLBACK_BLOCK:
            return "MY_ACCOUNTS"

        return None


# ──────────────────────────────────────────────────────────────────────────────
# DB 데이터 → 자연어 포맷터
# ──────────────────────────────────────────────────────────────────────────────

class FeatureAnswerFormatter:
    """feature 실행 결과(DB 데이터)를 고객 응답 텍스트로 포맷팅."""

    def format(self, feature_code: str, data: list[dict]) -> str:
        if not data:
            return "조회된 정보가 없습니다."

        handlers = {
            "RATE_GUIDE":        self._rate,
            "PRODUCT_GUIDE":     self._products,
            "JOIN_CONDITION":    self._join_condition,
            "PRODUCT_COMPARE":   self._compare,
            "TERMS_RAG":         self._terms,
            "MY_ACCOUNTS":       self._my_accounts,
            "INTEREST_HISTORY":  self._interest_history,
            "CONTRACT_STATUS":   self._contract_status,
        }
        handler = handlers.get(feature_code)
        if handler:
            return handler(data)
        # 등록되지 않은 feature는 데이터만 간단히 표시 (상담사 연결 유도 금지)
        return self._generic_list(data)

    _TYPE_KO = {"DEPOSIT": "예금", "SAVINGS": "적금", "SUBSCRIPTION": "청약"}
    _NA = "미표기"

    def _product_row(self, name: str, rate: object, term: object, ptype: object) -> str:
        """금리·만기·유형 3필드 강제 출력 공통 렌더러.

        값이 없거나 None이면 _NA("미표기")로 채운다.
        부분 출력 없이 항상 3필드 세트를 반환한다.
        """
        rate_str  = f"{rate}%" if rate not in (None, "", 0) else self._NA
        term_str  = str(term)  if term not in (None, "")    else self._NA
        type_str  = self._TYPE_KO.get(str(ptype), str(ptype)) if ptype not in (None, "") else self._NA
        return (
            f"  - 금리: {rate_str}\n"
            f"  - 만기: {term_str}\n"
            f"  - 계좌/상품 유형: {type_str}"
        )

    def _rate(self, data: list[dict]) -> str:
        from collections import defaultdict
        lines = ["[상품별 금리 안내]\n"]
        grouped: dict = defaultdict(list)
        for row in data:
            grouped[row.get("product_name", self._NA)].append(row)
        for name, rows in grouped.items():
            base_rows = [r for r in rows if r.get("rate_type") == "BASE"]
            pref_rows = [r for r in rows if r.get("rate_type") != "BASE"]
            base  = base_rows[0] if base_rows else rows[0]
            rate  = base.get("interest_rate") or base.get("base_interest_rate")
            min_m = base.get("minimum_contract_period") or base.get("min_period_month")
            max_m = base.get("maximum_contract_period") or base.get("max_period_month")
            ptype = base.get("product_type") or base.get("deposit_product_type")
            term  = f"{min_m}~{max_m}개월" if (min_m and max_m) else None
            if pref_rows:
                pref_rates = [r.get("interest_rate", 0) for r in pref_rows if r.get("interest_rate")]
                pref_note  = f" (우대금리 최대 {max(str(x) for x in pref_rates)}%)" if pref_rates else ""
            else:
                pref_note = ""
            lines.append(f"- {name}{pref_note}")
            lines.append(self._product_row(name="", rate=rate, term=term, ptype=ptype))
        lines.append("\n상세 조건은 영업점 또는 앱에서 확인해 주세요.")
        return "\n".join(lines)

    def _products(self, data: list[dict]) -> str:
        is_personalized = any("match_score" in row for row in data)
        header = "[맞춤 상품 추천]\n" if is_personalized else "[판매 중인 상품 목록]\n"
        lines = [header]
        for row in data:
            name  = row.get("product_name") or row.get("deposit_product_name") or self._NA
            ptype = row.get("product_type") or row.get("deposit_product_type")
            rate  = row.get("base_interest_rate") or row.get("interest_rate")
            min_m = row.get("min_period_month") or row.get("minimum_contract_period")
            max_m = row.get("max_period_month") or row.get("maximum_contract_period")
            term  = f"{min_m}~{max_m}개월" if (min_m and max_m) else None
            reason = row.get("recommend_reason", "")
            score  = row.get("match_score", "")
            lines.append(f"- {name}")
            lines.append(self._product_row(name="", rate=rate, term=term, ptype=ptype))
            if is_personalized and reason:
                lines.append(f"  ✓ {reason} (적합도 {score}점)")
        lines.append("\n특정 상품에 대해 더 알고 싶으시면 질문해 주세요.")
        return "\n".join(lines)

    def _join_condition(self, data: list[dict]) -> str:
        lines = ["[가입 조건 안내]\n"]
        for row in data[:5]:
            name      = row.get("product_name", self._NA)
            min_amt   = row.get("min_join_amount", "")
            max_amt   = row.get("max_join_amount", "")
            min_month = row.get("min_period_month", "")
            max_month = row.get("max_period_month", "")
            ptype     = row.get("product_type") or row.get("deposit_product_type")
            rate      = row.get("base_interest_rate") or row.get("interest_rate")
            term      = f"{min_month}~{max_month}개월" if (min_month and max_month) else None
            early     = "가능" if row.get("is_early_termination_allowed") else "불가"
            tax       = "있음" if row.get("is_tax_benefit_available") else "없음"
            amt_str   = (
                f"{int(min_amt):,}원~{int(max_amt):,}원"
                if isinstance(min_amt, int) else
                f"{min_amt}원~{max_amt}원"
            ) if min_amt else self._NA
            lines.append(f"- {name}")
            lines.append(self._product_row(name="", rate=rate, term=term, ptype=ptype))
            lines.append(f"  - 가입금액: {amt_str} / 중도해지: {early} / 세제혜택: {tax}")
        return "\n".join(lines)

    def _compare(self, data: list[dict]) -> str:
        lines = ["[상품 비교]\n"]
        for row in data[:5]:
            name  = row.get("product_name", self._NA)
            ptype = row.get("product_type") or row.get("deposit_product_type")
            rate  = row.get("base_interest_rate") or row.get("interest_rate")
            min_m = row.get("min_period_month") or row.get("minimum_contract_period")
            max_m = row.get("max_period_month") or row.get("maximum_contract_period")
            term  = f"{min_m}~{max_m}개월" if (min_m and max_m) else None
            lines.append(f"- {name}")
            lines.append(self._product_row(name="", rate=rate, term=term, ptype=ptype))
        return "\n".join(lines)

    def _terms(self, data: list[dict]) -> str:
        lines = ["[약관 검색 결과]\n"]
        for row in data[:3]:
            name    = row.get("special_term_name", "")
            summary = row.get("special_term_summary", "")
            lines.append(f"- {name}")
            if summary:
                lines.append(f"  {summary}")
        lines.append("\n전체 약관은 상품 설명서를 참조해 주세요.")
        return "\n".join(lines)

    _ACCOUNT_TYPE_KO = {
        "CHECKING": "입출금통장", "SAVINGS": "적금", "DEPOSIT": "정기예금",
        "SUBSCRIPTION": "청약", "FOREIGN": "외화통장",
    }

    def _my_accounts(self, data: list[dict]) -> str:
        lines = ["[내 계좌 목록]\n"]
        show, rest = data[:5], data[5:]
        for row in show:
            num    = row.get("account_number", "")
            atype  = self._ACCOUNT_TYPE_KO.get(row.get("account_type", ""), row.get("account_type", "계좌"))
            alias  = row.get("account_alias") or ""
            bal    = row.get("balance", 0)
            status = row.get("account_status", "")
            alias_str = f" ({alias})" if alias else ""
            bal_str   = f"{int(bal):,}원" if bal is not None else "잔액 미제공"
            status_str = " [해지]" if status in ("CLOSED", "INACTIVE") else ""
            lines.append(f"- {num}{alias_str} / {atype} / 잔액 {bal_str}{status_str}")
        if rest:
            lines.append(f"\n+ {len(rest)}개 계좌가 더 있습니다. 전체 조회는 앱의 계좌 목록을 확인해 주세요.")
        return "\n".join(lines)

    def _interest_history(self, data: list[dict]) -> str:
        lines = ["[이자 내역]\n"]
        show, rest = data[:5], data[5:]
        for row in show:
            rate       = row.get("applied_interest_rate", "")
            gross      = row.get("interest_amount", 0)
            net        = row.get("interest_after_tax_amount", 0)
            paid_at    = str(row.get("paid_at", ""))[:10]
            gross_str  = f"{int(gross):,}원" if gross is not None else "-"
            net_str    = f"{int(net):,}원" if net is not None else "-"
            lines.append(f"- {paid_at} / 적용금리 {rate}% / 세전 {gross_str} / 세후 {net_str}")
        if rest:
            lines.append(f"\n+ {len(rest)}건이 더 있습니다. 전체 내역은 앱의 이자 내역을 확인해 주세요.")
        return "\n".join(lines)

    def _contract_status(self, data: list[dict]) -> str:
        _STATUS_KO = {
            "ACTIVE": "유지", "MATURED": "만기", "TERMINATED": "해지", "PENDING": "대기"
        }
        lines = ["[계약 현황]\n"]
        show, rest = data[:5], data[5:]
        for row in show:
            no     = row.get("contract_no") or row.get("contract_id", "")
            name   = row.get("product_name") or self._NA
            ptype  = row.get("product_type") or row.get("deposit_product_type")
            rate   = row.get("contract_interest_rate") or row.get("interest_rate")
            mat    = str(row.get("maturity_at", "") or "")[:10] or None
            status = _STATUS_KO.get(row.get("contract_status", ""), row.get("contract_status", ""))
            amount = row.get("join_amount", 0)
            amt_str = f"{int(amount):,}원" if amount else self._NA
            lines.append(f"- {name}  [계약번호: {no} / 가입금액: {amt_str} / 상태: {status}]")
            lines.append(self._product_row(name="", rate=rate, term=mat, ptype=ptype))
        if rest:
            lines.append(f"\n+ {len(rest)}건이 더 있습니다. 전체 계약은 앱의 계약 현황을 확인해 주세요.")
        return "\n".join(lines)

    def _generic_list(self, data: list[dict]) -> str:
        """등록되지 않은 feature의 조회 결과를 key: value 형식으로 단순 표시."""
        lines = [f"[조회 결과 {len(data)}건]\n"]
        show, rest = data[:5], data[5:]
        for row in show:
            # 의미 있는 값만 추려서 표시 (None, 내부 ID 제외)
            parts = []
            for k, v in row.items():
                if v is None or k.endswith("_id"):
                    continue
                if isinstance(v, float) and v == int(v):
                    v = f"{int(v):,}"
                parts.append(f"{k}: {v}")
            lines.append("- " + " / ".join(parts[:5]))
        if rest:
            lines.append(f"\n+ {len(rest)}건이 더 있습니다.")
        return "\n".join(lines)


class LlmAdapter:
    """외부 AI 호출 없이 기존 서비스 생성자와 테스트 계약을 유지하는 어댑터."""

    process_method_code = "BP003_RULE"

    def __init__(self, api_key: str = "", model: str = "rule-based"):
        self.api_key = api_key
        self.model = model

    def answer(self, message: str, context: str = "") -> tuple[str, bool]:
        return (
            "자동 답변 범위를 벗어난 문의입니다. 상담사 연결을 이용해 주세요.",
            True,
        )

    def recommend(
        self,
        cash_flow: dict,
        products: list[dict],
        user_query: str,
        history_ctx: str = "",
    ) -> str:
        raise RuntimeError("상품 추천은 규칙 기반 추천 엔진에서만 처리합니다.")


from langfuse.decorators import observe


class OpenAIDocumentAnalyzer:
    """업로드된 문서(PDF 텍스트)를 분석하는 어댑터.

    OpenAI API 키가 있으면 GPT를 사용하고, 없으면 룰 기반 분석으로 폴백한다.
    """

    _SYSTEM_PROMPTS: dict[str, str] = {
        "CASH_FLOW": (
            "당신은 은행 재무 상담사입니다. 고객이 제공한 타행 거래내역을 분석해 "
            "월별 수입/지출 패턴, 주요 지출 카테고리, 저축 여력을 한국어로 요약해 주세요. "
            "구체적인 수치와 함께 금융 상품 추천 포인트도 함께 제시해 주세요."
        ),
        "TERMS": (
            "당신은 금융 약관 전문가입니다. 제공된 약관을 고객이 이해하기 쉽게 핵심 내용을 "
            "한국어로 설명해 주세요. 특히 중도해지 조건, 수수료, 주의사항을 강조해 주세요."
        ),
        "PRODUCT": (
            "당신은 은행 상품 전문가입니다. 제공된 상품 설명서를 바탕으로 금리 조건, "
            "가입 조건, 해지 방법, 세제 혜택을 고객이 쉽게 이해할 수 있도록 한국어로 요약해 주세요."
        ),
    }

    def __init__(self, api_key: str, model: str = "gpt-4o-mini") -> None:
        self._api_key = api_key
        self._model = model

    def analyze(self, text: str, analyze_type: str) -> str:
        if self._api_key:
            try:
                return self._analyze_with_openai(text, analyze_type)
            except Exception:
                pass
        return self._analyze_rule_based(text, analyze_type)

    @observe(name="llm-document-analyze")
    def _analyze_with_openai(self, text: str, analyze_type: str) -> str:
        from openai import OpenAI
        system_prompt = self._SYSTEM_PROMPTS.get(analyze_type, self._SYSTEM_PROMPTS["TERMS"])
        client = OpenAI(api_key=self._api_key)
        resp = client.chat.completions.create(
            model=self._model,
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": f"다음 내용을 분석해 주세요:\n\n{text[:8000]}"},
            ],
            max_tokens=1000,
        )
        return resp.choices[0].message.content or "분석 결과를 가져올 수 없습니다."

    def _analyze_rule_based(self, text: str, analyze_type: str) -> str:
        import re

        if analyze_type == "CASH_FLOW":
            return self._analyze_cash_flow(text)
        if analyze_type == "TERMS":
            return self._analyze_terms(text)
        if analyze_type == "PRODUCT":
            return self._analyze_product(text)
        return "분석 유형을 인식할 수 없습니다."

    def _analyze_cash_flow(self, text: str) -> str:
        import re
        lines = [l.strip() for l in text.splitlines() if l.strip()]

        # 금액 패턴: 숫자+원 혹은 쉼표 포함 숫자
        amount_pattern = re.compile(r"([\+\-]?\d[\d,]*)\s*원?")

        income_keywords = ["입금", "급여", "월급", "이자", "수입", "환급", "적립"]
        expense_keywords = ["출금", "이체", "결제", "납부", "지출", "인출", "자동이체"]

        income_total = 0
        expense_total = 0
        income_items: list[str] = []
        expense_items: list[str] = []

        for line in lines:
            amounts = [int(m.group(1).replace(",", "")) for m in amount_pattern.finditer(line)]
            if not amounts:
                continue
            amt = amounts[-1]
            if any(k in line for k in income_keywords):
                income_total += amt
                if len(income_items) < 5:
                    income_items.append(f"  • {line[:40]}")
            elif any(k in line for k in expense_keywords):
                expense_total += amt
                if len(expense_items) < 5:
                    expense_items.append(f"  • {line[:40]}")

        surplus = income_total - expense_total
        parts: list[str] = ["📊 **거래내역 분석 결과**\n"]
        parts.append(f"💰 총 수입: {income_total:,}원")
        if income_items:
            parts.extend(income_items)
        parts.append(f"\n💸 총 지출: {expense_total:,}원")
        if expense_items:
            parts.extend(expense_items)
        parts.append(f"\n📈 월평균 잉여자금: {surplus:,}원")

        if surplus > 500_000:
            parts.append("\n✅ 잉여자금이 충분합니다. 정기적금이나 청약저축을 활용하면 목돈 마련에 유리합니다.")
        elif surplus > 0:
            parts.append("\n💡 소액 자동이체 적금으로 저축 습관을 만들어 보세요.")
        else:
            parts.append("\n⚠️ 지출이 수입을 초과하고 있습니다. 지출 항목 점검을 권장드립니다.")

        if income_total == 0 and expense_total == 0:
            parts = [
                "📄 거래내역 파일을 확인했습니다.\n",
                "수입·지출 금액을 자동으로 파악하기 어려운 형식입니다.\n",
                "주요 내용을 직접 확인해 주세요:\n",
            ]
            parts.extend(f"  • {l}" for l in lines[:10])

        return "\n".join(parts)

    def _analyze_terms(self, text: str) -> str:
        import re
        lines = [l.strip() for l in text.splitlines() if l.strip()]

        # 조항 번호(제N조) 또는 숫자. 형태로 시작하는 줄 추출
        article_pattern = re.compile(r"^(제\s*\d+\s*조|[\d]+\.)\s*(.+)")
        articles: list[str] = []
        for line in lines:
            m = article_pattern.match(line)
            if m:
                articles.append(f"  • {m.group(0)[:60]}")
            if len(articles) >= 10:
                break

        # 키워드별 관련 문장 추출
        highlights: dict[str, list[str]] = {
            "중도해지": [],
            "수수료": [],
            "이자": [],
            "만기": [],
        }
        for line in lines:
            for keyword, bucket in highlights.items():
                if keyword in line and len(bucket) < 2:
                    bucket.append(line[:80])

        parts = ["📋 **약관 분석 결과**\n"]

        if articles:
            parts.append("📌 주요 조항:")
            parts.extend(articles)

        for keyword, bucket in highlights.items():
            if bucket:
                parts.append(f"\n🔍 {keyword} 관련:")
                parts.extend(f"  • {s}" for s in bucket)

        if len(parts) == 1:
            parts.append("약관 내용을 확인했습니다. 주요 내용:")
            parts.extend(f"  • {l}" for l in lines[:8])

        parts.append("\n⚠️ 중도해지 시 이자 손실이 발생할 수 있으니 가입 전 약관을 꼼꼼히 확인하세요.")
        return "\n".join(parts)

    def _analyze_product(self, text: str) -> str:
        import re
        lines = [l.strip() for l in text.splitlines() if l.strip()]

        rate_pattern = re.compile(r"(\d+\.?\d*)\s*%")
        amount_pattern = re.compile(r"(\d[\d,]*)\s*원")
        period_pattern = re.compile(r"(\d+)\s*개월")

        rates: list[str] = []
        amounts: list[str] = []
        periods: list[str] = []
        features: list[str] = []

        feature_keywords = ["세제", "비과세", "소득공제", "우대", "혜택", "특징", "조건", "가입대상", "만기"]

        for line in lines:
            if rate_pattern.search(line) and len(rates) < 4:
                rates.append(f"  • {line[:70]}")
            if amount_pattern.search(line) and len(amounts) < 3:
                amounts.append(f"  • {line[:70]}")
            if period_pattern.search(line) and len(periods) < 3:
                periods.append(f"  • {line[:70]}")
            if any(k in line for k in feature_keywords) and len(features) < 5:
                features.append(f"  • {line[:70]}")

        parts = ["📄 **상품 설명서 분석 결과**\n"]
        if rates:
            parts.append("💹 금리 조건:")
            parts.extend(rates)
        if amounts:
            parts.append("\n💰 가입 금액:")
            parts.extend(amounts)
        if periods:
            parts.append("\n📅 가입 기간:")
            parts.extend(periods)
        if features:
            parts.append("\n✨ 주요 특징/혜택:")
            parts.extend(features)

        if len(parts) == 1:
            parts.append("상품 설명서 내용을 확인했습니다. 주요 내용:")
            parts.extend(f"  • {l}" for l in lines[:8])

        parts.append("\n💡 자세한 가입 조건은 영업점 또는 고객센터에 문의하세요.")
        return "\n".join(parts)


class LlmHandoffAdapter:
    process_method_code = "BP002"

    def answer(self, message: str) -> str:
        try:
            from app.metrics import chatbot_fallback_total
            chatbot_fallback_total.inc()
        except Exception:
            pass
        return (
            "시나리오로 즉시 처리하기 어려운 문의입니다. "
            "상담사 연결을 이용해 주세요."
        )




class RagAnswerGenerator:
    """RAG 검색 결과 + 사용자 질문 → 자연어 답변 생성기.

    OpenAI API 키가 있으면 GPT-4o-mini로 답변하고, 없으면 룰 기반으로 폴백한다.
    """

    _SYSTEM_PROMPT = (
        "당신은 AXful Bank의 친절한 금융 상품 상담사입니다. "
        "제공된 상품 정보를 근거로 고객 질문에 한국어로 답변해 주세요. "
        "상품 정보에 없는 내용은 추측하지 말고 '확인이 필요합니다'라고 안내하세요. "
        "답변은 간결하고 명확하게 작성하며, 필요시 불릿 포인트를 사용하세요."
    )

    def __init__(self, api_key: str, model: str = "gpt-4o-mini") -> None:
        self._api_key = api_key
        self._model = model

    def answer(self, query: str, rag_results: list[dict]) -> str:
        if not rag_results:
            return "현재 등록된 상품 정보 기준으로는 답변이 어렵습니다. 상담사 연결을 원하시면 '상담사 연결'을 선택해 주세요."

        context = self._build_context(rag_results)

        if self._api_key:
            try:
                return self._answer_with_openai(query, context)
            except Exception:
                pass

        return self._answer_rule_based(query, rag_results)

    def _build_context(self, rag_results: list[dict]) -> str:
        lines = []
        for i, r in enumerate(rag_results, 1):
            doc_type = r.get("_type", "product")
            if doc_type == "product":
                name    = r.get("deposit_product_name") or r.get("product_name", "")
                ptype   = r.get("deposit_product_type") or r.get("product_type", "")
                ptype_ko = {"DEPOSIT": "예금", "SAVINGS": "적금", "SUBSCRIPTION": "청약"}.get(ptype, ptype)
                rate    = r.get("base_interest_rate", "")
                min_amt = r.get("min_join_amount", "")
                max_amt = r.get("max_join_amount", "")
                min_m   = r.get("min_period_month", "")
                max_m   = r.get("max_period_month", "")
                early   = "가능" if r.get("is_early_termination_allowed") else "불가"
                tax     = "있음" if r.get("is_tax_benefit_available") else "없음"
                desc    = r.get("description", "")
                line = (
                    f"[상품 {i}] {name} ({ptype_ko})\n"
                    f"  - 기본금리: {rate}% | 가입금액: {min_amt}~{max_amt}원 | 기간: {min_m}~{max_m}개월\n"
                    f"  - 중도해지: {early} | 세제혜택: {tax}"
                )
                if desc:
                    line += f"\n  - 설명: {desc[:150]}"
            else:
                name    = r.get("special_term_name", "")
                summary = r.get("special_term_summary", "") or r.get("special_term_content", "")[:150]
                line    = f"[약관 {i}] {name}\n  - {summary}"
            lines.append(line)
        return "\n\n".join(lines)

    @observe(name="llm-rag-answer")
    def _answer_with_openai(self, query: str, context: str) -> str:
        from openai import OpenAI
        client = OpenAI(api_key=self._api_key)
        resp = client.chat.completions.create(
            model=self._model,
            messages=[
                {"role": "system", "content": self._SYSTEM_PROMPT},
                {"role": "user", "content": f"[관련 상품 정보]\n{context}\n\n[고객 질문]\n{query}"},
            ],
            max_tokens=600,
            temperature=0.3,
        )
        return resp.choices[0].message.content or "답변을 생성할 수 없습니다."

    def _answer_rule_based(self, query: str, rag_results: list[dict]) -> str:
        ptype_ko = {"DEPOSIT": "예금", "SAVINGS": "적금", "SUBSCRIPTION": "청약"}
        lines = ["관련 상품 정보를 찾았습니다.\n"]
        for r in rag_results[:3]:
            doc_type = r.get("_type", "product")
            if doc_type == "product":
                name  = r.get("deposit_product_name") or r.get("product_name", "")
                ptype = r.get("deposit_product_type") or r.get("product_type", "")
                rate  = r.get("base_interest_rate", "")
                min_m = r.get("min_period_month", "")
                max_m = r.get("max_period_month", "")
                lines.append(f"• {name} ({ptype_ko.get(ptype, ptype)}): 금리 {rate}%, 기간 {min_m}~{max_m}개월")
            else:
                name    = r.get("special_term_name", "")
                summary = r.get("special_term_summary", "")
                lines.append(f"• {name}: {summary[:80]}" if summary else f"• {name}")
        lines.append("\n자세한 내용은 상담사 연결을 이용해 주세요.")
        return "\n".join(lines)
