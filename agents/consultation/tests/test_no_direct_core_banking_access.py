"""상담이 수신 원장에 다시 붙지 못하게 못 박는다 (A2 Phase 3).

**왜 이 테스트가 필요한가.** A2 는 상담의 SQL 을 지우는 작업이 아니었다. 지운 것은
결과이고, 없앤 것은 <b>행위자도 사유도 감사도 없이 남의 계좌를 읽는 경로</b>다.

그런데 그 경로는 되살리기가 아주 쉽다. 접속 문자열 한 줄이면 되고, 되살아나도 기능은
멀쩡히 돌기 때문에 아무도 눈치채지 못한다. 조용히 통과하는 것이 이 결함의 성질이다.
그래서 사람의 주의가 아니라 여기서 막는다.

**세 겹으로 본다.** 코드에서 표 이름이 사라졌는지, 접속 문자열이 원장 DB 를 가리키지
않는지, 그리고 <b>망에서 아예 닿지 않는지</b>. 앞의 둘만 보면 "코드에서는 지웠는데
길은 열려 있는" 상태를 놓친다. 세 번째가 실제 통제고, 앞의 둘은 조기 경보다.
"""
from __future__ import annotations

import re
from pathlib import Path

import pytest
import yaml

REPO_ROOT = Path(__file__).resolve().parents[3]
APP_DIR = Path(__file__).resolve().parents[1] / "app"
ROOT_COMPOSE = REPO_ROOT / "docker-compose.yml"

# core-banking 이 소유한 표. 상담이 이 이름들을 SQL 로 부르면 경계를 넘은 것이다.
CORE_BANKING_TABLES = (
    "deposit_accounts",
    "deposit_transactions",
    "deposit_contracts",
    "deposit_interest_history",
    "deposit_banking_products",
    "deposit_special_terms",
    "deposit_target_groups",
    "banking_deposit_products",
    "banking_deposit_product_interest_rates",
    "banking_deposit_product_target_groups",
)

# 경계를 넘는 유일한 파일. 여기'만' core-banking 을 부르고, HTTP 로만 부른다.
CLIENT_MODULE = "core_banking_client.py"


def _python_sources() -> list[Path]:
    return [p for p in APP_DIR.rglob("*.py") if "__pycache__" not in p.parts]


def _strip_comments_and_docstrings(source: str) -> str:
    """주석과 문서화 문자열을 지운다.

    설명문에 표 이름이 나오는 것은 경계 위반이 아니다 — 무엇을 왜 안 쓰는지 적어 두는
    편이 오히려 낫다. 실행되는 SQL 만 본다.
    """
    import ast
    import io
    import tokenize

    out = []
    try:
        tree = ast.parse(source)
    except SyntaxError:  # pragma: no cover - 문법 오류는 다른 테스트가 잡는다
        return source

    docstrings = set()
    for node in ast.walk(tree):
        if isinstance(node, (ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)):
            doc = ast.get_docstring(node, clean=False)
            if doc is not None:
                docstrings.add(doc)

    for tok in tokenize.generate_tokens(io.StringIO(source).readline):
        if tok.type == tokenize.COMMENT:
            continue
        if tok.type == tokenize.STRING:
            literal = tok.string
            try:
                value = ast.literal_eval(literal)
            except (ValueError, SyntaxError):
                value = None
            if isinstance(value, str) and value in docstrings:
                continue
        out.append(tok.string)
    return "\n".join(out)


@pytest.mark.parametrize("table", CORE_BANKING_TABLES)
def test_런타임_코드가_수신_표를_직접_부르지_않는다(table: str) -> None:
    """상담 코드에 core-banking 표 이름이 남으면 직접 조회가 되살아난 것이다."""
    offenders = []
    for path in _python_sources():
        if path.name == CLIENT_MODULE:
            continue
        code = _strip_comments_and_docstrings(path.read_text(encoding="utf-8"))
        if re.search(rf"\b{re.escape(table)}\b", code):
            offenders.append(str(path.relative_to(REPO_ROOT)))

    assert not offenders, (
        f"'{table}' 은 core-banking 소유 표다. 상담이 직접 읽으면 그 조회에는 "
        f"행위자도 사유도 감사도 남지 않는다. core_banking_client 를 통해 읽을 것.\n"
        f"발견: {offenders}"
    )


