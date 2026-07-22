"""
Advisory RAG 정책문서 일괄 적재 스크립트.

사용법:
    pip install requests
    python scripts/seed_advisory_rag.py [--host URL] [--seed-dir PATH] [--dry-run]

각 .md 파일은 YAML 프런트매터 포함 필수:
    ---
    doc_cd: DOC_001
    doc_title: "문서 제목"
    doc_version: "1.0"
    effective_start_date: "20250101"
    effective_end_date: "20991231"
    source_uri: ""
    doc_desc: "..."
    # doc_category_cd 생략 시 폴더명에서 자동 결정
    ---

폴더 -> doc_category_cd 매핑:
    law/          -> CREDIT_REGULATION
    supervision/  -> SUPERVISION
    internal/     -> INTERNAL_POLICY
    product/      -> PRODUCT_POLICY
    fair-lending/ -> FAIR_LENDING

멱등: 동일 doc_cd + doc_version 은 재실행 시 건너뜀 (409 = NO-OP).
"""

import argparse
import json
import os
import sys
import time

import requests

# ---------------------------------------------------------------------------
# 설정
# ---------------------------------------------------------------------------

DEFAULT_SEED_DIR = os.path.join(
    os.path.dirname(__file__), "..", "..", "advisory-service", "seed-data"
)

FOLDER_CATEGORY_MAP = {
    "law": "CREDIT_REGULATION",
    "supervision": "SUPERVISION",
    "internal": "INTERNAL_POLICY",
    "product": "PRODUCT_POLICY",
    "fair-lending": "FAIR_LENDING",
}

# ---------------------------------------------------------------------------
# 프런트매터 파서 (외부 의존성 없음)
# ---------------------------------------------------------------------------

def parse_frontmatter(content: str) -> tuple:
    """YAML 프런트매터와 본문을 분리해 반환 (meta_dict, body_str)."""
    if not content.startswith("---"):
        return {}, content
    end = content.find("\n---", 3)
    if end == -1:
        return {}, content
    fm_str = content[4:end]
    body = content[end + 4:].lstrip("\n")
    meta = {}
    for line in fm_str.splitlines():
        if ":" in line:
            key, _, val = line.partition(":")
            meta[key.strip()] = val.strip().strip('"').strip("'")
    return meta, body


def folder_category(filepath: str) -> str:
    """파일 경로에서 상위 폴더명 추출 후 doc_category_cd 결정."""
    parent = os.path.basename(os.path.dirname(filepath))
    return FOLDER_CATEGORY_MAP.get(parent, parent.upper().replace("-", "_"))


# ---------------------------------------------------------------------------
# 파일 스캔
# ---------------------------------------------------------------------------

def scan_seed_files(seed_dir: str) -> list:
    """seed_dir 을 재귀 탐색해 .md 파일 목록을 반환."""
    result = []
    for root, _, files in os.walk(seed_dir):
        for fname in sorted(files):
            if fname.endswith(".md") and fname != "README.md":
                result.append(os.path.join(root, fname))
    return result


def build_payload(filepath: str) -> dict:
    """
    .md 파일을 읽어 POST 페이로드 dict 반환.
    필수 필드(docCd, docTitle, docVersion) 누락 시 ValueError.
    """
    with open(filepath, encoding="utf-8") as f:
        raw = f.read()

    meta, body = parse_frontmatter(raw)

    required = ("doc_cd", "doc_title", "doc_version")
    missing = [k for k in required if not meta.get(k)]
    if missing:
        raise ValueError(f"프런트매터 필수 필드 누락: {missing} ({filepath})")

    return {
        "docCd": meta["doc_cd"],
        "docTitle": meta["doc_title"],
        "docCategoryCd": meta.get("doc_category_cd") or folder_category(filepath),
        "docVersion": meta["doc_version"],
        "effectiveStartDate": meta.get("effective_start_date", "20250101"),
        "effectiveEndDate": meta.get("effective_end_date", "20991231"),
        "sourceUri": meta.get("source_uri", ""),
        "docDesc": meta.get("doc_desc", ""),
        "content": body.strip(),
    }


# ---------------------------------------------------------------------------
# HTTP 클라이언트
# ---------------------------------------------------------------------------

def register_document(host: str, payload: dict, dry_run: bool) -> str:
    """
    문서를 적재하고 결과 문자열 반환.
    반환값: "OK", "SKIP", "FAIL"
    """
    url = f"{host}/api/internal/advisory/documents"

    if dry_run:
        print(f"  [DRY-RUN] POST {url}")
        print(f"  docCd={payload['docCd']}  title={payload['docTitle'][:50]}")
        return "OK"

    try:
        resp = requests.post(
            url,
            headers={"Content-Type": "application/json"},
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            timeout=60,
        )
        if resp.status_code in (200, 201):
            data = resp.json().get("data", {})
            print(f"  OK     docId={data.get('docId')}  chunks={data.get('chunkCount')}")
            return "OK"
        elif resp.status_code == 409 or (
            resp.status_code in (400, 422)
            and any(kw in resp.text for kw in ("이미", "중복", "LOAN_001", "duplicate"))
        ):
            print(f"  SKIP   (이미 존재)  docCd={payload['docCd']} v{payload['docVersion']}")
            return "SKIP"
        else:
            print(f"  FAIL   status={resp.status_code}  {resp.text[:200]}")
            return "FAIL"
    except requests.exceptions.ConnectionError as e:
        print(f"  FAIL   연결 오류: {e}")
        return "FAIL"
    except Exception as e:
        print(f"  FAIL   {type(e).__name__}: {e}")
        return "FAIL"


# ---------------------------------------------------------------------------
# 메인
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="Advisory RAG 정책문서 일괄 적재")
    parser.add_argument(
        "--host",
        default="http://localhost:8080",
        help="advisory-service 기본 URL (기본값: http://localhost:8080)",
    )
    parser.add_argument(
        "--seed-dir",
        default=DEFAULT_SEED_DIR,
        help="시드 데이터 루트 디렉토리 (기본값: advisory-service/seed-data/)",
    )
    parser.add_argument("--dry-run", action="store_true", help="실제 요청 없이 대상만 출력")
    args = parser.parse_args()

    seed_dir = os.path.abspath(args.seed_dir)
    if not os.path.isdir(seed_dir):
        print(f"오류: seed-dir 을 찾을 수 없습니다: {seed_dir}", file=sys.stderr)
        sys.exit(1)

    files = scan_seed_files(seed_dir)
    if not files:
        print(f"경고: {seed_dir} 에서 .md 파일을 찾지 못했습니다.")
        sys.exit(0)

    print(f"=== Advisory RAG 시딩  host={args.host}  dry_run={args.dry_run}  파일 수={len(files)} ===\n")

    ok = skip = fail = 0
    for filepath in files:
        rel = os.path.relpath(filepath, seed_dir)
        print(f"[{rel}]")
        try:
            payload = build_payload(filepath)
        except (ValueError, OSError) as e:
            print(f"  FAIL   파일 오류: {e}")
            fail += 1
            continue

        result = register_document(args.host, payload, args.dry_run)
        if result == "OK":
            ok += 1
        elif result == "SKIP":
            skip += 1
        else:
            fail += 1

        if not args.dry_run:
            time.sleep(0.3)

    print(f"\n=== 완료  성공={ok}  건너뜀={skip}  실패={fail}  전체={len(files)} ===")
    if fail > 0:
        sys.exit(1)


if __name__ == "__main__":
    main()
