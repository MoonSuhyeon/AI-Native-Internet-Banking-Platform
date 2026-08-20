"""소스에서 REST 엔드포인트를 뽑아 `docs/api-spec.md` 를 다시 쓴다.

**왜 스크립트인가.** 이 문서는 원래 "소스 컨트롤러에서 자동 추출 후 정리" 라고 적혀
있었지만 추출기가 레포에 없었다. 그래서 손으로 고쳐야 했고, 고치지 않은 채 서비스가
병합되자 문서가 **없는 서비스를 부르라고 안내하는** 상태가 됐다 —
`deposit-service`(8082)와 `payment-service`(8080)는 `core-banking` 하나로 합쳐졌고,
`advisory-service` 는 loan-service 안으로 들어갔으며, `master-service` 는 레포에
존재한 적이 없다.

문서를 다시 손으로 맞추면 다음 병합 때 또 어긋난다. 그래서 뽑는 방법 자체를 남긴다.

**한계를 분명히 한다.** 정규식으로 읽으므로 다음은 못 본다.

- 상수·변수로 조립한 경로 (`@GetMapping(PATH_PREFIX + "/x")`)
- 런타임에 등록하는 라우트
- FastAPI 의 `include_router(prefix=...)` 중첩

전수 목록이 아니라 **경로 인벤토리**다. 요청·응답 규약은 서비스별 문서가 맡는다.

사용:
    python scripts/extract_api_spec.py           # 문서를 다시 쓴다
    python scripts/extract_api_spec.py --check   # 어긋나면 1 로 끝난다(CI용)
"""

from __future__ import annotations

import argparse
import io
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOC = ROOT / "docs" / "api-spec.md"

#: 실제로 HTTP 를 여는 것들. 이름은 문서에 그대로 나간다.
#:
#: `advisory-service` 와 `master-service` 가 없는 것은 실수가 아니다 — 앞은
#: loan-service 로 병합됐고(`agents/advisory-service/src` 는 비어 있다), 뒤는 레포에
#: 존재한 적이 없다. 예전 문서가 둘을 서비스로 소개하고 있었다.
JAVA_SOURCES = [
    ("Customer Service (고객·인증·인증서)", "customer-service", "services/customer-service"),
    ("Core Banking (수신·계좌·예적금·이체)", "core-banking", "services/core-banking"),
    ("Loan Service (여신·대출·심사자문)", "loan-service", "services/loan-service"),
    ("FDS Detector (이상거래 탐지)", "fds-detector", "services/fds-detector"),
    ("Auto Loan Review (AI 자동심사)", "auto-loan-review", "agents/auto-loan-review"),
    ("Review AI Gateway (심사 AI 게이트웨이)", "review-ai-gateway", "agents/review-ai-gateway"),
    ("Doc Agent (서류 제출·검토)", "doc-agent", "agents/doc-agent"),
]

PYTHON_SOURCES = [
    ("Consultation (상담·챗봇)", "consultation", "agents/consultation/app/main.py"),
    ("Fraud Investigation (조사 에이전트)", "fraud-investigation", "agents/fraud-investigation-agent/src/agent/api.py"),
    ("Goal Agent (목표 상담)", "goal-agent", "agents/goal-agent/app/main.py"),
    ("Inference Server (모델 추론)", "inference-server", "agents/inference-server/app/main.py"),
]

HTTP_METHODS = ["Get", "Post", "Put", "Patch", "Delete"]

CLASS_MAPPING = re.compile(r'@RequestMapping\s*\(\s*(?:value\s*=\s*)?"([^"]*)"')
METHOD_MAPPING = re.compile(
    r'@(' + "|".join(HTTP_METHODS) + r')Mapping\s*(?:\(\s*(?:value\s*=\s*|path\s*=\s*)?"([^"]*)"|\()?'
)
CONTEXT_PATH = re.compile(r'context-path:\s*(\S+)')

PY_ROUTE = re.compile(
    r'@(?:app|router)\.(get|post|put|patch|delete)\s*\(\s*[\'"]([^\'"]+)[\'"]'
)


def _join(base: str, path: str) -> str:
    if not base:
        return path or "/"
    if not path:
        return base
    return base.rstrip("/") + "/" + path.lstrip("/")


def java_context_path(module: Path) -> str:
    """`server.servlet.context-path` 를 응답 경로 앞에 붙인다.

    core-banking 이 `/api` 를 쓴다. 이걸 빼면 문서의 경로가 실제로 부르는 경로와
    달라지는데, 그 차이가 정확히 게이트웨이 라우트를 잘못 적게 만드는 자리다.
    """
    for name in ("application.yml", "application.yaml"):
        f = module / "src" / "main" / "resources" / name
        if f.exists():
            m = CONTEXT_PATH.search(io.open(f, encoding="utf-8").read())
            if m:
                return m.group(1).strip().rstrip("/")
    return ""