def test_경계를_넘는_파일은_하나뿐이고_HTTP_로만_부른다() -> None:
    """core-banking 을 부르는 곳이 늘면 통제할 자리도 그만큼 늘어난다."""
    client = APP_DIR / CLIENT_MODULE
    assert client.exists(), f"{CLIENT_MODULE} 이 없다"

    code = _strip_comments_and_docstrings(client.read_text(encoding="utf-8"))
    for forbidden in ("sqlalchemy", "psycopg", "create_engine", "sessionmaker"):
        assert forbidden not in code, (
            f"{CLIENT_MODULE} 이 '{forbidden}' 을 쓴다. 이 파일은 HTTP 로만 부르는 "
            f"자리다 — DB 를 직접 열면 API 로 옮긴 의미가 없다."
        )


# ── 배포 설정 ────────────────────────────────────────────────────────────────
#
# 위 검사는 코드만 본다. 코드가 깨끗해도 접속 문자열이 원장 DB 를 가리키고 있으면
# 언제든 되돌아갈 수 있다. 배포 설정을 함께 보는 이유다.

def _root_compose() -> dict:
    if not ROOT_COMPOSE.exists():  # pragma: no cover
        pytest.skip(f"루트 compose 를 찾지 못했다: {ROOT_COMPOSE}")
    return yaml.safe_load(ROOT_COMPOSE.read_text(encoding="utf-8"))


def test_접속_문자열이_수신_원장_DB_를_가리키지_않는다() -> None:
    services = _root_compose()["services"]
    url = services["consultation-service"]["environment"]["CONSULTATION_DATABASE_URL"]

    assert "core-banking-db" not in url, (
        "상담 DB 접속이 수신 원장 DB 를 가리킨다. 이 한 줄이면 A2 가 없앤 경로가 "
        f"그대로 되살아난다.\n실제 값: {url}"
    )
    assert "search_path" not in url, (
        "search_path 로 deposit 스키마를 붙이는 것은 원장 표를 직접 읽겠다는 뜻이다.\n"
        f"실제 값: {url}"
    )


def test_상담과_수신_원장_DB_가_같은_망에_있지_않다() -> None:
    """실제 통제는 여기다.

    자격증명을 지워도 망이 열려 있으면 되붙일 수 있다. 반대로 망이 끊겨 있으면
    접속 문자열을 되돌려 놓아도 이름 조회부터 실패한다 — 조용히 통과하지 않는다.
    """
    services = _root_compose()["services"]
    consultation_nets = set(services["consultation-service"].get("networks") or ["default"])
    ledger_db_nets = set(services["core-banking-db-a"].get("networks") or ["default"])

    shared = consultation_nets & ledger_db_nets
    assert not shared, (
        "상담과 수신 원장 DB 가 같은 망에 있다. 코드에서 SQL 을 지워도 길은 열려 있는 "
        f"상태다.\n겹치는 망: {sorted(shared)}\n"
        f"상담: {sorted(consultation_nets)} / 원장 DB: {sorted(ledger_db_nets)}"
    )


def test_상담_전용_DB_는_상담만_쓴다() -> None:
    """상담 이력은 상담 것이지 사이드카 공용이 아니다."""
    compose = _root_compose()
    services = compose["services"]

    db_nets = set(services["consultation-db"].get("networks") or ["default"])
    assert db_nets, "consultation-db 에 망이 지정돼 있지 않다"

    others = [
        name
        for name, spec in services.items()
        if name not in ("consultation-db", "consultation-service")
        and set(spec.get("networks") or ["default"]) & db_nets
    ]
    assert not others, (
        f"상담 전용 DB 망에 다른 서비스가 들어와 있다: {others}\n"
        "이 망에는 상담 서비스와 그 DB 만 있어야 한다."
    )
