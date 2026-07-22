"""doc-agent 위변조 시그널 탐지 엔드포인트.

POST /forgery/analyze — 이미지/PDF base64 → 시그널 목록 + 집계 점수
GET  /forgery/health  — 탐지 엔진 상태
"""

from __future__ import annotations

import base64
import io
import logging
import struct
from typing import Any

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

log = logging.getLogger("forgery")

router = APIRouter(prefix="/forgery", tags=["forgery"])


# ── Pydantic IO ──────────────────────────────────────────────────────────────

class ForgeryRequest(BaseModel):
    submission_id: str
    doc_type:      str
    file_b64:      str     # base64 인코딩 원본 파일 (PDF or 이미지)
    content_type:  str     # application/pdf | image/jpeg | image/png


class SignalItem(BaseModel):
    category:  str    # META | VISUAL | SEMANTIC
    type:      str    # META_EDIT_TOOL | ELA_HIGH | COPY_MOVE | FONT_INCONSISTENCY
    score:     float  # 0.0 ~ 1.0
    evidence:  str


class ForgeryResponse(BaseModel):
    submission_id:   str
    aggregate_score: float
    signals:         list[SignalItem]


# ── 탐지 함수들 ──────────────────────────────────────────────────────────────

def _analyze_pdf_metadata(file_bytes: bytes) -> list[SignalItem]:
    """PDF Producer/Creator/ModifyDate 메타데이터 시그널."""
    signals: list[SignalItem] = []
    try:
        import pikepdf  # type: ignore
        pdf = pikepdf.open(io.BytesIO(file_bytes))
        meta = pdf.docinfo

        # Producer/Creator 편집 도구 검출
        edit_tools = ["photoshop", "gimp", "inkscape", "illustrator", "paint"]
        for field in ["/Producer", "/Creator"]:
            val = str(meta.get(field, "")).lower()
            if any(tool in val for tool in edit_tools):
                signals.append(SignalItem(
                    category="META", type="META_EDIT_TOOL", score=0.4,
                    evidence=f"{field}={val[:80]}"
                ))

        # ModifyDate > CreateDate + 5분
        create = meta.get("/CreationDate")
        modify = meta.get("/ModDate")
        if create and modify and str(modify) > str(create):
            diff_hint = f"CreationDate={str(create)[:16]} ModDate={str(modify)[:16]}"
            signals.append(SignalItem(
                category="META", type="META_MODIFY_AFTER_CREATE", score=0.2,
                evidence=diff_hint
            ))
        pdf.close()
    except ImportError:
        log.debug("pikepdf 미설치 — PDF 메타데이터 분석 SKIP")
    except Exception as e:
        log.warning("PDF 메타데이터 분석 오류: %s", e)
    return signals


def _analyze_ela(img_bytes: bytes) -> list[SignalItem]:
    """ELA (Error Level Analysis): 재압축 차이로 편집 영역 검출."""
    signals: list[SignalItem] = []
    try:
        import numpy as np
        from PIL import Image, ImageFilter  # type: ignore

        original = Image.open(io.BytesIO(img_bytes)).convert("RGB")

        # JPEG 75로 재압축
        buf = io.BytesIO()
        original.save(buf, format="JPEG", quality=75)
        buf.seek(0)
        recompressed = Image.open(buf).convert("RGB")

        # 차이 이미지 (절댓값)
        orig_arr = np.array(original, dtype=np.float32)
        recomp_arr = np.array(recompressed, dtype=np.float32)
        diff = np.abs(orig_arr - recomp_arr)

        mean_diff  = float(diff.mean())
        max_diff   = float(diff.max())
        high_ratio = float((diff > 30).mean())   # 30 이상 차이 픽셀 비율

        # 편집 영역이 있으면 특정 구역의 ELA 값이 주변보다 두드러짐
        if high_ratio > 0.05 and mean_diff > 5.0:
            score = min(0.8, high_ratio * 4)
            signals.append(SignalItem(
                category="VISUAL", type="ELA_HIGH", score=round(score, 2),
                evidence=f"mean_diff={mean_diff:.1f} max_diff={max_diff:.0f} high_ratio={high_ratio:.3f}"
            ))
    except ImportError:
        log.debug("Pillow 미설치 — ELA SKIP")
    except Exception as e:
        log.warning("ELA 분석 오류: %s", e)
    return signals


