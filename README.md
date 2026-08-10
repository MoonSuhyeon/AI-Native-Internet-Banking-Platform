# AI-Native Internet Banking

An internet banking platform where AI agents carry out banking work — transfers,
loan review, fraud investigation, document verification — and humans approve at
the points that need judgment.

---

## Architecture

```
                    ┌──────────────────────────┐
                    │        CHANNELS          │
                    │ Customer │ Employee │ AI │
                    │   Web    │  Admin   │Bot │
                    └────────────┬─────────────┘
                                 │
                                 ▼
                    ┌──────────────────────────┐
                    │       API GATEWAY        │
                    │  JWT · Identity · RBAC   │
                    │  Routing · CORS · Guard  │
                    └────────────┬─────────────┘
                                 │
              ┌──────────────────┴──────────────────┐
              ▼                                     ▼
    ┌──────────────────┐              ┌──────────────────────────┐
    │  CUSTOMER / AUTH │◀─── look up ─│      CORE BANKING        │
    │                  │   (limits,   │                          │
    │  CIF · Party     │    holder)   │ Deposit · Payment · Loan │
    │  Auth · Role     │──────────────▶│                          │
    │  Audit           │              │ ├ Double-entry Ledger    │
    └──────────────────┘              │ ├ Outbox / Saga          │
                                      │ └ Orchestration          │
                                      └────────────┬─────────────┘
                                                   │
                        ┌──────────────────────────┼───────────────────┐
                        ▼                          ▼                   ▼
              ┌──────────────────┐      ┌──────────────────┐  ┌───────────────┐
              │       FDS        │      │      KAFKA       │  │ AI / DECISION │
              │                  │      │                  │  │               │
              │ PreCheck Gate    │      │  Events / MQ     │  │ Loan Review   │
              │ Detector         │      └────────┬─────────┘  │ Review AI     │
              │ Fraud Agent      │               │            │ Document Agent│
              │ HITL             │               │            │ Consultation  │
              └──────────────────┘               │            └───────────────┘
                                                 │
                        ┌────────────────────────┼────────────────────────┐
                        ▼                        ▼                        ▼
              ┌──────────────────┐    ┌──────────────────┐    ┌──────────────────┐
              │ EXTERNAL SYSTEMS │    │  FDS Detector    │    │      SPARK       │
              │                  │    │                  │    │                  │
              │ KFTC · BOK       │    │ post-hoc         │    │ windowed         │
              │ Clearing/Settle  │    │ detection        │    │ aggregation      │
              │ Reconciliation   │    └──────────────────┘    └──────────────────┘
              └──────────────────┘

              ┌──────────────────────────────────────────────────┐
              │                    DATA                          │
              │  PostgreSQL 16 · pgvector · Redis 7              │
              └──────────────────────────────────────────────────┘

              ┌──────────────────────────────────────────────────┐
              │                OBSERVABILITY                     │
              │  Prometheus · Grafana · Loki · Alertmanager      │
              │  Langfuse · Phoenix (LLM / agent tracing)        │
              └──────────────────────────────────────────────────┘
```

---

## What makes it different

**Double-entry ledger, verified against what was stored.** Every transfer writes
balanced journal entries, and the check re-reads the persisted rows instead of
comparing in-memory objects.

**Reconciliation.** Internal ledger is matched against external clearing records
daily. Breaks are typed by severity and persisted, so trends are visible.

**Fraud detection in three layers.** Inline (before the money moves), post-hoc
(after settlement), and windowed aggregation — each with its own time budget.

**Agents propose, humans decide.** The investigation agent runs a bounded loop
and stops at a recommendation. Payment freeze and STR require human approval.

**One way in.** All external traffic passes the gateway, which strips
client-supplied identity headers and replaces them with verified JWT claims.
Agent sidecars publish no host ports.

---

## Stack

| | |
|---|---|
| Backend | Java 17 · Spring Boot 3.3.5 · Gradle multi-module |
| Agents | Python 3.11 · FastAPI · LangGraph |
| Frontend | Next.js · TypeScript |
| Data | PostgreSQL 16 (pgvector) · Redis 7 · Kafka 3.8 (KRaft) |
| Runtime | Docker Compose |

---

## Run locally

```bash
./gradlew build
docker compose up -d

cd web && npm run dev
```

Optional profiles:

```bash
docker compose --profile doc up -d    # document agent (MinIO, Vault)
docker compose --profile rag up -d    # Elasticsearch, Kibana
```

---

## Docs

| | |
|---|---|
| [docs/README.md](docs/README.md) | Documentation index |
| [docs/OPEN_ITEMS.md](docs/OPEN_ITEMS.md) | What is not done yet |
| [docs/decisions/](docs/decisions/) | Why things are built this way |
