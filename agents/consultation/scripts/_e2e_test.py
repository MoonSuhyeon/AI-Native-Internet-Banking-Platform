"""
실제 PostgreSQL 연결 E2E 테스트
챗봇 시작 → 기능 실행 → AGENT 이관 → 대기열 → 상담사 연결 → 메시지 → 종료
"""
import io
import sys
import httpx

if hasattr(sys.stdout, "buffer"):
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

BASE = "http://localhost:8087"

def check(label, resp, expected_status=200):
    if resp.status_code != expected_status:
        print(f"  [FAIL] {label}: HTTP {resp.status_code}")
        print(f"     {resp.text[:300]}")
        return None
    data = resp.json()
    return data


print("\n" + "="*60)
print("  E2E 실제 PostgreSQL 연결 테스트")
print("="*60)

# ── 전제: 시나리오 시드 ────────────────────────────────────────────────────────
r = httpx.post(f"{BASE}/chatbot/scenarios/default")
d = check("시나리오 시드", r)
print(f"\n[0] 시나리오 시드: scenario_id={d['scenario_id']}, first_node_id={d['first_node_id']}")

# ── 1. 챗봇 시작 ───────────────────────────────────────────────────────────────
r = httpx.post(f"{BASE}/chatbot/consultations/start", json={
    "customer_no": "CUST001",
    "entry_screen": "HOME",
    "app_version": "1.0.0"
})
d = check("챗봇 시작", r)
chatbot_id = d["chatbot_consultation_id"]
print(f"[1] 챗봇 시작: chatbot_consultation_id={chatbot_id}, node_id={d['node_id']}")
print(f"    버튼: {[b['value'] for b in d['buttons']]}")

# ── 2. PRODUCT_ADVICE 버튼 ────────────────────────────────────────────────────
r = httpx.post(f"{BASE}/chatbot/consultations/{chatbot_id}/messages", json={
    "message": "금융상품 상담",
    "button_value": "PRODUCT_ADVICE"
})
d = check("PRODUCT_ADVICE 버튼", r)
print(f"[2] PRODUCT_ADVICE: node_id={d['node_id']}, method={d['process_method']}")

# ── 3. AGENT 버튼 (루트 노드로부터) ─────────────────────────────────────────────
# 새 상담 시작
r_start2 = httpx.post(f"{BASE}/chatbot/consultations/start", json={
    "customer_no": "CUST001", "entry_screen": "HOME", "app_version": "1.0.0"
})
d_start2 = check("챗봇 시작2", r_start2)
chatbot_id2 = d_start2["chatbot_consultation_id"]

r = httpx.post(f"{BASE}/chatbot/consultations/{chatbot_id2}/messages", json={
    "message": "agent connect",
    "button_value": "AGENT"
})
d = check("AGENT 버튼", r)
print(f"[3] AGENT 이관: node_id={d['node_id']}, transfer={d['agent_transfer_required']}, method={d['process_method']}")

# ── 4. 대기열 조회 ────────────────────────────────────────────────────────────
r = httpx.get(f"{BASE}/chat/queue")
queue = check("대기열 조회", r)
print(f"[4] 대기열: {len(queue)}건")
# chatbot_id2 로 생성된 chat_consultation 찾기
chat_id = None
for item in queue:
    if item.get("chatbot_consultation_id") == chatbot_id2:
        chat_id = item["chat_consultation_id"]
        print(f"    chat_consultation_id={chat_id}, customer={item['customer_no']}")
        break

if not chat_id:
    print("  ❌ 대기열에서 해당 상담 찾지 못함")
    print(f"     queue={queue}")
    exit(1)

# ── 5. 상담사 연결 ────────────────────────────────────────────────────────────
r = httpx.post(f"{BASE}/chat/consultations/{chat_id}/connect", json={"employee_id": 777})
d = check("상담사 연결", r)
print(f"[5] 상담사 연결: employee_id={d.get('employee_id')}, active_yn={d.get('active_yn')}")

# ── 6. 메시지 교환 ────────────────────────────────────────────────────────────
msgs = [
    ("AGENT", "Hello! How can I help you?"),
    ("USER",  "I need account info"),
    ("AGENT", "Your account CTR-001 matures on 2027-01-01"),
    ("USER",  "Thank you!"),
]
for sender_type, content in msgs:
    r = httpx.post(f"{BASE}/chat/consultations/{chat_id}/messages", json={
        "message": content,
        "sender_type": sender_type
    })
    d = check(f"메시지 전송", r)
    print(f"[6] [{sender_type}] seq={d.get('sequence_no')}: {content[:40]}")

# ── 7. 메시지 이력 조회 ───────────────────────────────────────────────────────
r = httpx.get(f"{BASE}/chat/consultations/{chat_id}/messages")
msgs_list = check("메시지 이력 조회", r)
print(f"[7] 메시지 이력: {len(msgs_list)}건")

# ── 8. 상담 종료 ──────────────────────────────────────────────────────────────
r = httpx.post(f"{BASE}/chat/consultations/{chat_id}/end", json={"satisfaction_score": 5})
d = check("상담 종료", r)
print(f"[8] 상담 종료: active_yn={d.get('active_yn')}, satisfaction_score={d.get('satisfaction_score')}")

# ── 9. 기능별 execute ─────────────────────────────────────────────────────────
print("\n[9] 기능별 execute 테스트")
feature_cases = [
    ("PRODUCT_GUIDE",    {}),
    ("RATE_GUIDE",       {}),
    ("JOIN_CONDITION",   {}),
    ("PRODUCT_COMPARE",  {}),
    ("TERMS_RAG",        {"query": ""}),
    ("FAQ",              {}),
    ("MY_ACCOUNTS",      {"customer_no": "CUST001"}),
    ("MY_PRODUCTS",      {"customer_no": "CUST001"}),
    ("CONTRACT_STATUS",  {"customer_no": "CUST001"}),
    ("MATURITY_SCHEDULE",{"customer_no": "CUST001"}),
    ("INTEREST_HISTORY", {"customer_no": "CUST001"}),
    ("STAFF_CUSTOMER",   {"customer_no": "CUST001", "staff_id": "EMP001"}),
    ("STAFF_CONTRACT",   {"customer_no": "CUST001", "staff_id": "EMP001"}),
    ("STAFF_ACCOUNT",    {"customer_no": "CUST001", "staff_id": "EMP001"}),
    ("STAFF_TRANSFER_FLOW",{"customer_no": "CUST001", "staff_id": "EMP001"}),
    ("STAFF_CONSULTATION_HISTORY",{"customer_no": "CUST001", "staff_id": "EMP001"}),
]

ok_count = 0
for code, payload in feature_cases:
    r = httpx.post(f"{BASE}/chatbot/features/{code}/execute", json=payload)
    if r.status_code == 200:
        d = r.json()
        count = len(d.get("data") or [])
        status = d.get("status", "?")
        icon = "✅" if status in ("OK", "EMPTY", "AUTH_REQUIRED", "STAFF_AUTH_REQUIRED") else "❓"
        icon = "OK" if status in ("OK", "EMPTY", "AUTH_REQUIRED", "STAFF_AUTH_REQUIRED") else "??"
        print(f"    [{icon}] {code:<35} {status:<20} ({count}건)")
        ok_count += 1
    else:
        print(f"    [FAIL] {code:<35} HTTP {r.status_code}")

print(f"\n{'='*60}")
print(f"  기능 {ok_count}/{len(feature_cases)}개 응답 ✅")
print(f"  챗봇→AGENT 이관→상담사 연결→메시지 교환→종료 ✅")
print(f"{'='*60}\n")
