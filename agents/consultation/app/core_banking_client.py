"""core-banking 조회 클라이언트.

수신 데이터(상품·계약·계좌·거래)는 core-banking 의 것이다. 여기서 직접 SQL 로 읽으면
두 가지가 무너진다.

1. **경계** — core-banking 이 스키마를 바꾸면 이 서비스가 조용히 깨진다. 컴파일도
   테스트도 잡아 주지 않는다.
2. **인가·감사** — 고객 데이터 경로에는 행위자·사유 검증과 열람 감사가 붙어 있다
   (``/v1/internal/banking/**``). SQL 로 우회하면 그 통제가 통째로 빠진다.

이 모듈은 그 경계를 넘는 **유일한 정상 경로**다.

전환은 자원 단위로 끊어서 한다. 공개 상품 → 계약/만기 → 계좌/잔액 → 거래 순이고,
지금은 **공개 상품까지** 옮겨져 있다. 상품은 고객 데이터가 아니라서 행위자·사유가
필요 없고, 그래서 가장 먼저 옮길 수 있다.

조회 실패는 빈 결과로 돌린다. 상담은 상품 목록을 못 읽으면 안내를 줄이면 되지만,
여기서 예외를 올리면 대화 자체가 끊긴다.
"""
from __future__ import annotations

from typing import Any

import httpx

from app import access_context
from app.config import get_settings

_TIMEOUT = 2.0


def _get(path: str, params: dict[str, Any] | None = None,
         with_actor: bool = False) -> Any:
    """core-banking 조회.

    ``with_actor`` 가 참이면 이 요청의 행위자를 헤더로 실어 보낸다. 고객 데이터
    경로는 행위자 없이는 거절된다 — 빠뜨리면 조용히 통과하는 것이 아니라 막힌다.
    공개 카탈로그(상품·약관)는 행위자를 요구하지 않으므로 붙이지 않는다.
    """
    settings = get_settings()
    # core-banking 의 context-path 는 /api 다. 붙이지 않으면 전부 404 가 나는데
    # 아래에서 상태코드를 None 으로 삼키므로 "자료가 없다" 와 구별되지 않는다 —
    # 조용히 빈 화면이 되고 아무도 모른다.
    #
    # 같은 파일의 customer-service 호출(_verify_staff)은 /api 를 붙이고 있었다.
    # 한쪽만 빠져 있었던 것이다.
    url = f"{settings.core_banking_url}/api{path}"
    headers = access_context.current().headers() if with_actor else {}
    # 서비스 신원. core-banking 이 이 값의 SHA-256 으로 호출자를 찾는다.
    #
    # 행위자 헤더(X-Employee-Id 등)와 역할이 다르다. 행위자는 "누구를 대신해
    # 부르는가" 이고 이것은 "어느 서비스가 부르는가" 다. 앞의 것은 주장이라 감사에
    # 쓰고, 뒤의 것은 자격증명이라 인증에 쓴다.
    headers["X-Internal-Token"] = settings.core_banking_credential
    try:
        with httpx.Client(timeout=_TIMEOUT) as client:
            resp = client.get(url, params=params or {}, headers=headers)
        if resp.status_code != 200:
            return None
        body = resp.json()
    except Exception:
        return None
    # ApiResponse 로 감싸 오는 경우와 그대로 오는 경우를 함께 받는다.
    if isinstance(body, dict) and "data" in body:
        return body["data"]
    return body


def fetch_selling_products() -> list[dict[str, Any]]:
    """판매 중인 수신 상품. 실패 시 빈 목록.

    반환 키는 SQL 시절 컬럼 이름을 유지한다 — 호출부를 함께 바꾸지 않기 위해서다.
    """
    data = _get("/products", {"productStatus": "SELLING"})
    if not isinstance(data, list):
        return []
    return [_product_view(p) for p in data]


def fetch_selling_products_for_compare() -> list[dict[str, Any]]:
    """비교 화면이 쓰는 넓은 형태. 우대조건·자동재예치·통장발행까지 포함한다."""
    data = _get("/products", {"productStatus": "SELLING"})
    if not isinstance(data, list):
        return []
    return [_compare_view(p) for p in data]


