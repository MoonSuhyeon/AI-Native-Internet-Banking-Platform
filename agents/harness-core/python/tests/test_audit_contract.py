"""계약이 실제로 지켜지는지 확인한다.

두 런타임이 같은 테이블에 쓴다는 것은 지금까지 **글로만** 보장돼 있었다.
Java 를 고치고 Python 을 안 고치거나, 원본 마이그레이션을 고치고 사본을 안 고치면
런타임에 가서야 드러난다 — 그것도 감사 기록이 유실되는 형태로.

여기서 확인하는 것은 셋이다.

1. SQL 컬럼 == Python dataclass 필드
2. SQL 컬럼 == Java record 컴포넌트 (camelCase → snake_case)
3. 원본 마이그레이션의 컬럼 == 각 에이전트에 복사된 마이그레이션의 컬럼

3번이 ADR 이 "남은 부담 하나" 로 적어둔 그 부담이다. 손으로 맞춰야 하는 상태를
없애지는 못하지만, **어긋나면 테스트가 먼저 알린다**로 바꿀 수는 있다.
"""

from __future__ import annotations

import dataclasses
import re
from pathlib import Path
from typing import NamedTuple

import pytest

from harness_core.audit import AgentAuditEntry, NoOpAgentAuditLog

REPO_ROOT = Path(__file__).resolve().parents[4]

CANONICAL_SQL = REPO_ROOT / "agents/harness-core/src/main/resources/db/harness/V001__harness_audit_log.sql"
JAVA_ENTRY = REPO_ROOT / "agents/harness-core/src/main/java/com/bank/harness/audit/AgentAuditEntry.java"

# 각 에이전트 DB 에 복사된 판본. 새 에이전트가 감사를 붙이면 여기에 추가한다.
# 추가를 잊으면 그 사본은 아무도 지키지 않는 상태가 된다.
#
# 값이 파일 <b>목록</b>인 이유: Flyway 를 쓰는 에이전트는 이미 적용된 마이그레이션을
# 고칠 수 없다(체크섬). 그래서 컬럼 추가가 새 파일로 나가고, 그 에이전트의 유효 스키마는
# 여러 파일을 순서대로 적용한 결과다. 첫 파일만 보면 확장을 놓친다.
MIGRATION_COPIES = {
    "auto-loan-review": [
        REPO_ROOT / "agents/auto-loan-review/src/main/resources/db/migration/V8__harness_audit_log.sql",
        REPO_ROOT / "agents/auto-loan-review/src/main/resources/db/migration/V9__harness_audit_actor.sql",
        REPO_ROOT / "agents/auto-loan-review/src/main/resources/db/migration/V10__harness_audit_actor_roles_index.sql",
    ],
    "consultation": [REPO_ROOT / "agents/consultation/sql/harness-audit.sql"],
    "goal-agent": [REPO_ROOT / "agents/goal-agent/sql/harness-audit.sql"],
    "fraud-investigation-agent": [
        REPO_ROOT / "agents/fraud-investigation-agent/sql/harness-audit.sql"
    ],
}

# 자동 증가 PK 는 기록 주체가 채우지 않으므로 계약 대상이 아니다.
NOT_PART_OF_CONTRACT = {"id"}


class Column(NamedTuple):
    """대조 단위. 이름·타입만으로는 부족해서 NULL 허용과 기본값까지 담는다."""

    name: str
    type: str
    not_null: bool
    default: str | None


def _parse_constraints(tail: str) -> tuple[bool, str | None]:
    """컬럼 정의의 타입 뒤쪽에서 NOT NULL 여부와 DEFAULT 식을 뽑는다.

    NULL 허용을 보는 이유: 사본에서 NOT NULL 이 빠지면 그 에이전트만 빈 값을 받아
    넣고, 나중에 한곳에 모을 때 다른 에이전트의 기록과 형식이 달라진다.

    기본값을 보는 이유: JSONB NOT NULL 컬럼의 DEFAULT 가 사라지면 그 컬럼을 채우지
    않는 경로에서 INSERT 가 통째로 거부된다 — 판단은 성공했는데 기록만 사라진다.
    """
    cleaned = tail.strip().rstrip(",;").strip()
    not_null = re.search(r"\bNOT\s+NULL\b", cleaned, re.IGNORECASE) is not None
    default_match = re.search(r"\bDEFAULT\s+(.+)$", cleaned, re.IGNORECASE)
    default = default_match.group(1).strip() if default_match else None
    return not_null, default


