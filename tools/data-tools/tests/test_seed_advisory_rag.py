"""
seed_advisory_rag.py 단위 테스트.

테스트 항목:
  - parse_frontmatter: 정상 / 프런트매터 없음
  - folder_category: 폴더명 -> doc_category_cd 매핑
  - build_payload: 정상 파싱 / 필수 필드 누락 에러
  - register_document (dry-run): HTTP 호출 없이 "OK" 반환
  - register_document (멱등): 409 응답 -> "SKIP" 반환
  - scan_seed_files: README.md 제외 확인

실행:
  pip install pytest
  pytest services/data-tools/tests/test_seed_advisory_rag.py -v
"""

import os
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch

# 스크립트 경로를 sys.path 에 추가
SCRIPTS_DIR = os.path.join(os.path.dirname(__file__), "..", "scripts")
sys.path.insert(0, os.path.abspath(SCRIPTS_DIR))

from seed_advisory_rag import (
    build_payload,
    folder_category,
    parse_frontmatter,
    register_document,
    scan_seed_files,
)


class TestParseFrontmatter(unittest.TestCase):
    def test_정상_프런트매터_파싱(self):
        content = '---\ndoc_cd: TEST_001\ndoc_title: "테스트"\n---\n본문 내용'
        meta, body = parse_frontmatter(content)
        self.assertEqual(meta["doc_cd"], "TEST_001")
        self.assertEqual(meta["doc_title"], "테스트")
        self.assertEqual(body, "본문 내용")

    def test_프런트매터_없으면_빈_dict_반환(self):
        content = "프런트매터 없는 마크다운"
        meta, body = parse_frontmatter(content)
        self.assertEqual(meta, {})
        self.assertEqual(body, content)

    def test_큰따옴표_값_제거(self):
        content = '---\ndoc_version: "1.0"\n---\n'
        meta, _ = parse_frontmatter(content)
        self.assertEqual(meta["doc_version"], "1.0")


class TestFolderCategory(unittest.TestCase):
    def test_알려진_폴더_매핑(self):
        self.assertEqual(folder_category("/seed-data/law/DOC.md"), "CREDIT_REGULATION")
        self.assertEqual(folder_category("/seed-data/supervision/DOC.md"), "SUPERVISION")
        self.assertEqual(folder_category("/seed-data/internal/DOC.md"), "INTERNAL_POLICY")
        self.assertEqual(folder_category("/seed-data/product/DOC.md"), "PRODUCT_POLICY")
        self.assertEqual(folder_category("/seed-data/fair-lending/DOC.md"), "FAIR_LENDING")

    def test_미등록_폴더는_대문자_변환(self):
        result = folder_category("/seed-data/custom-type/DOC.md")
        self.assertEqual(result, "CUSTOM_TYPE")


class TestBuildPayload(unittest.TestCase):
    def _make_md(self, content: str) -> str:
        fd, path = tempfile.mkstemp(suffix=".md", dir=tempfile.mkdtemp())
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(content)
        return path

    def test_정상_페이로드_생성(self):
        content = (
            "---\n"
            "doc_cd: TEST_001\n"
            'doc_title: "테스트 문서"\n'
            'doc_version: "1.0"\n'
            'effective_start_date: "20250101"\n'
            'effective_end_date: "20991231"\n'
            "---\n"
            "본문 내용입니다."
        )
        path = self._make_md(content)
        payload = build_payload(path)
        self.assertEqual(payload["docCd"], "TEST_001")
        self.assertEqual(payload["docTitle"], "테스트 문서")
        self.assertEqual(payload["docVersion"], "1.0")
        self.assertEqual(payload["content"], "본문 내용입니다.")

    def test_필수_필드_누락_시_ValueError(self):
        content = "---\ndoc_title: 제목만 있음\n---\n본문"
        path = self._make_md(content)
        with self.assertRaises(ValueError):
            build_payload(path)

    def test_doc_category_cd_프런트매터_우선(self):
        content = (
            "---\n"
            "doc_cd: TEST_002\n"
            "doc_title: 테스트\n"
            'doc_version: "1.0"\n'
            "doc_category_cd: CUSTOM_CAT\n"
            "---\n"
            "본문"
        )
        path = self._make_md(content)
        # fair-lending 폴더 이름을 흉내 내기 위해 임시 파일 경로를 조작
        payload = build_payload(path)
        self.assertEqual(payload["docCategoryCd"], "CUSTOM_CAT")


class TestRegisterDocumentDryRun(unittest.TestCase):
    def test_dry_run_은_HTTP_호출_없이_OK_반환(self):
        payload = {
            "docCd": "DRY_001",
            "docTitle": "드라이런 문서",
            "docVersion": "1.0",
        }
        result = register_document("http://localhost:9999", payload, dry_run=True)
        self.assertEqual(result, "OK")

    @patch("seed_advisory_rag.requests.post")
    def test_409_응답은_SKIP_반환(self, mock_post):
        mock_resp = MagicMock()
        mock_resp.status_code = 409
        mock_resp.text = ""
        mock_post.return_value = mock_resp

        payload = {"docCd": "DUP_001", "docTitle": "중복", "docVersion": "1.0"}
        result = register_document("http://localhost:8080", payload, dry_run=False)
        self.assertEqual(result, "SKIP")

    @patch("seed_advisory_rag.requests.post")
    def test_400_중복_메시지는_SKIP_반환(self, mock_post):
        mock_resp = MagicMock()
        mock_resp.status_code = 400
        mock_resp.text = '{"code":"LOAN_001","message":"이미 존재하는 문서입니다"}'
        mock_post.return_value = mock_resp

        payload = {"docCd": "DUP_002", "docTitle": "중복2", "docVersion": "1.0"}
        result = register_document("http://localhost:8080", payload, dry_run=False)
        self.assertEqual(result, "SKIP")

    @patch("seed_advisory_rag.requests.post")
    def test_201_응답은_OK_반환(self, mock_post):
        mock_resp = MagicMock()
        mock_resp.status_code = 201
        mock_resp.json.return_value = {"data": {"docId": 42, "chunkCount": 3}}
        mock_post.return_value = mock_resp

        payload = {"docCd": "NEW_001", "docTitle": "신규", "docVersion": "1.0"}
        result = register_document("http://localhost:8080", payload, dry_run=False)
        self.assertEqual(result, "OK")


class TestScanSeedFiles(unittest.TestCase):
    def test_README_제외하고_md_파일만_반환(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            subdir = os.path.join(tmpdir, "law")
            os.makedirs(subdir)
            open(os.path.join(tmpdir, "README.md"), "w").close()
            open(os.path.join(subdir, "DOC_001.md"), "w").close()
            open(os.path.join(subdir, "DOC_002.md"), "w").close()
            open(os.path.join(subdir, "not_md.txt"), "w").close()

            files = scan_seed_files(tmpdir)

        basenames = [os.path.basename(f) for f in files]
        self.assertIn("DOC_001.md", basenames)
        self.assertIn("DOC_002.md", basenames)
        self.assertNotIn("README.md", basenames)
        self.assertNotIn("not_md.txt", basenames)


if __name__ == "__main__":
    unittest.main()