def _compare_view(p: dict[str, Any]) -> dict[str, Any]:
    return {
        "product_id":                   p.get("productId"),
        "product_name":                 p.get("productName"),
        "product_type":                 p.get("productType"),
        "description":                  p.get("description"),
        "base_interest_rate":           p.get("baseInterestRate"),
        "preferential_rate_condition":  p.get("preferentialRateCondition"),
        "min_join_amount":              p.get("minJoinAmount"),
        "max_join_amount":              p.get("maxJoinAmount"),
        "min_period_month":             p.get("minPeriodMonth"),
        "max_period_month":             p.get("maxPeriodMonth"),
        "is_early_termination_allowed": p.get("isEarlyTerminationAllowed"),
        "is_tax_benefit_available":     p.get("isTaxBenefitAvailable"),
        "is_auto_renewal_available":    p.get("isAutoRenewalAvailable"),
        "is_passbook_issued":           p.get("isPassbookIssued"),
    }


def _product_view(p: dict[str, Any]) -> dict[str, Any]:
    return {
        "product_id":                   p.get("productId"),
        "deposit_product_name":         p.get("productName"),
        "deposit_product_type":         p.get("productType"),
        "base_interest_rate":           p.get("baseInterestRate"),
        "min_join_amount":              p.get("minJoinAmount"),
        "max_join_amount":              p.get("maxJoinAmount"),
        "min_period_month":             p.get("minPeriodMonth"),
        "max_period_month":             p.get("maxPeriodMonth"),
        "is_early_termination_allowed": p.get("isEarlyTerminationAllowed"),
        "is_tax_benefit_available":     p.get("isTaxBenefitAvailable"),
        "description":                  p.get("description"),
    }

# ── 상품 카탈로그 ────────────────────────────────────────────────────────────
#
# 판매 중 상품을 대상·금리·예금상세까지 한 번에 받는다. 상담은 이 넷을 늘 함께
# 쓰므로 개별 조회로 나누면 상품 수만큼 호출이 늘어난다(N+1).
#
# 걸러 내고 모양을 바꾸는 것은 여기서 한다 — SQL 시절 각 화면이 쓰던 컬럼 이름을
# 유지해 호출부를 함께 바꾸지 않기 위해서다.

def fetch_product_catalog() -> list[dict[str, Any]]:
    """판매 중인 상품 카탈로그 원본. 실패 시 빈 목록."""
    data = _get("/v1/internal/banking/products")
    return data if isinstance(data, list) else []


def _rate_sum(entry: dict[str, Any]) -> float | None:
    """BASE·PREFERENTIAL 합계 — SQL 시절 effective_rate 를 그대로 옮긴 것."""
    rates = [r for r in (entry.get("interestRates") or [])
             if r.get("rateType") in ("BASE", "PREFERENTIAL")]
    if not rates:
        return None
    total = 0.0
    for r in rates:
        try:
            total += float(r.get("rate") or 0)
        except (TypeError, ValueError):
            continue
    return total


def _target_group_label(entry: dict[str, Any]) -> str:
    groups = entry.get("targetGroups") or []
    labels = []
    for g in groups:
        name = g.get("targetGroupName")
        desc = g.get("description")
        if name:
            labels.append(f"{name} ({desc})" if desc else name)
    return ", ".join(dict.fromkeys(labels)) if labels else "개인고객 (나이 제한 없음)"


