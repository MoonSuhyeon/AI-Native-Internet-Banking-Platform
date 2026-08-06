"""공통 feature executor 기반 클래스.

모든 FeatureExecutor 가 공유하는 DB 쿼리 헬퍼, 응답 팩토리,
인증 검증 로직을 한 곳에서 관리한다.
"""
from __future__ import annotations

from datetime import date, datetime, timedelta, timezone
from functools import lru_cache
from typing import Any

import httpx
from sqlalchemy import bindparam, text
from sqlalchemy.orm import Session

from app.schemas import ChatbotFeatureExecuteResponse

# ChatMessageHistory 모델 임포트 (history context 조회용)
CODE_SENDER_USER = 1


@lru_cache(maxsize=512)
def _fetch_customer_age_cached(customer_no: str) -> int | None:
    """customer-service에서 생년월일을 조회해 만 나이를 반환한다.

    프로세스 수준 LRU 캐시(최대 512명)를 사용해 상품 목록 조회마다
    동기 HTTP 호출이 반복되는 것을 방지한다.
    customer-service 미기동/장애 시 즉시 None 반환(청년 필터 미적용)으로 폴백.
    """
    from app.config import get_settings
    url = f"{get_settings().customer_service_url}/api/v1/customers/me"
    try:
        with httpx.Client(timeout=1.0) as client:
            resp = client.get(url, headers={"X-Customer-Id": customer_no})
        if resp.status_code != 200:
            return None
        body = resp.json()
        birth_str = (body.get("data") or body).get("birthDate")
        if not birth_str:
            return None
        birth_str = birth_str.replace("-", "")
        birth = date(int(birth_str[:4]), int(birth_str[4:6]), int(birth_str[6:8]))
        today = date.today()
        return today.year - birth.year - ((today.month, today.day) < (birth.month, birth.day))
    except Exception:
        return None


