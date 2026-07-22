"""
Advisory RAG 합성 심사 케이스 생성 스크립트.

대출 API 를 호출해 COMPLETED 상태의 loan_review 레코드를 N 건 생성한다.
생성된 케이스는 advisory-service 의 case-index/backfill API 로 RAG 인덱스에 적재 가능.

사용법:
    pip install requests
    python scripts/seed_review_cases.py [--count 10] [--approved-ratio 0.7]
                                         [--customer-id 1] [--host URL] [--dry-run]

파라미터:
    --count          생성할 케이스 수 (기본값: 10)
    --approved-ratio 승인 비율 0.0~1.0 (기본값: 0.7)
    --customer-id    대출 신청에 사용할 고객 ID (기본값: 1)
    --host           서비스 기본 URL (기본값: http://localhost:8080)
    --dry-run        실제 API 호출 없이 대상 목록만 출력
"""

import argparse
import json
import sys
import time
import uuid

import requests

# ---------------------------------------------------------------------------
# 기본값
# ---------------------------------------------------------------------------

DEFAULT_HOST        = "http://localhost:8080"
DEFAULT_COUNT       = 10
DEFAULT_APPR_RATIO  = 0.7
DEFAULT_CUSTOMER_ID = 1


# ---------------------------------------------------------------------------
# API 호출 헬퍼
# ---------------------------------------------------------------------------

def _post(host: str, path: str, body: dict, dry_run: bool) -> dict:
    """POST 요청. dry_run=True 면 실제 호출 없이 빈 dict 반환."""
    if dry_run:
        print(f"  [DRY-RUN] POST {host}{path}  body={json.dumps(body, ensure_ascii=False)[:120]}")
        return {}
    resp = requests.post(
        f"{host}{path}",
        headers={"Content-Type": "application/json"},
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        timeout=30,
    )
    resp.raise_for_status()
    return resp.json().get("data", {})


def _patch(host: str, path: str, body: dict, dry_run: bool) -> None:
    if dry_run:
        print(f"  [DRY-RUN] PATCH {host}{path}  body={json.dumps(body, ensure_ascii=False)[:120]}")
        return
    resp = requests.patch(
        f"{host}{path}",
        headers={"Content-Type": "application/json"},
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        timeout=30,
    )
    resp.raise_for_status()


# ---------------------------------------------------------------------------
# 개별 단계
# ---------------------------------------------------------------------------

def create_product(host: str, dry_run: bool) -> int:
    """임시 대출 상품 생성 후 활성화. 상품 ID 반환."""
    code = "SEED_" + uuid.uuid4().hex[:8].upper()
    body = {
        "prodCd": code,
        "prodName": "합성 케이스 생성용 상품",
        "loanTypeCd": "CREDIT",
        "repaymentMethodCd": "EQUAL",
        "rateTypeCd": "FIXED",
        "baseRateBps": 600,
        "minAmount": 1_000_000,
        "maxAmount": 100_000_000,
        "minPeriodMo": 12,
        "maxPeriodMo": 60,
        "collateralRequiredYn": "N",
        "guarantorRequiredYn": "N",
    }
    data = _post(host, "/api/loan-products", body, dry_run)
    prod_id = data.get("prodId", 0)
    if not dry_run:
        _patch(host, f"/api/loan-products/{prod_id}",
               {"prodStatusCd": "ACTIVE"}, dry_run)
    print(f"  상품 생성: prodId={prod_id}  prodCd={code}")
    return prod_id


def create_application(host: str, prod_id: int, customer_id: int, dry_run: bool) -> int:
    """대출 신청서 생성. applId 반환."""
    data = _post(host, "/api/loan-applications", {
        "customerId": customer_id,
        "prodId": prod_id,
        "channelCd": "MOBILE",
        "requestedAmount": 30_000_000,
        "requestedPeriodMo": 36,
        "loanPurposeCd": "LIVING",
        "repaymentMethodCd": "EQUAL",
    }, dry_run)
    return data.get("applId", 0)