def fetch_products_for_guide() -> list[dict[str, Any]]:
    """상품 안내용. 대상 요약과 실효금리를 붙이고 실효금리 내림차순 20건."""
    rows = []
    for e in fetch_product_catalog():
        eff = _rate_sum(e)
        base = e.get("baseInterestRate")
        rows.append({
            "product_id":                   e.get("productId"),
            "deposit_product_name":         e.get("productName"),
            "deposit_product_type":         e.get("productType"),
            "description":                  e.get("description"),
            "base_interest_rate":           base,
            "min_period_month":             e.get("minPeriodMonth"),
            "max_period_month":             e.get("maxPeriodMonth"),
            "min_join_amount":              e.get("minJoinAmount"),
            "max_join_amount":              e.get("maxJoinAmount"),
            "is_early_termination_allowed": e.get("isEarlyTerminationAllowed"),
            "is_tax_benefit_available":     e.get("isTaxBenefitAvailable"),
            "is_auto_renewal_available":    e.get("isAutoRenewalAvailable"),
            "is_compound_interest":         e.get("isCompoundInterest"),
            "target_groups":                _target_group_label(e),
            "effective_rate":               eff if eff is not None else base,
            # 아래 둘은 화면에 안 나가고 필터에만 쓴다. SQL 시절 WHERE 절이 하던 몫이다.
            "_deposit_type":                e.get("depositType"),
            "_target_group_ids":            [g.get("targetGroupId") for g in (e.get("targetGroups") or [])],
        })

    def sort_key(r):
        v = r.get("effective_rate")
        try:
            return -float(v)
        except (TypeError, ValueError):
            return 0.0

    rows.sort(key=sort_key)
    return rows[:20]


def fetch_interest_rates() -> list[dict[str, Any]]:
    """상품별 금리 목록(안내용 20건). SQL 시절 rate_id 순서를 유지한다."""
    rows = []
    for e in fetch_product_catalog():
        for r in (e.get("interestRates") or []):
            rows.append({
                "product_name":            e.get("productName"),
                "rate_type":               r.get("rateType"),
                "minimum_contract_period": r.get("minimumContractPeriod"),
                "maximum_contract_period": r.get("maximumContractPeriod"),
                "interest_rate":           r.get("rate"),
                "condition_description":   r.get("conditionDescription"),
                "_product_id":             e.get("productId"),
                "_rate_id":                r.get("rateId"),
            })
    rows.sort(key=lambda r: ((r["_product_id"] or 0), (r["_rate_id"] or 0)))
    for r in rows:
        r.pop("_product_id", None)
        r.pop("_rate_id", None)
    return rows[:20]


def fetch_products_by_ids(product_ids: list[int]) -> list[dict[str, Any]]:
    """비교 화면이 고른 상품들의 상세."""
    wanted = {int(i) for i in product_ids if i is not None}
    out = []
    for e in fetch_product_catalog():
        if e.get("productId") in wanted:
            v = _compare_view(e)
            v["is_compound_interest"] = e.get("isCompoundInterest")
            out.append(v)
    return out


def fetch_special_terms(query: str = "") -> list[dict[str, Any]]:
    """약관 검색. 매칭은 여기서 한다 — SQL LIKE 를 서비스 경계 너머로 넘기지 않는다."""
    data = _get("/v1/internal/banking/special-terms")
    if not isinstance(data, list):
        return []
    rows = [{
        "special_term_id":      t.get("specialTermId"),
        "special_term_name":    t.get("specialTermName"),
        "special_term_content": t.get("specialTermContent"),
        "special_term_summary": t.get("specialTermSummary"),
        "is_required":          t.get("isRequired"),
        "status":               t.get("status"),
    } for t in data]

    q = (query or "").strip()
    if q:
        rows = [r for r in rows if any(
            q in (r.get(k) or "") for k in
            ("special_term_name", "special_term_content", "special_term_summary"))]
    rows.sort(key=lambda r: r.get("special_term_id") or 0)
    return rows[:10]


def fetch_join_conditions() -> list[dict[str, Any]]:
    """가입 조건 안내. 판매 상태와 무관하게 카탈로그 순서로 20건."""
    rows = [{
        "product_id":                   e.get("productId"),
        "product_name":                 e.get("productName"),
        "min_join_amount":              e.get("minJoinAmount"),
        "max_join_amount":              e.get("maxJoinAmount"),
        "min_period_month":             e.get("minPeriodMonth"),
        "max_period_month":             e.get("maxPeriodMonth"),
        "is_early_termination_allowed": e.get("isEarlyTerminationAllowed"),
        "is_tax_benefit_available":     e.get("isTaxBenefitAvailable"),
        "product_status":               "SELLING",
    } for e in fetch_product_catalog()]
    rows.sort(key=lambda r: r.get("product_id") or 0)
    return rows[:20]


