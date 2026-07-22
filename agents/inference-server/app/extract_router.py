"""doc-agent L4 구조화 추출 엔드포인트.

POST /extract/structured — 마스킹된 OCR 텍스트 + doc_type → Ollama JSON Schema 강제 필드 추출
GET  /extract/health    — Ollama 연결 상태
"""

from __future__ import annotations

import logging
import os
from typing import Any

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

log = logging.getLogger("extract")

router = APIRouter(prefix="/extract", tags=["extract"])

OLLAMA_BASE_URL = os.getenv("OLLAMA_BASE_URL", "http://host.docker.internal:11434")
OLLAMA_MODEL    = os.getenv("OLLAMA_MODEL", "qwen2.5:3b")

# ── 서류 유형별 JSON Schema 정의 ────────────────────────────────────────────

_SCHEMAS: dict[str, dict] = {
    "ID_CARD": {
        "type": "object",
        "properties": {
            "name":       {"type": "string",  "description": "마스킹된 성명 (예: 홍*동)"},
            "masked_ssn": {"type": "string",  "description": "주민번호 (예: 900101-1******, 반드시 마스킹 유지)"},
            "id_type":    {"type": "string",  "enum": ["주민등록증", "운전면허증", "여권"]},
        },
        "required": ["name"],
    },
    "RESIDENT_REGISTER": {
        "type": "object",
        "properties": {
            "name":       {"type": "string"},
            "address":    {"type": "string", "description": "등록 주소지"},
            "issue_date": {"type": "string", "description": "발급일 (YYYY-MM-DD)"},
        },
        "required": ["name"],
    },
    "EMPLOYMENT_CERT": {
        "type": "object",
        "properties": {
            "name":             {"type": "string"},
            "company":          {"type": "string"},
            "position":         {"type": "string"},
            "hire_date":        {"type": "string", "description": "입사일 (YYYY-MM-DD)"},
            "issue_date":       {"type": "string", "description": "발급일 (YYYY-MM-DD)"},
            "has_official_seal":{"type": "boolean", "description": "직인 날인 여부"},
        },
        "required": ["name", "company"],
    },
    "INCOME_TAX_RECEIPT": {
        "type": "object",
        "properties": {
            "name":             {"type": "string"},
            "employer":         {"type": "string"},
            "annual_income":    {"type": "integer", "description": "총급여 (원)"},
            "attribution_year": {"type": "integer", "description": "귀속연도"},
        },
        "required": ["name", "annual_income"],
    },
    "REGISTRY_DEED": {
        "type": "object",
        "properties": {
            "property_address":   {"type": "string"},
            "owner_name":         {"type": "string"},
            "prior_bond_amount":  {"type": "integer", "description": "을구 채권 합계 (원)"},
            "is_clean_title":     {"type": "boolean", "description": "을구 권리사항 없으면 true"},
        },
        "required": ["property_address"],
    },
    "SALE_CONTRACT": {
        "type": "object",
        "properties": {
            "property_address": {"type": "string"},
            "buyer_name":       {"type": "string"},
            "seller_name":      {"type": "string"},
            "contract_date":    {"type": "string", "description": "계약일 (YYYY-MM-DD)"},
            "sale_price":       {"type": "integer", "description": "매매가 (원)"},
        },
        "required": ["property_address"],
    },
}

_SYSTEM_PROMPT = (
    "당신은 금융 서류 정보 추출 전문가입니다. "
    "주어진 OCR 텍스트에서 정확한 정보만 추출하세요. "
    "불확실한 필드는 null로 반환하세요. "
    "마스킹된 개인정보(****) 는 절대 복원하지 마세요."
)


# ── Pydantic IO ──────────────────────────────────────────────────────────────

class ExtractRequest(BaseModel):
    submission_id: str
    doc_type: str        # ID_CARD | RESIDENT_REGISTER | ...
    masked_text: str


class ExtractResponse(BaseModel):
    submission_id: str
    doc_type: str
    fields: dict[str, Any]
    model: str


# ── 엔드포인트 ───────────────────────────────────────────────────────────────

@router.get("/health")
def extract_health() -> dict:
    try:
        import ollama  # type: ignore
        client = ollama.Client(host=OLLAMA_BASE_URL)
        client.list()
        return {"status": "UP", "model": OLLAMA_MODEL, "ollama_url": OLLAMA_BASE_URL}
    except Exception as e:
        return {"status": "DEGRADED", "reason": str(e)}


@router.post("/structured", response_model=ExtractResponse)
def extract_structured(req: ExtractRequest) -> ExtractResponse:
    schema = _SCHEMAS.get(req.doc_type)
    if schema is None:
        raise HTTPException(status_code=400, detail=f"지원하지 않는 doc_type: {req.doc_type}")

    try:
        import ollama  # type: ignore
    except ImportError:
        raise HTTPException(status_code=503, detail="ollama 패키지 미설치")

    try:
        client = ollama.Client(host=OLLAMA_BASE_URL)
        response = client.chat(
            model=OLLAMA_MODEL,
            messages=[
                {"role": "system", "content": _SYSTEM_PROMPT},
                {"role": "user",   "content": f"서류 유형: {req.doc_type}\n\n텍스트:\n{req.masked_text}"},
            ],
            format=schema,      # Ollama 0.5+ JSON Schema 강제
            options={"temperature": 0},
        )
        import json
        fields = json.loads(response.message.content)
    except Exception as e:
        log.error("Ollama 추출 실패: submissionId=%s err=%s", req.submission_id, e)
        raise HTTPException(status_code=500, detail=f"LLM 추출 실패: {e}")

    return ExtractResponse(
        submission_id=req.submission_id,
        doc_type=req.doc_type,
        fields=fields,
        model=OLLAMA_MODEL,
    )