def prep_fully_eligible(host: str, appl_id: int, dry_run: bool) -> None:
    """사전심사·CB평가·DSR·본인인증 순서대로 통과."""
    _post(host, f"/api/loan-applications/{appl_id}/prescreening", {
        "prescResultCd": "PASS",
        "estimatedGrade": "BBB",
        "estimatedScore": 700,
    }, dry_run)
    _post(host, f"/api/loan-applications/{appl_id}/credit-evaluation", {
        "cevalEngine": "KCB",
        "cevalDecisionCd": "APPROVE",
        "cevalScore": 720,
        "evalLimitAmount": 50_000_000,
    }, dry_run)
    _post(host, f"/api/loan-applications/{appl_id}/dsr-calculation", {
        "annualIncomeAmt": 80_000_000,
        "newAnnualRepayAmt": 10_000_000,
    }, dry_run)
    _post(host, f"/api/loan-applications/{appl_id}/identity-verifications", {
        "idvMethodCd": "PASS_APP",
        "idvTargetCd": "BORROWER",
        "mobileNo": "01098765432",
    }, dry_run)


def run_review(host: str, appl_id: int, approved: bool, dry_run: bool) -> int:
    """심사 결정 등록. revId 반환."""
    decision = "APPROVED" if approved else "REJECTED"
    data = _post(host, f"/api/loan-applications/{appl_id}/review", {
        "revTypeCd": "AUTO",
        "revDecisionCd": decision,
    }, dry_run)
    return data.get("revId", 0)


# ---------------------------------------------------------------------------
# 풀 플로우
# ---------------------------------------------------------------------------

def create_full_review(host: str, prod_id: int, customer_id: int,
                       approved: bool, dry_run: bool) -> int:
    """대출 신청~심사까지 전체 흐름. revId 반환."""
    appl_id = create_application(host, prod_id, customer_id, dry_run)
    prep_fully_eligible(host, appl_id, dry_run)
    return run_review(host, appl_id, approved, dry_run)


# ---------------------------------------------------------------------------
# 메인
# ---------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(description="Advisory RAG 합성 심사 케이스 생성")
    parser.add_argument("--count",          type=int,   default=DEFAULT_COUNT,
                        help=f"생성할 케이스 수 (기본값: {DEFAULT_COUNT})")
    parser.add_argument("--approved-ratio", type=float, default=DEFAULT_APPR_RATIO,
                        help=f"승인 비율 0.0~1.0 (기본값: {DEFAULT_APPR_RATIO})")
    parser.add_argument("--customer-id",    type=int,   default=DEFAULT_CUSTOMER_ID,
                        help=f"대출 신청 고객 ID (기본값: {DEFAULT_CUSTOMER_ID})")
    parser.add_argument("--host",           default=DEFAULT_HOST,
                        help=f"서비스 기본 URL (기본값: {DEFAULT_HOST})")
    parser.add_argument("--dry-run",        action="store_true",
                        help="실제 API 호출 없이 대상만 출력")
    args = parser.parse_args()

    if not 0.0 <= args.approved_ratio <= 1.0:
        print("오류: --approved-ratio 는 0.0 ~ 1.0 범위여야 합니다.", file=sys.stderr)
        return 1

    print(
        f"=== Advisory RAG 케이스 시딩  host={args.host}  "
        f"count={args.count}  approved_ratio={args.approved_ratio}  "
        f"dry_run={args.dry_run} ===\n"
    )

    # 대출 상품 1개 공유
    prod_id = create_product(args.host, args.dry_run)

    ok = fail = 0
    for i in range(args.count):
        approved = (i / args.count) < args.approved_ratio
        decision_label = "APPROVED" if approved else "REJECTED"
        print(f"[{i + 1}/{args.count}] 케이스 생성  decision={decision_label}")
        try:
            rev_id = create_full_review(
                args.host, prod_id, args.customer_id, approved, args.dry_run
            )
            print(f"  완료  revId={rev_id}")
            ok += 1
        except Exception as e:
            print(f"  실패  {type(e).__name__}: {e}")
            fail += 1

        if not args.dry_run:
            time.sleep(0.1)

    print(f"\n=== 완료  성공={ok}  실패={fail}  전체={args.count} ===")
    return 1 if fail > 0 else 0


if __name__ == "__main__":
    raise SystemExit(main())