def fetch_compare_by_ids(product_ids: list[int]) -> list[dict[str, Any]]:
    """비교 대상 상품의 요약. SQL 시절 컬럼 이름을 유지한다."""
    wanted = {int(i) for i in product_ids if i is not None}
    rows = [{
        "product_id":         e.get("productId"),
        "product_name":       e.get("productName"),
        "product_type":       e.get("productType"),
        "base_interest_rate": e.get("baseInterestRate"),
        "min_join_amount":    e.get("minJoinAmount"),
        "max_join_amount":    e.get("maxJoinAmount"),
        "min_period_month":   e.get("minPeriodMonth"),
        "max_period_month":   e.get("maxPeriodMonth"),
    } for e in fetch_product_catalog() if e.get("productId") in wanted]
    rows.sort(key=lambda r: r.get("product_id") or 0)
    return rows


def fetch_compare_top(limit: int = 5) -> list[dict[str, Any]]:
    """비교 대상을 안 골랐을 때 보여 줄 기본 목록 — 기본금리 높은 순."""
    rows = fetch_compare_by_ids([e.get("productId") for e in fetch_product_catalog()])

    def rate(r):
        try:
            return -float(r.get("base_interest_rate") or 0)
        except (TypeError, ValueError):
            return 0.0

    rows.sort(key=lambda r: (rate(r), r.get("product_id") or 0))
    return rows[:limit]


def fetch_products_by_name(query: str) -> list[dict[str, Any]]:
    """상품명·설명에 검색어가 든 상품. SQL LIKE 를 대신한다."""
    q = (query or "").strip()
    rows = fetch_products_for_guide()
    for r in rows:
        r.pop("_deposit_type", None)
        r.pop("_target_group_ids", None)
    if not q:
        return rows[:10]
    return [r for r in rows if q in (r.get("deposit_product_name") or "")
            or q in (r.get("description") or "")][:10]


def fetch_products_for_goal(product_types: list[str], months: int | None,
                            limit: int, with_amounts: bool = False) -> list[dict[str, Any]]:
    """목표 기반 추천용. 기간 조건을 만족하는 상품을 기본금리 높은 순으로.

    기간 조건에 걸리는 상품이 없으면 조건을 빼고 다시 고른다 — SQL 시절 fallback 을
    그대로 옮긴 것이다. 조건이 없다고 빈 손으로 돌려주면 추천이 통째로 사라진다.
    """
    wanted = {t for t in product_types if t}

    def view(e: dict[str, Any]) -> dict[str, Any]:
        row = {
            "product_name":     e.get("productName"),
            "product_type":     e.get("productType"),
            "base_interest_rate": e.get("baseInterestRate"),
            "min_period_month": e.get("minPeriodMonth"),
            "max_period_month": e.get("maxPeriodMonth"),
        }
        if with_amounts:
            row.update({
                "product_id":               e.get("productId"),
                "min_join_amount":          e.get("minJoinAmount"),
                "max_join_amount":          e.get("maxJoinAmount"),
                "is_tax_benefit_available": e.get("isTaxBenefitAvailable"),
            })
        return row

    def rate_desc(r):
        try:
            return -float(r.get("base_interest_rate") or 0)
        except (TypeError, ValueError):
            return 0.0

    catalog = [e for e in fetch_product_catalog()
               if not wanted or e.get("productType") in wanted]

    def period_ok(e):
        if months is None:
            return True
        lo, hi = e.get("minPeriodMonth"), e.get("maxPeriodMonth")
        return (lo is None or lo <= months) and (hi is None or hi >= months)

    rows = [view(e) for e in catalog if period_ok(e)]
    if not rows:
        rows = [view(e) for e in catalog]
    rows.sort(key=rate_desc)
    return rows[:limit]


