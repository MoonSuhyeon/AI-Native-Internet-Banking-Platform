"""
seed_review_cases.py 단위 테스트.

테스트 항목:
  - create_product: dry_run=True → HTTP 호출 없이 prod_id=0 반환
  - create_application: dry_run=True → HTTP 호출 없이 appl_id=0 반환
  - prep_fully_eligible: dry_run=True → HTTP 호출 없음
  - run_review: dry_run=True → HTTP 호출 없이 rev_id=0 반환
  - create_full_review: mock POST 성공 → rev_id 반환
  - create_full_review: requests.post 예외 → raise 전파

실행:
  pip install pytest
  pytest services/data-tools/tests/test_seed_review_cases.py -v
"""

import os
import sys
import unittest
from unittest.mock import MagicMock, patch

SCRIPTS_DIR = os.path.join(os.path.dirname(__file__), "..", "scripts")
sys.path.insert(0, os.path.abspath(SCRIPTS_DIR))

from seed_review_cases import (
    create_application,
    create_full_review,
    create_product,
    prep_fully_eligible,
    run_review,
)

HOST = "http://localhost:9999"


def _mock_resp(status: int, data: dict):
    m = MagicMock()
    m.status_code = status
    m.raise_for_status = MagicMock()
    m.json.return_value = {"data": data}
    return m


class TestDryRun(unittest.TestCase):
    """dry_run=True 시 requests 호출 없음 확인."""

    @patch("seed_review_cases.requests.post")
    @patch("seed_review_cases.requests.patch")
    def test_create_product_dry_run(self, mock_patch, mock_post):
        prod_id = create_product(HOST, dry_run=True)
        mock_post.assert_not_called()
        mock_patch.assert_not_called()
        self.assertEqual(prod_id, 0)

    @patch("seed_review_cases.requests.post")
    def test_create_application_dry_run(self, mock_post):
        appl_id = create_application(HOST, prod_id=1, customer_id=1, dry_run=True)
        mock_post.assert_not_called()
        self.assertEqual(appl_id, 0)

    @patch("seed_review_cases.requests.post")
    def test_prep_fully_eligible_dry_run(self, mock_post):
        prep_fully_eligible(HOST, appl_id=1, dry_run=True)
        mock_post.assert_not_called()

    @patch("seed_review_cases.requests.post")
    def test_run_review_dry_run(self, mock_post):
        rev_id = run_review(HOST, appl_id=1, approved=True, dry_run=True)
        mock_post.assert_not_called()
        self.assertEqual(rev_id, 0)


class TestCreateFullReview(unittest.TestCase):
    """실제 HTTP 호출(mock) 을 통한 전체 흐름 검증."""

    @patch("seed_review_cases.requests.patch")
    @patch("seed_review_cases.requests.post")
    def test_정상_흐름_revId_반환(self, mock_post, mock_patch):
        # create_product → applId → prescreening → credit-eval → dsr → identity → review
        mock_post.side_effect = [
            _mock_resp(201, {"prodId": 10}),    # create_product
            _mock_resp(201, {"applId": 20}),    # create_application
            _mock_resp(201, {}),                # prescreening
            _mock_resp(201, {}),                # credit-evaluation
            _mock_resp(201, {}),                # dsr-calculation
            _mock_resp(201, {}),                # identity-verifications
            _mock_resp(201, {"revId": 99}),     # review
        ]
        mock_patch.return_value = _mock_resp(200, {})

        prod_id = create_product(HOST, dry_run=False)
        rev_id = create_full_review(HOST, prod_id, customer_id=1,
                                    approved=True, dry_run=False)

        self.assertEqual(rev_id, 99)
        self.assertEqual(mock_post.call_count, 7)
        self.assertEqual(mock_patch.call_count, 1)

    @patch("seed_review_cases.requests.patch")
    @patch("seed_review_cases.requests.post")
    def test_rejected_결정_전달(self, mock_post, mock_patch):
        mock_post.side_effect = [
            _mock_resp(201, {"prodId": 11}),
            _mock_resp(201, {"applId": 21}),
            _mock_resp(201, {}),
            _mock_resp(201, {}),
            _mock_resp(201, {}),
            _mock_resp(201, {}),
            _mock_resp(201, {"revId": 100}),
        ]
        mock_patch.return_value = _mock_resp(200, {})

        prod_id = create_product(HOST, dry_run=False)
        rev_id = create_full_review(HOST, prod_id, customer_id=1,
                                    approved=False, dry_run=False)
        # review POST 는 마지막 호출 — REJECTED body 확인
        review_call = mock_post.call_args_list[-1]
        body_bytes = review_call.kwargs.get("data", b"")
        body_str = body_bytes.decode("utf-8") if isinstance(body_bytes, bytes) else str(body_bytes)
        self.assertIn("REJECTED", body_str)
        self.assertEqual(rev_id, 100)

    @patch("seed_review_cases.requests.post")
    def test_HTTP_오류시_예외_전파(self, mock_post):
        import requests as req_lib
        mock_post.side_effect = req_lib.exceptions.ConnectionError("연결 실패")
        with self.assertRaises(req_lib.exceptions.ConnectionError):
            create_application(HOST, prod_id=1, customer_id=1, dry_run=False)


if __name__ == "__main__":
    unittest.main()
