"""doc-agent 전용 OCR 엔드포인트.

POST /ocr/extract       — 이미지·PDF 바이트(base64) → 텍스트 영역 리스트 (PaddleOCR)
POST /ocr/extract-table — 등기부등본 테이블 구조 파싱 (PP-StructureV2)
GET  /ocr/health        — PaddleOCR 로드 상태
"""

from __future__ import annotations

import base64
import io
import logging
from typing import Any

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

log = logging.getLogger("ocr")

router = APIRouter(prefix="/ocr", tags=["ocr"])

# PaddleOCR 지연 로드 — 미설치 환경에서 서버 기동은 가능하게 유지
_ocr_engine: Any = None


def _get_engine() -> Any:
    global _ocr_engine
    if _ocr_engine is None:
        try:
            from paddleocr import PaddleOCR  # type: ignore
            _ocr_engine = PaddleOCR(use_angle_cls=True, lang="korean", show_log=False)
            log.info("PaddleOCR 로드 완료")
        except ImportError:
            log.warning("paddleocr 미설치 — /ocr/extract 호출 시 503 반환")
    return _ocr_engine


class OcrRequest(BaseModel):
    image_b64: str          # base64 인코딩 이미지(jpg/png) 또는 PDF 첫 페이지
    submission_id: str


class OcrRegion(BaseModel):
    text: str
    confidence: float
    bbox: list[list[int]]   # [[x1,y1],[x2,y2],[x3,y3],[x4,y4]]


class OcrResponse(BaseModel):
    submission_id: str
    regions: list[OcrRegion]
    engine: str = "paddleocr-ko"


@router.get("/health")
def ocr_health() -> dict:
    engine = _get_engine()
    return {"status": "UP" if engine is not None else "DEGRADED", "engine": "paddleocr-ko"}


@router.post("/extract", response_model=OcrResponse)
def extract(req: OcrRequest) -> OcrResponse:
    engine = _get_engine()
    if engine is None:
        raise HTTPException(status_code=503, detail="PaddleOCR 미설치")

    try:
        img_bytes = base64.b64decode(req.image_b64)
    except Exception:
        raise HTTPException(status_code=400, detail="image_b64 디코딩 실패")

    import numpy as np
    from PIL import Image  # type: ignore

    try:
        img = Image.open(io.BytesIO(img_bytes)).convert("RGB")
        img_array = np.array(img)
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"이미지 파싱 실패: {e}")

    try:
        raw = engine.ocr(img_array, cls=True)
    except Exception as e:
        log.error("PaddleOCR 추론 오류: %s", e)
        raise HTTPException(status_code=500, detail="OCR 추론 실패")

    regions: list[OcrRegion] = []
    for page in (raw or []):
        for line in (page or []):
            bbox_raw, (text, conf) = line
            regions.append(OcrRegion(
                text=text,
                confidence=float(conf),
                bbox=[[int(p[0]), int(p[1])] for p in bbox_raw],
            ))

    return OcrResponse(submission_id=req.submission_id, regions=regions)


# ── PP-StructureV2 테이블 파싱 ───────────────────────────────────────────────

_structure_engine: Any = None


def _get_structure_engine() -> Any:
    global _structure_engine
    if _structure_engine is None:
        try:
            from paddleocr import PPStructure  # type: ignore
            _structure_engine = PPStructure(
                table=True, ocr=True, lang="ch",  # 한국어는 ch 모델로 충분
                show_log=False,
            )
            log.info("PP-StructureV2 로드 완료")
        except ImportError:
            log.warning("paddleocr(PPStructure) 미설치")
        except Exception as e:
            log.warning("PP-StructureV2 초기화 실패: %s", e)
    return _structure_engine


class TableOcrRequest(BaseModel):
    image_b64: str
    submission_id: str


class TableOcrResponse(BaseModel):
    submission_id: str
    table_text: str          # 셀 내용을 줄 단위로 flatten한 텍스트 (LLM 입력용)
    table_count: int


@router.post("/extract-table", response_model=TableOcrResponse)
def extract_table(req: TableOcrRequest) -> TableOcrResponse:
    """등기부등본 갑구·을구 테이블 파싱. PP-StructureV2 미설치 시 일반 OCR로 fallback."""
    try:
        img_bytes = base64.b64decode(req.image_b64)
    except Exception:
        raise HTTPException(status_code=400, detail="image_b64 디코딩 실패")

    import numpy as np
    from PIL import Image  # type: ignore

    try:
        img = Image.open(io.BytesIO(img_bytes)).convert("RGB")
        img_array = np.array(img)
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"이미지 파싱 실패: {e}")

    engine = _get_structure_engine()
    if engine is None:
        # fallback: 일반 OCR
        log.info("PP-Structure 미사용 — 일반 OCR fallback: submissionId=%s", req.submission_id)
        plain_engine = _get_engine()
        if plain_engine is None:
            return TableOcrResponse(submission_id=req.submission_id, table_text="", table_count=0)
        try:
            raw = plain_engine.ocr(img_array, cls=True)
            lines = []
            for page in (raw or []):
                for line in (page or []):
                    _, (text, conf) = line
                    if conf >= 0.6:
                        lines.append(text)
            return TableOcrResponse(
                submission_id=req.submission_id,
                table_text="\n".join(lines),
                table_count=0,
            )
        except Exception as e:
            log.error("fallback OCR 실패: %s", e)
            return TableOcrResponse(submission_id=req.submission_id, table_text="", table_count=0)

    try:
        result = engine(img_array)
    except Exception as e:
        log.error("PP-Structure 추론 오류: submissionId=%s err=%s", req.submission_id, e)
        raise HTTPException(status_code=500, detail=f"PP-Structure 추론 실패: {e}")

    lines: list[str] = []
    table_count = 0
    for region in (result or []):
        rtype = region.get("type", "").lower()
        if rtype == "table":
            table_count += 1
            # HTML 테이블에서 셀 텍스트 추출
            html: str = region.get("res", {}).get("html", "")
            if html:
                import re
                cells = re.findall(r"<td[^>]*>(.*?)</td>", html, re.DOTALL)
                for cell in cells:
                    clean = re.sub(r"<[^>]+>", "", cell).strip()
                    if clean:
                        lines.append(clean)
        elif rtype in ("text", "title"):
            res = region.get("res", [])
            for item in (res if isinstance(res, list) else []):
                try:
                    text, conf = item[1]
                    if conf >= 0.6:
                        lines.append(text)
                except (TypeError, ValueError, IndexError):
                    pass

    table_text = "\n".join(lines)
    log.info("PP-Structure 완료: submissionId=%s tables=%d cells=%d",
             req.submission_id, table_count, len(lines))

    return TableOcrResponse(
        submission_id=req.submission_id,
        table_text=table_text,
        table_count=table_count,
    )