def _column_defs(sql_path: Path) -> list[Column]:
    """CREATE TABLE 본문에서 컬럼 정의를 순서대로 뽑는다.

    타입까지 보는 이유: 이름만 맞춰 보면 ``VARCHAR(64)`` → ``VARCHAR(16)`` 같은
    변경을 놓친다. 그 드리프트는 감사 기록을 **조용히 잘라서** 저장하는 형태로
    나타나므로 어긋난 줄도 모르게 된다.
    """
    text = sql_path.read_text(encoding="utf-8")
    body = re.search(
        r"CREATE TABLE IF NOT EXISTS harness_audit_log\s*\((.*?)\n\);",
        text,
        re.DOTALL,
    )
    assert body, f"{sql_path.name} 에서 harness_audit_log 정의를 찾지 못했다"

    defs = []
    for line in body.group(1).splitlines():
        line = line.strip()
        if not line or line.startswith("--"):
            continue
        tokens = line.split()
        name = tokens[0]
        if name.upper() in {"PRIMARY", "CONSTRAINT", "UNIQUE", "FOREIGN"}:
            continue
        if name in NOT_PART_OF_CONTRACT:
            continue
        col_type = tokens[1].rstrip(",").upper() if len(tokens) > 1 else ""
        not_null, default = _parse_constraints(" ".join(tokens[2:]))
        defs.append(Column(name, col_type, not_null, default))
    return defs


_CREATE_INDEX = re.compile(
    r"CREATE\s+INDEX\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)\s+"
    r"ON\s+harness_audit_log\s+(?:USING\s+(\w+)\s+)?\((.*?)\)\s*;",
    re.IGNORECASE | re.DOTALL,
)


def _indexes_of(sql_paths: list[Path]) -> dict[str, tuple[str, str]]:
    """여러 파일에 흩어진 인덱스 선언을 이름 → (방식, 컬럼식) 으로 모은다.

    인덱스를 보는 이유: 컬럼이 같아도 인덱스가 빠지면 그 에이전트에서만 같은 질의가
    풀스캔이 된다. 형식은 같은데 성질이 다른 것이라 눈으로는 알아채기 어렵다.
    """
    found: dict[str, tuple[str, str]] = {}
    for path in sql_paths:
        text = path.read_text(encoding="utf-8")
        for name, method, columns in _CREATE_INDEX.findall(text):
            normalized = " ".join(columns.split())
            found[name] = ((method or "btree").lower(), normalized)
    return found


def _columns_of(sql_path: Path) -> list[str]:
    """컬럼 이름만. 필드명 대조(Java·Python)에 쓴다."""
    return [column.name for column in _column_defs(sql_path)]


# 타입에서 세미콜론을 제외하는 것이 중요하다. \S+ 로 두면 `VARCHAR(64);` 처럼
# 문장 끝까지 삼키고, 뒤이어 오는 ALTER 문의 제약이 이 컬럼 것으로 딸려 들어온다.
_ALTER_ADD = re.compile(
    r"ALTER\s+TABLE\s+harness_audit_log\s+ADD\s+COLUMN\s+(?:IF\s+NOT\s+EXISTS\s+)?"
    r"(\w+)\s+([^\s;]+)([^;]*);",
    re.IGNORECASE,
)


