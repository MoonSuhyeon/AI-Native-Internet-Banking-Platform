"""환경변수 로딩 — 프로젝트 루트 .env 를 자동 탐색."""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv


def _find_project_root(start: Path) -> Path:
    """`settings.gradle` 이 있는 가장 가까운 상위 경로 반환."""
    for p in [start, *start.parents]:
        if (p / "settings.gradle").exists():
            return p
    return start


def _find_env_file(worktree_root: Path) -> Path | None:
    """`.env` 탐색.

    1순위: 현재 worktree 의 .env
    2순위: worktree 구조면 메인 레포 .env (`.claude/worktrees/<id>/` 패턴 인식)
    """
    here = worktree_root / ".env"
    if here.exists():
        return here

    parts = worktree_root.parts
    if ".claude" in parts:
        idx = parts.index(".claude")
        if idx >= 1 and idx + 1 < len(parts) and parts[idx + 1] == "worktrees":
            main_root = Path(*parts[:idx])
            candidate = main_root / ".env"
            if candidate.exists():
                return candidate
    return None


PROJECT_ROOT = _find_project_root(Path(__file__).resolve())
_env_path = _find_env_file(PROJECT_ROOT)
if _env_path:
    load_dotenv(_env_path)

DATA_DIR = PROJECT_ROOT / "data" / "external" / "korean"


@dataclass(frozen=True)
class ApiKeys:
    ecos: str | None = os.getenv("KOREA_BANK_API_KEY")
    kosis: str | None = os.getenv("KOSIS_API_KEY")
    data_go_kr: str | None = os.getenv("PUBLIC_DATA_API_KEY")
    fisis: str | None = os.getenv("FISIS_API_KEY")

    def require(self, name: str) -> str:
        key = getattr(self, name, None)
        if not key:
            raise RuntimeError(
                f"환경변수 누락: {name} (.env 또는 시스템 환경에 설정 필요)"
            )
        return key


KEYS = ApiKeys()