def _analyze_copy_move(img_bytes: bytes) -> list[SignalItem]:
    """Copy-Move 탐지: 이미지 내 동일 블록 반복 검출 (도장·서명 복붙)."""
    signals: list[SignalItem] = []
    try:
        import cv2  # type: ignore  # opencv-python-headless
        import numpy as np

        nparr = np.frombuffer(img_bytes, np.uint8)
        img = cv2.imdecode(nparr, cv2.IMREAD_GRAYSCALE)
        if img is None:
            return signals

        # DCT 기반 블록 해시 비교 (간소화 구현)
        block_size = 32
        h, w = img.shape
        hashes: dict[bytes, tuple[int, int]] = {}
        duplicates = 0

        for y in range(0, h - block_size, block_size // 2):
            for x in range(0, w - block_size, block_size // 2):
                block = img[y:y+block_size, x:x+block_size]
                dct   = cv2.dct(block.astype(np.float32))
                # 상위 8×8 계수만 해시
                coeff = dct[:8, :8].flatten()
                mean  = coeff.mean()
                bits  = bytes([1 if v >= mean else 0 for v in coeff])
                if bits in hashes:
                    prev_y, prev_x = hashes[bits]
                    dist = ((y - prev_y) ** 2 + (x - prev_x) ** 2) ** 0.5
                    if dist > block_size * 2:   # 인접 블록 제외
                        duplicates += 1
                else:
                    hashes[bits] = (y, x)

        total_blocks = ((h // (block_size // 2)) * (w // (block_size // 2)))
        dup_ratio = duplicates / max(total_blocks, 1)

        if dup_ratio > 0.02:
            score = min(0.7, dup_ratio * 10)
            signals.append(SignalItem(
                category="VISUAL", type="COPY_MOVE", score=round(score, 2),
                evidence=f"duplicate_blocks={duplicates} ratio={dup_ratio:.4f}"
            ))
    except ImportError:
        log.debug("opencv 미설치 — Copy-Move SKIP")
    except Exception as e:
        log.warning("Copy-Move 분석 오류: %s", e)
    return signals


def _analyze_font_consistency(img_bytes: bytes) -> list[SignalItem]:
    """폰트 높이 분산 기반 일관성 검사 (OCR bbox 활용)."""
    # bbox 정보가 없으면 이미지 행 밝기 분산으로 간접 측정
    signals: list[SignalItem] = []
    try:
        import numpy as np
        from PIL import Image  # type: ignore

        img = Image.open(io.BytesIO(img_bytes)).convert("L")
        arr = np.array(img, dtype=np.float32)

        # 행별 평균 밝기 — 텍스트 행에서 낮아야 정상
        row_means = arr.mean(axis=1)
        text_rows = row_means[row_means < 200]   # 어두운 행 = 텍스트

        if len(text_rows) > 10:
            variance = float(text_rows.std())
            if variance > 60:
                score = min(0.4, (variance - 60) / 100)
                signals.append(SignalItem(
                    category="VISUAL", type="FONT_INCONSISTENCY", score=round(score, 2),
                    evidence=f"row_brightness_std={variance:.1f}"
                ))
    except Exception as e:
        log.debug("폰트 일관성 분석 오류: %s", e)
    return signals


def _aggregate(signals: list[SignalItem]) -> float:
    """카테고리 다양성 가중 집계 (단일 카테고리 독점 방지)."""
    if not signals:
        return 0.0
    by_cat: dict[str, float] = {}
    for s in signals:
        by_cat[s.category] = max(by_cat.get(s.category, 0.0), s.score)
    # 카테고리 수에 따라 보너스 (다양성)
    diversity_bonus = min(0.2, (len(by_cat) - 1) * 0.1)
    raw = sum(by_cat.values()) / len(by_cat)
    return min(1.0, round(raw + diversity_bonus, 3))


# ── 엔드포인트 ───────────────────────────────────────────────────────────────

@router.get("/health")
def forgery_health() -> dict:
    available = []
    try:
        import pikepdf; available.append("pikepdf")  # type: ignore
    except ImportError:
        pass
    try:
        import cv2; available.append("opencv")  # type: ignore
    except ImportError:
        pass
    try:
        from PIL import Image; available.append("pillow")  # type: ignore
    except ImportError:
        pass
    return {"status": "UP", "engines": available}


@router.post("/analyze", response_model=ForgeryResponse)
def analyze(req: ForgeryRequest) -> ForgeryResponse:
    try:
        file_bytes = base64.b64decode(req.file_b64)
    except Exception:
        raise HTTPException(status_code=400, detail="file_b64 디코딩 실패")

    signals: list[SignalItem] = []

    is_pdf = req.content_type == "application/pdf"
    is_img = req.content_type in ("image/jpeg", "image/png")

    if is_pdf:
        signals += _analyze_pdf_metadata(file_bytes)

    if is_img:
        signals += _analyze_ela(file_bytes)
        signals += _analyze_copy_move(file_bytes)
        signals += _analyze_font_consistency(file_bytes)

    aggregate = _aggregate(signals)

    log.info("위변조 분석 완료: submissionId=%s signals=%d score=%.3f",
             req.submission_id, len(signals), aggregate)

    return ForgeryResponse(
        submission_id=req.submission_id,
        aggregate_score=aggregate,
        signals=signals,
    )
