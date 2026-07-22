"""
상품 추천 RAG 엔진.

흐름:
  1. build_from_db(products, terms)
     - DB 상품 + 약관 데이터를 자연어 문서로 변환
     - OpenAI text-embedding-3-small 로 배치 임베딩
     - in-memory 인덱스에 저장
  2. search(query, top_k)
     - 사용자 쿼리를 임베딩
     - 코사인 유사도 계산 → top-k 반환
  3. build_cashflow_query(cf)
     - 현금흐름 분석 결과(total_balance, monthly_surplus, monthly_tx_count)를
       RAG 검색용 자연어 쿼리로 변환 (Phase 2)

외부 의존성: openai (requirements.txt 에 있음), 표준 라이브러리 math
테스트: EmbeddingProvider 를 MockEmbeddingProvider 로 교체 가능
"""

import math
from typing import Any, Protocol, runtime_checkable

from langfuse.decorators import observe


# ── 임베딩 프로바이더 인터페이스 ─────────────────────────────────────────────────

@runtime_checkable
class EmbeddingProvider(Protocol):
    def embed_batch(self, texts: list[str]) -> list[list[float]]: ...


class OpenAIEmbeddingProvider:
    """OpenAI text-embedding-3-small 기반 임베딩."""

    MODEL = "text-embedding-3-small"

    def __init__(self, api_key: str) -> None:
        self._api_key = api_key

    def embed_batch(self, texts: list[str]) -> list[list[float]]:
        from openai import OpenAI
        client = OpenAI(api_key=self._api_key)
        resp = client.embeddings.create(input=texts, model=self.MODEL)
        return [item.embedding for item in resp.data]


# ── RAG 엔진 ─────────────────────────────────────────────────────────────────

