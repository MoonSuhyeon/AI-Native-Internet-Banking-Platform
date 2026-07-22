"""Langfuse 연결 테스트 스크립트 (SDK v4)"""
import os
from dotenv import load_dotenv

load_dotenv(os.path.join(os.path.dirname(__file__), '..', '.env'))

os.environ.setdefault("LANGFUSE_SECRET_KEY", os.getenv("LANGFUSE_SECRET_KEY", ""))
os.environ.setdefault("LANGFUSE_PUBLIC_KEY", os.getenv("LANGFUSE_PUBLIC_KEY", ""))
os.environ.setdefault("LANGFUSE_HOST", os.getenv("LANGFUSE_HOST", "http://localhost:3001"))

from langfuse.decorators import observe, langfuse_context
from langfuse import Langfuse

langfuse = Langfuse(
    secret_key=os.environ["LANGFUSE_SECRET_KEY"],
    public_key=os.environ["LANGFUSE_PUBLIC_KEY"],
    host=os.environ.get("LANGFUSE_HOST", "http://localhost:3001"),
)

@observe()
def rag_search(query: str):
    langfuse_context.update_current_observation(
        input={"query": query},
        output={"chunks": ["정기적금 기본금리 3.5%", "우대금리 최대 1.0%"]},
    )
    return ["정기적금 기본금리 3.5%", "우대금리 최대 1.0%"]

@observe()
def llm_call(query: str, context: list):
    langfuse_context.update_current_observation(
        model="gpt-4o-mini",
        input={"query": query, "context": context},
        output="정기적금 기본금리는 3.5%이며 우대금리 최대 1.0%가 적용됩니다.",
        usage={"input": 20, "output": 30},
    )
    return "정기적금 기본금리는 3.5%이며 우대금리 최대 1.0%가 적용됩니다."

@observe(name="test-consultation")
def handle_message(query: str):
    chunks = rag_search(query)
    answer = llm_call(query, chunks)
    return answer

result = handle_message("정기적금 금리 알려줘")
print(f"응답: {result}")

langfuse.flush()
print("✅ Langfuse 전송 완료 — http://localhost:3001 에서 Traces 확인하세요")