def fetch_products_basic(exclude_types: list[str] | None = None,
                         limit: int | None = None) -> list[dict[str, Any]]:
    """만기 재예치·추천에 쓰는 기본형. 기본금리 높은 순."""
    skip = set(exclude_types or [])
    rows = [{
        "product_id":                   e.get("productId"),
        "deposit_product_name":         e.get("productName"),
        "deposit_product_type":         e.get("productType"),
        "base_interest_rate":           e.get("baseInterestRate"),
        "min_join_amount":              e.get("minJoinAmount"),
        "max_join_amount":              e.get("maxJoinAmount"),
        "min_period_month":             e.get("minPeriodMonth"),
        "max_period_month":             e.get("maxPeriodMonth"),
        "is_early_termination_allowed": e.get("isEarlyTerminationAllowed"),
        "is_tax_benefit_available":     e.get("isTaxBenefitAvailable"),
    } for e in fetch_product_catalog() if e.get("productType") not in skip]

    def rate_desc(r):
        try:
            return -float(r.get("base_interest_rate") or 0)
        except (TypeError, ValueError):
            return 0.0

    rows.sort(key=rate_desc)
    return rows[:limit] if limit else rows


def fetch_products_with_target_groups(exclude_types: list[str] | None = None) -> list[dict[str, Any]]:
    """상품 × 대상그룹을 SQL 조인처럼 펼쳐서 돌려준다.

    대상이 없는 상품도 한 행으로 남긴다 — LEFT JOIN 을 그대로 옮긴 것이다.
    없앴다가는 대상 미지정 상품이 추천에서 통째로 사라진다.
    """
    skip = set(exclude_types or [])
    out: list[dict[str, Any]] = []
    for e in fetch_product_catalog():
        if e.get("productType") in skip:
            continue
        base = {
            "product_id":                   e.get("productId"),
            "deposit_product_name":         e.get("productName"),
            "deposit_product_type":         e.get("productType"),
            "base_interest_rate":           e.get("baseInterestRate"),
            "min_join_amount":              e.get("minJoinAmount"),
            "max_join_amount":              e.get("maxJoinAmount"),
            "min_period_month":             e.get("minPeriodMonth"),
            "max_period_month":             e.get("maxPeriodMonth"),
            "is_early_termination_allowed": e.get("isEarlyTerminationAllowed"),
            "is_tax_benefit_available":     e.get("isTaxBenefitAvailable"),
        }
        groups = e.get("targetGroups") or []
        if not groups:
            out.append({**base, "target_group_name": None, "min_age": None, "max_age": None})
            continue
        for g in groups:
            out.append({**base,
                        "target_group_name": g.get("targetGroupName"),
                        "min_age":           g.get("minAge"),
                        "max_age":           g.get("maxAge")})
    return out


# ── 고객 데이터 ──────────────────────────────────────────────────────────────
#
# 여기부터는 행위자와 사유가 붙는다. core-banking 이 인가 판단을 직접 확인하고,
# 자기 자원 기준으로 한 번 더 보고, 요청·판단·결과를 감사에 남긴다.

def fetch_customer_accounts(customer_no: str) -> list[dict[str, Any]]:
    """고객의 계좌 요약. 실패·거절 시 빈 목록."""
    if not customer_no:
        return []
    data = _get(f"/v1/internal/banking/customers/{customer_no}/accounts", with_actor=True)
    if not isinstance(data, list):
        return []
    return [{
        "account_id":     a.get("accountId"),
        "account_number": a.get("accountNumber"),
        "customer_no":    customer_no,
        "account_type":   a.get("accountType"),
        "account_alias":  a.get("accountAlias"),
        "balance":        a.get("balance"),
        "currency":       a.get("currency"),
        "account_status": a.get("accountStatus"),
        "opened_at":      a.get("openedAt"),
        "closed_at":      a.get("closedAt"),
    } for a in data]