def _effective_columns(sql_paths: list[Path]) -> list[Column]:
    """여러 파일을 순서대로 적용했을 때 남는 컬럼 목록.

    CREATE TABLE 로 시작해 ALTER TABLE ADD COLUMN 을 덧붙인다. 이미 있는 컬럼을
    다시 ADD 하는 것은 무시한다 (``IF NOT EXISTS`` 의 의미 그대로).

    Flyway 를 쓰는 에이전트는 적용된 마이그레이션을 고칠 수 없어서 컬럼 추가가
    새 파일로 나간다. 그 결과 유효 스키마가 여러 파일에 흩어지고, 첫 파일만 보면
    확장을 놓친 채 "사본이 원본과 다르다"는 잘못된 실패가 난다.
    """
    defs: list[Column] = []
    seen: set[str] = set()

    for path in sql_paths:
        text = path.read_text(encoding="utf-8")

        if "CREATE TABLE" in text.upper():
            for column in _column_defs(path):
                if column.name not in seen:
                    seen.add(column.name)
                    defs.append(column)

        for name, col_type, tail in _ALTER_ADD.findall(text):
            if name in NOT_PART_OF_CONTRACT or name in seen:
                continue
            seen.add(name)
            not_null, default = _parse_constraints(tail)
            # 문장 끝 세미콜론이 타입에 딸려 온다. 붙은 채로 두면 CREATE TABLE 쪽과
            # 같은 타입인데도 다르다고 나온다.
            defs.append(Column(name, col_type.rstrip(",;").upper(), not_null, default))

    return defs


def _java_record_components() -> list[str]:
    """Java record 헤더에서 컴포넌트 이름을 뽑아 snake_case 로."""
    text = JAVA_ENTRY.read_text(encoding="utf-8")
    header = re.search(r"public record AgentAuditEntry\s*\((.*?)\)\s*\{", text, re.DOTALL)
    assert header, "AgentAuditEntry record 헤더를 찾지 못했다"

    names = []
    for part in header.group(1).split(","):
        part = part.strip()
        if not part:
            continue
        names.append(part.split()[-1])
    return [re.sub(r"(?<!^)(?=[A-Z])", "_", n).lower() for n in names]


# ─────────────────────────────────────────────────────────────────────────────
# 1. SQL ↔ Python
# ─────────────────────────────────────────────────────────────────────────────

def test_python_필드가_SQL_컬럼과_정확히_일치한다():
    sql_columns = _columns_of(CANONICAL_SQL)
    py_fields = [f.name for f in dataclasses.fields(AgentAuditEntry)]

    assert py_fields == sql_columns, (
        "Python dataclass 와 테이블 컬럼이 어긋났다.\n"
        f"  SQL:    {sql_columns}\n"
        f"  Python: {py_fields}"
    )


# ─────────────────────────────────────────────────────────────────────────────
# 2. SQL ↔ Java
# ─────────────────────────────────────────────────────────────────────────────

def test_java_레코드가_SQL_컬럼과_정확히_일치한다():
    sql_columns = _columns_of(CANONICAL_SQL)
    java_components = _java_record_components()

    assert java_components == sql_columns, (
        "Java record 와 테이블 컬럼이 어긋났다.\n"
        f"  SQL:  {sql_columns}\n"
        f"  Java: {java_components}"
    )


def test_두_런타임이_같은_필드를_본다():
    # 1·2 가 통과하면 자동으로 성립하지만, 실패했을 때 어느 쪽이 틀렸는지
    # 바로 보이도록 따로 둔다.
    assert [f.name for f in dataclasses.fields(AgentAuditEntry)] == _java_record_components()


# ─────────────────────────────────────────────────────────────────────────────
# 3. 원본 ↔ 사본 (ADR 이 적어둔 "남은 부담")
# ─────────────────────────────────────────────────────────────────────────────

@pytest.mark.parametrize("agent,paths", MIGRATION_COPIES.items())
def test_복사된_마이그레이션이_원본과_같은_컬럼을_선언한다(agent: str, paths: list[Path]):
    for p in paths:
        assert p.exists(), f"{agent} 의 사본이 사라졌다: {p}"

    # 이름·타입·순서·NULL 허용·기본값을 함께 본다.
    # 하나만 어긋나도 나중에 한곳에 모을 수 없다.
    assert _effective_columns(paths) == _column_defs(CANONICAL_SQL), (
        f"{agent} 의 감사 스키마가 원본과 어긋났다. "
        f"원본을 고치고 사본을 안 고쳤을 가능성이 크다."
    )