def scan_java(module_dir: Path) -> list[tuple[str, str, str]]:
    """(컨트롤러, METHOD, 경로) 목록."""
    out: list[tuple[str, str, str]] = []
    ctx = java_context_path(module_dir)
    src = module_dir / "src" / "main" / "java"
    if not src.is_dir():
        return out

    for f in sorted(src.rglob("*.java")):
        text = io.open(f, encoding="utf-8").read()
        if "@RestController" not in text:
            continue
        base = ""
        m = CLASS_MAPPING.search(text)
        if m:
            base = m.group(1)
        controller = f.stem
        for mm in METHOD_MAPPING.finditer(text):
            verb = mm.group(1).upper()
            path = mm.group(2) or ""
            out.append((controller, verb, ctx + _join(base, path)))
    return out


def scan_python(file_path: Path) -> list[tuple[str, str, str]]:
    if not file_path.exists():
        return []
    text = io.open(file_path, encoding="utf-8").read()
    return [
        (file_path.stem, verb.upper(), path)
        for verb, path in PY_ROUTE.findall(text)
    ]


def render() -> str:
    sections: list[str] = []
    toc: list[str] = []
    total = 0
    controllers = 0

    def add(title: str, anchor: str, rows: list[tuple[str, str, str]]) -> None:
        nonlocal total, controllers
        total += len(rows)
        by_controller: dict[str, list[tuple[str, str]]] = {}
        for controller, verb, path in rows:
            by_controller.setdefault(controller, []).append((verb, path))
        controllers += len(by_controller)

        toc.append(f"- [{title}](#{anchor}) — {len(rows)}개")
        body = [f'<a id="{anchor}"></a>\n', f"## {title}\n"]
        for controller in sorted(by_controller):
            body.append(f"### {controller}\n")
            body.append("| Method | Path |")
            body.append("|---|---|")
            for verb, path in sorted(by_controller[controller], key=lambda r: (r[1], r[0])):
                body.append(f"| `{verb}` | `{path}` |")
            body.append("")
        sections.append("\n".join(body))

    for title, anchor, rel in JAVA_SOURCES:
        add(title, anchor, scan_java(ROOT / rel))
    for title, anchor, rel in PYTHON_SOURCES:
        add(title, anchor, scan_python(ROOT / rel))

    header = f"""# Internet Banking — 전체 API 명세서

> **이 문서는 `scripts/extract_api_spec.py` 가 소스에서 뽑아 쓴다.**
> 손으로 고치지 말고 스크립트를 돌린다 — 예전에 손으로 관리하다가 서비스 병합을
> 반영하지 못해, 문서를 보고 붙이면 **없는 서비스를 부르게** 되는 상태였다.
>
> ```
> python scripts/extract_api_spec.py          # 다시 쓴다
> python scripts/extract_api_spec.py --check  # 어긋나면 실패한다
> ```
>
> **경로 인벤토리이지 계약서가 아니다.** 요청·응답 규약은 서비스별 문서가 맡는다:
> [customer](customer-service-api-spec.md) ·
> core-banking [수신](core-banking-deposit-api-spec.md)·[이체](core-banking-payment-api-spec.md) ·
> [loan](loan-service-api-spec.md) · [소규모 서비스](misc-services-api-spec.md).
>
> 정규식으로 읽으므로 상수로 조립한 경로와 런타임 등록 라우트는 못 본다.

## 지금의 서비스 구성

| 서비스 | 포트 | 비고 |
|---|---|---|
| `api-gateway` | 8088(로컬) / 8080 | 유일한 외부 진입점. JWT 검증 후 신원 헤더를 주입한다 |
| `customer-service` | 8081 | 고객·인증·인증서·뱅킹 편의기능 |
| `core-banking` | 8082 | **`deposit-service` + `payment-service` 병합** ([결정](decisions/core-banking-merge.md)). `context-path: /api` |
| `loan-service` | 8083 | **`advisory-service` 를 포함한다** — 별도 서비스가 아니다 |
| `fds-detector` | — | 결제 이벤트 소비. 조사 에이전트와 분리 |
| 사이드카(상담·조사·서류·자동심사 등) | 8087·8090 등 | 호스트 포트를 열지 않는다. 게이트웨이 경유 |

> 예전 문서가 소개하던 `deposit-service`·`payment-service`·`advisory-service`·
> `master-service` 는 **지금 없다.** 앞의 셋은 병합됐고 `master-service` 는 레포에
> 존재한 적이 없다.

- **서비스 수**: {len(JAVA_SOURCES) + len(PYTHON_SOURCES)}
- **컨트롤러 수**: {controllers}
- **엔드포인트 수**: {total}

---

## 목차
""" + "\n".join(toc) + "\n\n---\n\n"

    return header + "\n---\n\n".join(sections)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="어긋나면 1 로 끝난다")
    args = parser.parse_args()

    rendered = render()
    if args.check:
        current = io.open(DOC, encoding="utf-8").read() if DOC.exists() else ""
        if current.strip() != rendered.strip():
            print("docs/api-spec.md 가 소스와 어긋난다. "
                  "python scripts/extract_api_spec.py 를 돌려 다시 써야 한다.")
            return 1
        print("docs/api-spec.md 는 소스와 일치한다.")
        return 0

    io.open(DOC, "w", encoding="utf-8", newline="\n").write(rendered)
    print(f"{DOC.relative_to(ROOT)} 를 다시 썼다.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