def fetch_customer_transactions(customer_no: str, size: int = 200) -> list[dict[str, Any]]:
    """고객의 최근 거래. 건수 상한은 서버가 다시 자른다."""
    if not customer_no:
        return []
    data = _get(f"/v1/internal/banking/customers/{customer_no}/transactions",
                {"size": size}, with_actor=True)
    if not isinstance(data, list):
        return []
    return [{
        "transaction_id":     t.get("transactionId"),
        "transaction_number": t.get("transactionNumber"),
        "account_id":         t.get("accountId"),
        "transaction_type":   t.get("transactionType"),
        "direction_type":     t.get("directionType"),
        "amount":             t.get("amount"),
        "balance_after":      t.get("balanceAfter"),
        "status":             t.get("status"),
        "transaction_memo":   t.get("memo"),
        "transaction_at":     t.get("transactionAt"),
        # 상대 계좌는 내려오지 않는다. 본인 계좌 사이의 이체인지만 서버가 계산해 준다 —
        # 현금흐름 집계에서 내부 이체를 빼는 데 필요한 것은 그 참·거짓 하나뿐이다.
        "internal_transfer":  bool(t.get("internalTransfer")),
    } for t in data]


def fetch_customer_contracts(customer_no: str) -> list[dict[str, Any]]:
    """고객의 수신 계약."""
    if not customer_no:
        return []
    data = _get(f"/v1/internal/banking/customers/{customer_no}/contracts", with_actor=True)
    if not isinstance(data, list):
        return []
    return [{
        "contract_id":            c.get("contractId"),
        "contract_no":            c.get("contractNumber"),
        "customer_no":            customer_no,
        "join_amount":            c.get("joinAmount"),
        "contract_interest_rate": c.get("contractInterestRate"),
        "started_at":             c.get("startedAt"),
        "maturity_at":            c.get("maturityAt"),
        "contract_status":        c.get("contractStatus"),
        "product_id":             c.get("productId"),
        "product_name":           c.get("productName"),
        "product_type":           c.get("productType"),
    } for c in data]


def fetch_customer_interest_history(customer_no: str, size: int = 20) -> list[dict[str, Any]]:
    """고객의 이자 지급 내역."""
    if not customer_no:
        return []
    data = _get(f"/v1/internal/banking/customers/{customer_no}/interest-history",
                {"size": size}, with_actor=True)
    if not isinstance(data, list):
        return []
    return [{
        "interest_id":               h.get("interestId"),
        "contract_id":               h.get("contractId"),
        "account_id":                h.get("accountId"),
        "applied_interest_rate":     h.get("appliedInterestRate"),
        "interest_amount":           h.get("interestAmount"),
        "interest_after_tax_amount": h.get("interestAfterTaxAmount"),
        "paid_at":                   h.get("paidAt"),
    } for h in data]


# ── 직원 인가 ────────────────────────────────────────────────────────────────

def authorize_employee(employee_id: str | None, resource: str, action: str = "READ",
                       target_customer_no: str | None = None,
                       reason: str | None = None) -> bool:
    """이 직원이 이 자원을 봐도 되는지 customer-service 에 묻는다.

    <b>왜 employees 테이블을 직접 읽지 않는가.</b> 직원 신원의 정본은
    customer-service 이고(party → party_role → employee), 이 서비스가 붙는 DB 에는
    그 테이블이 아예 없다. 예전 코드는 그것을 직접 읽으려다 예외를 삼키고 늘
    False 를 돌려줬다 — 직원 기능이 조용히 전부 막혀 있었다.

    판단을 못 받으면 거절한다. 인가 서비스 장애가 곧 전면 개방이 되면 안 된다.
    """
    if not employee_id:
        return False
    ctx = access_context.current()
    url = f"{get_settings().customer_service_url}/api/v1/internal/authorization/employee"
    body = {
        "employeeId": int(employee_id) if str(employee_id).isdigit() else None,
        "resource": resource,
        "action": action,
        "targetCustomerId": target_customer_no,
        "reason": reason or ctx.reason or "상담 직원 조회",
    }
    if body["employeeId"] is None:
        return False
    try:
        with httpx.Client(timeout=_TIMEOUT) as client:
            resp = client.post(url, json=body)
        if resp.status_code != 200:
            return False
        return (resp.json() or {}).get("decision") == "ALLOW"
    except Exception:
        return False


# ── services.py 가 쓰는 모양 ────────────────────────────────────────────────
#
# 아래 넷은 컬럼 이름과 정렬·건수 제한까지 예전 SQL 그대로 맞춘다.
# 응답 키가 바뀌면 화면과 테스트가 같이 깨지므로, 경계만 옮기고 모양은 두는 것이다.