@pytest.mark.parametrize("agent,paths", MIGRATION_COPIES.items())
def test_복사본이_같은_인덱스를_선언한다(agent: str, paths: list[Path]):
    """컬럼이 같아도 인덱스가 빠지면 그 에이전트에서만 같은 질의가 풀스캔이 된다.

    형식은 같은데 성질이 다른 것이라 눈으로는 알아채기 어렵다. 실제로
    ``actor_roles`` GIN 인덱스는 컬럼만 사본에 옮겨지고 인덱스가 없는 채로
    한동안 남아 있었다(다음 순서 4).
    """
    assert _indexes_of(paths) == _indexes_of([CANONICAL_SQL]), (
        f"{agent} 의 감사 인덱스가 원본과 어긋났다."
    )


def test_원본이_기대하는_인덱스를_모두_갖는다():
    """이름이 바뀌거나 사라지면 사본 대조가 통째로 무의미해지므로 원본을 따로 고정한다."""
    indexes = _indexes_of([CANONICAL_SQL])

    assert set(indexes) == {
        "ix_harness_audit_subject",
        "ix_harness_audit_subject_kind",
        "ix_harness_audit_actor",
        "ix_harness_audit_actor_roles",
        "ix_harness_audit_trace",
    }
    # 역할 조회는 @> 질의라 B-tree 로는 받을 수 없다.
    assert indexes["ix_harness_audit_actor_roles"] == ("gin", "actor_roles jsonb_path_ops")


def test_NOT_NULL_JSONB_컬럼은_기본값을_갖는다():
    """기본값이 빠지면 그 컬럼을 채우지 않는 경로에서 INSERT 가 통째로 거부된다.

    판단은 성공했는데 기록만 사라지는 형태라 가장 나쁘다. dataclass 쪽 보정
    (``_blank_to``)과 짝을 이루는 DB 쪽 방어다.
    """
    for column in _column_defs(CANONICAL_SQL):
        if column.type == "JSONB" and column.not_null:
            assert column.default, f"{column.name} 에 DEFAULT 가 없다"


@pytest.mark.parametrize("agent,paths", MIGRATION_COPIES.items())
def test_복사본도_INSERT_ONLY_트리거를_갖는다(agent: str, paths: list[Path]):
    # 컬럼만 맞고 트리거가 빠지면 그 에이전트의 감사 기록은 고칠 수 있는 상태가 된다.
    # 형식은 같은데 보장이 다른 것이 가장 알아채기 어렵다.
    joined = "\n".join(p.read_text(encoding="utf-8") for p in paths)
    assert "trg_harness_audit_no_update" in joined
    assert "trg_harness_audit_no_delete" in joined


# ─────────────────────────────────────────────────────────────────────────────
# dataclass 동작
# ─────────────────────────────────────────────────────────────────────────────

def test_빈_JSON_필드는_기본값으로_보정된다():
    # NOT NULL JSONB 컬럼에 빈 값이 들어가면 감사 기록만 통째로 유실된다.
    entry = AgentAuditEntry(
        agent_name="consultation",
        subject_type="CONSULT_SESSION",
        subject_id="s-1",
        request_json="",
        output_json="   ",
        tool_calls_json=None,
    )

    assert entry.request_json == "{}"
    assert entry.output_json == "{}"
    assert entry.tool_calls_json == "[]"


def test_기록은_수정할_수_없다():
    entry = AgentAuditEntry(agent_name="a", subject_type="T", subject_id="1")

    with pytest.raises(dataclasses.FrozenInstanceError):
        entry.output_json = '{"고쳤다": true}'  # type: ignore[misc]


def test_한글은_이스케이프되지_않는다():
    # \uXXXX 로 저장되면 사후 조사에서 사람이 읽을 수 없다.
    payload = AgentAuditEntry.json_of({"answer": "예금 금리는 3.5% 입니다"})

    assert "예금 금리" in payload


def test_NoOp_은_기록하지_않지만_깨지지도_않는다():
    # 감사를 끄는 것이 의존하는 쪽을 깨뜨리면 안 된다 — Java 쪽에서 실제로 겪은 실패다.
    log = NoOpAgentAuditLog()
    entry = AgentAuditEntry(agent_name="a", subject_type="T", subject_id="1")

    assert log.record(entry) is None
    assert log.find_latest("T", "1") is None
