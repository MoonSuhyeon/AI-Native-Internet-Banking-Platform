"""직원 업무 지원 feature executor.

담당 feature: STAFF_CUSTOMER, STAFF_CONTRACT, STAFF_ACCOUNT,
              STAFF_TRANSFER_FLOW, STAFF_CONSULTATION_HISTORY, STAFF_CASH_FLOW
"""
from __future__ import annotations

from app.features.base import FeatureExecutorBase
from app.schemas import ChatbotFeatureExecuteRequest, ChatbotFeatureExecuteResponse


class StaffFeatureExecutor(FeatureExecutorBase):

    # ── STAFF_CUSTOMER ────────────────────────────────────────────────────────

    def execute_staff_customer(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        if not request.customer_no or not request.staff_id:
            return self._staff_auth_required("STAFF_CUSTOMER", "직원 고객 정보 조회에는 고객번호와 직원 권한이 필요합니다.")
        if not self._validate_staff(request.staff_id):
            return self._staff_auth_required("STAFF_CUSTOMER", "유효하지 않은 직원 계정입니다.")
        rows = self._account_rows(request.customer_no)
        return self._data_response(
            "STAFF_CUSTOMER", rows, "직원용 고객 정보 조회를 완료했습니다.", "조회된 고객 정보가 없습니다.", requires_staff_auth=True
        )

    # ── STAFF_ACCOUNT ─────────────────────────────────────────────────────────

    def execute_staff_account(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        if not request.customer_no or not request.staff_id:
            return self._staff_auth_required("STAFF_ACCOUNT", "직원 고객 계좌 조회에는 고객번호와 직원 권한이 필요합니다.")
        if not self._validate_staff(request.staff_id):
            return self._staff_auth_required("STAFF_ACCOUNT", "유효하지 않은 직원 계정입니다.")
        rows = self._account_rows(request.customer_no)
        return self._data_response(
            "STAFF_ACCOUNT", rows, "직원용 고객 계좌 조회를 완료했습니다.", "조회된 고객 계좌가 없습니다.", requires_staff_auth=True
        )

    # ── STAFF_TRANSFER_FLOW ───────────────────────────────────────────────────

    def execute_staff_transfer_flow(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        if not request.customer_no or not request.staff_id:
            return self._staff_auth_required("STAFF_TRANSFER_FLOW", "이체 흐름 조회에는 고객번호와 직원 권한이 필요합니다.")
        if not self._validate_staff(request.staff_id):
            return self._staff_auth_required("STAFF_TRANSFER_FLOW", "유효하지 않은 직원 계정입니다.")
        rows = self._rows(
            """
            SELECT t.transaction_id,
                   t.transaction_number,
                   a.account_number,
                   a.customer_id AS customer_no,
                   t.transaction_type,
                   t.transaction_status,
                   t.amount,
                   t.transaction_at
              FROM deposit_transactions t
              JOIN deposit_accounts a ON a.account_id = t.account_id
             WHERE a.customer_id = :customer_no
             ORDER BY t.transaction_at DESC
             LIMIT 20
            """,
            {"customer_no": request.customer_no},
        )
        return self._data_response(
            "STAFF_TRANSFER_FLOW", rows, "이체 흐름 조회를 완료했습니다.", "조회된 이체 내역이 없습니다.", requires_staff_auth=True
        )

    # ── STAFF_CONSULTATION_HISTORY ────────────────────────────────────────────

    def execute_staff_consultation_history(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        if not request.customer_no or not request.staff_id:
            return self._staff_auth_required("STAFF_CONSULTATION_HISTORY", "상담 이력 조회에는 고객번호와 직원 권한이 필요합니다.")
        if not self._validate_staff(request.staff_id):
            return self._staff_auth_required("STAFF_CONSULTATION_HISTORY", "유효하지 않은 직원 계정입니다.")
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

    # ── STAFF_CASH_FLOW ───────────────────────────────────────────────────────

    def execute_staff_cash_flow(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        if not request.customer_no or not request.staff_id:
            return self._staff_auth_required("STAFF_CASH_FLOW", "고객 현금 흐름 조회에는 고객번호와 직원 권한이 필요합니다.")
        if not self._validate_staff(request.staff_id):
            return self._staff_auth_required("STAFF_CASH_FLOW", "유효하지 않은 직원 계정입니다.")
        rows = self._rows(
            """
            SELECT t.transaction_id,
                   a.account_number,
                   a.customer_id AS customer_no,
                   t.transaction_type,
                   t.amount,
                   t.transaction_status,
                   t.transaction_at
              FROM deposit_transactions t
              JOIN deposit_accounts a ON a.account_id = t.account_id
             WHERE a.customer_id = :customer_no
             ORDER BY t.transaction_at DESC
             LIMIT 20
            """,
            {"customer_no": request.customer_no},
        )
        return self._data_response(
            "STAFF_CASH_FLOW", rows, "고객 현금 흐름 조회를 완료했습니다.", "조회된 거래 내역이 없습니다.", requires_staff_auth=True
        )