class ProductRagEngine:
    """
    상품 + 약관 데이터를 인덱싱하고 의미 기반 검색을 제공하는 RAG 엔진.

    사용 예:
        engine = ProductRagEngine(OpenAIEmbeddingProvider(api_key))
        engine.build_from_db(products, terms)
        results = engine.search("목돈 굴릴 수 있는 예금 상품 추천", top_k=3)
    """

    def __init__(self, provider: EmbeddingProvider) -> None:
        self._provider = provider
        self._docs: list[dict[str, Any]] = []   # {type, text, data}
        self._vecs: list[list[float]] = []

    # ── 인덱스 빌드 ────────────────────────────────────────────────────────────

    def build_from_db(
        self,
        products: list[dict[str, Any]],
        terms: list[dict[str, Any]] | None = None,
    ) -> None:
        """DB 상품 + 약관 데이터로 인덱스를 빌드한다. 기존 인덱스는 교체된다."""
        docs: list[dict[str, Any]] = []

        for p in products:
            docs.append({"type": "product", "text": self._product_to_text(p), "data": p})

        for t in (terms or []):
            docs.append({"type": "term", "text": self._term_to_text(t), "data": t})

        if not docs:
            self._docs = []
            self._vecs = []
            return

        texts = [d["text"] for d in docs]
        vecs = self._provider.embed_batch(texts)

        self._docs = docs
        self._vecs = vecs

    def is_ready(self) -> bool:
        return len(self._docs) > 0

    def size(self) -> int:
        return len(self._docs)

    # ── 검색 ──────────────────────────────────────────────────────────────────

    @observe(name="rag-search")
    def search(
        self,
        query: str,
        top_k: int = 5,
        doc_type: str | None = None,
    ) -> list[dict[str, Any]]:
        """쿼리와 의미적으로 가장 유사한 문서 top_k 개를 반환한다.

        Args:
            query    : 검색 질문 (자연어)
            top_k    : 반환할 최대 문서 수
            doc_type : "product" | "term" | None(전체)
        Returns:
            각 원본 data dict + "_score"(유사도 0~1), "_type" 필드 추가된 리스트
        """
        if not self.is_ready():
            return []

        q_vec = self._provider.embed_batch([query])[0]

        scored: list[tuple[int, float]] = []
        for i, v in enumerate(self._vecs):
            if doc_type is not None and self._docs[i]["type"] != doc_type:
                continue
            scored.append((i, self._cosine(q_vec, v)))

        scored.sort(key=lambda x: x[1], reverse=True)

        return [
            {**self._docs[i]["data"], "_score": round(score, 4), "_type": self._docs[i]["type"]}
            for i, score in scored[:top_k]
        ]

    # ── 현금흐름 → 쿼리 변환 (Phase 2) ──────────────────────────────────────────

    @staticmethod
    def build_cashflow_query(cf: dict[str, Any]) -> str:
        """현금흐름 분석 결과를 RAG 검색용 자연어 쿼리로 변환한다.

        Args:
            cf: {total_balance, monthly_surplus, monthly_tx_count, has_data}
        """
        total_balance    = float(cf.get("total_balance", 0))
        monthly_surplus  = float(cf.get("monthly_surplus", 0))
        monthly_tx_count = float(cf.get("monthly_tx_count", 0))

        parts: list[str] = []

        # 잔액 규모 → 상품 유형 힌트
        if total_balance >= 10_000_000:
            parts.append("목돈이 있어 정기예금 또는 고금리 예금에 가입하고 싶다")
        elif total_balance >= 1_000_000:
            parts.append("소액 목돈으로 예금 상품을 찾고 있다")

        # 월 잉여자금 → 적금 납입 가능성
        if monthly_surplus >= 500_000:
            parts.append(f"매월 {monthly_surplus:,.0f}원 정도 정기적으로 납입할 수 있다")
        elif monthly_surplus >= 100_000:
            parts.append(f"매월 {monthly_surplus:,.0f}원 소액 적금을 원한다")
        elif monthly_surplus > 0:
            parts.append("소액이라도 꾸준히 저축하고 싶다")
        else:
            parts.append("여유자금이 많지 않아 부담이 적은 상품을 원한다")

        # 거래 빈도 → 정기 납입 패턴
        if monthly_tx_count >= 5:
            parts.append("정기적인 입출금 거래가 많아 자동이체 적금이 맞을 것 같다")
        elif monthly_tx_count >= 2:
            parts.append("가끔 거래하는 편이다")

        if not parts:
            parts.append("적합한 수신 금융 상품을 추천해 달라")

        return ". ".join(parts) + "."

    # ── 내부 유틸 ─────────────────────────────────────────────────────────────

    @staticmethod
    def _product_to_text(p: dict[str, Any]) -> str:
        fields = [
            ("상품명",        p.get("deposit_product_name") or p.get("product_name", "")),
            ("유형",          p.get("deposit_product_type") or p.get("product_type", "")),
            ("설명",          p.get("description", "")),
            ("기본금리",      f"{p.get('base_interest_rate', '')}%"),
            ("최소가입금액",  f"{p.get('min_join_amount', '')}원"),
            ("최대가입금액",  f"{p.get('max_join_amount', '')}원"),
            ("최소기간",      f"{p.get('min_period_month', '')}개월"),
            ("최대기간",      f"{p.get('max_period_month', '')}개월"),
        ]
        extras: list[str] = []
        if p.get("is_early_termination_allowed"):
            extras.append("중도해지 가능")
        if p.get("is_tax_benefit_available"):
            extras.append("세제혜택 있음")

        base = " | ".join(f"{k}: {v}" for k, v in fields if v and str(v) not in ("%", "원", "개월", "%"))
        return f"{base} | {', '.join(extras)}" if extras else base

    @staticmethod
    def _term_to_text(t: dict[str, Any]) -> str:
        fields = [
            ("약관명", t.get("special_term_name", "")),
            ("내용",   t.get("special_term_content", "")),
            ("요약",   t.get("special_term_summary", "")),
        ]
        return " | ".join(f"{k}: {v}" for k, v in fields if v)

    @staticmethod
    def _cosine(a: list[float], b: list[float]) -> float:
        dot    = sum(x * y for x, y in zip(a, b))
        norm_a = math.sqrt(sum(x * x for x in a))
        norm_b = math.sqrt(sum(x * x for x in b))
        if norm_a == 0.0 or norm_b == 0.0:
            return 0.0
        return dot / (norm_a * norm_b)