def build_history_context(db: Session, chatbot_consultation_id: int, max_turns: int = 5) -> str:
    """최근 대화 이력(사용자·챗봇 교대)을 LLM context 문자열로 변환한다.

    ChatbotService.handle_message 와 UserFinanceFeatureExecutor 양쪽에서
    사용하므로 모듈 수준 독립 함수로 제공한다.
    """
    from sqlalchemy import select
    from app.models import ChatMessageHistory

    rows = list(
        db.scalars(
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


# 우대금리 조건 fallback — DB(banking_deposit_product_interest_rates)에 조건 설명이
# 없을 때 상품명으로 유추한다. ChatbotService(PRODUCT_SEARCH)와 UserFinance
# executor(CASH_FLOW_RECOMMEND)가 같은 표를 써야 해서 여기 둔다.
PREF_COND_FALLBACK: list[tuple[str, str]] = [
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


def fetch_pref_conditions(rows_fn, product_ids: list[int]) -> dict[int, dict]:
    """상품별 우대금리 조건 설명 및 합산 금리 조회.

    rows_fn 은 SQL 을 실행해 dict 목록을 돌려주는 함수(_rows)다. ChatbotService 와
    FeatureExecutorBase 가 각자 _rows 를 갖고 있어 함수로 받는다.
    """
    if not product_ids:
        return {}
    id_list = ",".join(str(i) for i in product_ids)
    rows = rows_fn(
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


def enrich_pref_conditions(rows_fn, products: list[dict[str, Any]]) -> None:
    """상품 dict 목록에 pref_condition·pref_rate 를 채운다(제자리 수정).

    DB 조건이 우선이고, 없으면 상품명 기반 fallback 을 쓴다.
    """
    pids = [int(p.get("product_id") or p.get("banking_product_id") or 0) for p in products]
    pref_cond_map = fetch_pref_conditions(rows_fn, pids)
    for p in products:
        pid = int(p.get("product_id") or p.get("banking_product_id") or 0)
        info = pref_cond_map.get(pid, {})
        cond = info.get("condition", "") if isinstance(info, dict) else ""
        rate = info.get("rate", 0.0) if isinstance(info, dict) else 0.0
        if not cond:
            name = str(p.get("deposit_product_name") or p.get("product_name", ""))
            for keyword, fallback in PREF_COND_FALLBACK:
                if keyword in name:
                    cond = fallback
                    break
        if cond:
            p["pref_condition"] = cond
        if rate:
            p["pref_rate"] = rate


def append_preferential_rate_notice(message: str, product_cards: list[dict[str, Any]]) -> str:
    """추천 문구 뒤에 우대금리 안내 절을 붙인다. 해당 상품이 없으면 그대로 둔다."""
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


class FeatureExecutorBase:
    """Feature executor 공통 기반 클래스.

    DB 쿼리 유틸리티, 응답 팩토리, 인증/권한 검증 메서드를 제공한다.
    """

    def __init__(self, db: Session) -> None:
        self.db = db

    # ── DB 쿼리 헬퍼 ──────────────────────────────────────────────────────────

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
                   c.banking_product_id AS product_id,
                   p.deposit_product_name AS product_name,
                   c.join_amount,
                   c.contract_interest_rate,
                   c.started_at,
                   c.maturity_at,
                   c.contract_status
              FROM deposit_contracts c
              LEFT JOIN deposit_banking_products p ON p.banking_product_id = c.banking_product_id
             WHERE c.customer_id = :customer_no
             ORDER BY c.contract_id
             LIMIT 20
            """,
            {"customer_no": customer_no},
        )

    def _analyze_customer_cash_flow(self, customer_no: str, months: int = 3) -> dict[str, Any] | None:
        """고객의 전체 계좌 완료 거래를 집계해 현금흐름 지표를 반환한다.

        Returns:
            {total_balance, monthly_surplus, monthly_tx_count, has_data}
            계좌 없으면 None
        """
        accounts = self._rows(
            "SELECT account_id, balance FROM deposit_accounts WHERE customer_id = :cno",
            {"cno": customer_no},
        )
        if not accounts:
            return None

        total_balance = sum(float(a.get("balance") or 0) for a in accounts)
        account_ids = tuple(a["account_id"] for a in accounts)

        # 날짜 컷오프를 Python에서 계산 → SQLite·PostgreSQL 모두 호환
        cutoff = (datetime.now(timezone.utc) - timedelta(days=30 * months)).strftime("%Y-%m-%d")
        tx_rows = self._rows(
            """
            SELECT transaction_type,
                   status,
                   amount
              FROM deposit_transactions
             WHERE account_id IN :account_ids
               AND transaction_at >= :cutoff
            """,
            {"account_ids": account_ids, "cutoff": cutoff},
            expanding_params=("account_ids",),
        )

        tx_rows = [
            r for r in tx_rows
            if str(r.get("status") or "").upper() in ("SUCCESS", "COMPLETED")
        ]

        if not tx_rows:
            return {
                "total_balance":    total_balance,
                "monthly_surplus":  0.0,
                "monthly_tx_count": 0.0,
                "has_data":         False,
            }

        inflow  = sum(float(r["amount"] or 0) for r in tx_rows if r["transaction_type"] == "DEPOSIT")
        outflow = sum(
            float(r["amount"] or 0) for r in tx_rows
            if r["transaction_type"] in ("WITHDRAWAL", "WITHDRAW", "TRANSFER")
        )
        return {
            "total_balance":    total_balance,
            "monthly_surplus":  (inflow - outflow) / months,
            "monthly_tx_count": len(tx_rows) / months,
            "has_data":         True,
        }

    def _build_history_context(self, chatbot_consultation_id: int, max_turns: int = 5) -> str:
        """모듈 수준 build_history_context 의 인스턴스 메서드 래퍼."""
        return build_history_context(self.db, chatbot_consultation_id, max_turns)

    # ── 공통 executor (MY_PRODUCTS, CONTRACT_STATUS, STAFF_CONTRACT 공유) ─────

    def execute_customer_contracts(
        self,
        request: Any,
        feature_code: str,
        ok_message: str,
        empty_message: str,
        requires_staff_auth: bool = False,
    ) -> ChatbotFeatureExecuteResponse:
        if requires_staff_auth and (not request.customer_no or not request.staff_id):
            return self._staff_auth_required(feature_code, "계약 조회에는 고객번호와 직원 권한이 필요합니다.")
        if requires_staff_auth and request.staff_id and not self._validate_staff(request.staff_id):
            return self._staff_auth_required(feature_code, "유효하지 않은 직원 계정입니다.")
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

    # ── 응답 팩토리 ───────────────────────────────────────────────────────────

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

    # ── 인증/권한 검증 ────────────────────────────────────────────────────────

    def _validate_staff(self, staff_id: str) -> bool:
        """staff_id 가 employees 테이블에 실제로 존재하는 유효한 직원인지 확인한다.

        staff_id 는 execute_chatbot_feature 엔드포인트에서 JWT 토큰 클레임으로 교체된 후
        이 메서드에 전달되므로, 클라이언트가 body로 보낸 값은 무시된다.
        """
        rows = self._rows(
            "SELECT employee_id FROM employees WHERE employee_id = :sid AND status = 'ACTIVE'",
            {"sid": staff_id},
        )
        return len(rows) > 0

    def _get_customer_age(self, customer_no: str | None) -> int | None:
        """customer-service에서 생년월일을 조회해 만 나이를 반환. 실패 시 None.

        동기 httpx.Client 사용 — consultation-service route handler가 모두 def(동기)이므로
        FastAPI가 threadpool에서 실행, 이벤트 루프 블로킹 없음.
        결과는 프로세스 수준 LRU 캐시로 보관해 상품 목록 조회마다 동기 HTTP 호출이
        추가되는 것을 방지한다.
        """
        if not customer_no:
            return None
        return _fetch_customer_age_cached(customer_no)

    def _is_youth_eligible(self, age: int | None) -> bool:
        """청년 전용 상품 가입 가능 여부 (만 19~34세)."""
        return age is not None and 19 <= age <= 34