def fetch_products_guide_rows(product_type: str | None = None,
                              product_types: list[str] | None = None,
                              order: str = "id",
                              limit: int = 20,
                              with_description: bool = True) -> list[dict[str, Any]]:
    """판매 중 상품을 상담 화면이 쓰는 컬럼 이름으로 돌려준다.

    ``order`` 는 ``"id"``(상품번호 순) 또는 ``"rate"``(기본금리 내림차순).
    """
    rows = []
    for e in fetch_product_catalog():
        ptype = e.get("productType")
        if product_type and ptype != product_type:
            continue
        if product_types and ptype not in product_types:
            continue
        row = {
            "product_id":         e.get("productId"),
            "product_name":       e.get("productName"),
            "product_type":       ptype,
            "base_interest_rate": e.get("baseInterestRate"),
            "min_join_amount":    e.get("minJoinAmount"),
            "max_join_amount":    e.get("maxJoinAmount"),
            "min_period_month":   e.get("minPeriodMonth"),
            "max_period_month":   e.get("maxPeriodMonth"),
        }
        if with_description:
            row["description"] = e.get("description")
        row["product_status"] = "SELLING"
        rows.append(row)

    def rate_desc(r):
        # NULLS LAST 를 흉내 낸다. 금리 없는 상품이 앞으로 올라오면 안내가 뒤집힌다.
        v = r.get("base_interest_rate")
        try:
            return (0, -float(v))
        except (TypeError, ValueError):
            return (1, 0.0)

    if order == "rate":
        rows.sort(key=lambda r: (rate_desc(r), r.get("product_id") or 0))
    else:
        rows.sort(key=lambda r: r.get("product_id") or 0)
    return rows[:limit]


def fetch_rate_guide_rows(limit: int = 200,
                          exclude_name_keywords: tuple[str, ...] = ()) -> list[dict[str, Any]]:
    """상품별 금리 목록. 이름에 제외 키워드가 든 상품은 뺀다."""
    rows = []
    for e in fetch_product_catalog():
        name = e.get("productName") or ""
        if any(k in name for k in exclude_name_keywords):
            continue
        for r in (e.get("interestRates") or []):
            rows.append({
                "rate_id":                 r.get("rateId"),
                "product_id":              e.get("productId"),
                "product_name":            name,
                "rate_type":               r.get("rateType"),
                "minimum_contract_period": r.get("minimumContractPeriod"),
                "maximum_contract_period": r.get("maximumContractPeriod"),
                "interest_rate":           r.get("rate"),
                "condition_description":   r.get("conditionDescription"),
            })
    rows.sort(key=lambda r: ((r["product_id"] or 0), (r["rate_id"] or 0)))
    return rows[:limit]


def fetch_rates_by_product_ids(product_ids: list[int]) -> list[dict[str, Any]]:
    """지정 상품들의 금리 행. 기간·유형 집계는 부르는 쪽이 한다."""
    wanted = {int(i) for i in product_ids if i is not None}
    if not wanted:
        return []
    rows = []
    for e in fetch_product_catalog():
        pid = e.get("productId")
        if pid not in wanted:
            continue
        for r in (e.get("interestRates") or []):
            rows.append({
                "banking_product_id":      pid,
                "rate_id":                 r.get("rateId"),
                "rate_type":               r.get("rateType"),
                "rate":                    r.get("rate"),
                "condition_description":   r.get("conditionDescription"),
                "minimum_contract_period": r.get("minimumContractPeriod"),
                "maximum_contract_period": r.get("maximumContractPeriod"),
            })
    return rows


def fetch_target_group_product_ids(target_group_id: int) -> set[int]:
    """해당 대상군에 묶인 상품 번호. 나이 제한 상품을 거를 때 쓴다."""
    out = set()
    for e in fetch_product_catalog():
        for g in (e.get("targetGroups") or []):
            if g.get("targetGroupId") == target_group_id:
                out.add(int(e.get("productId")))
    return out
