# AI Agents Under Banking Internal Controls

*Team project · 5 people*

A bank is a two-sided market: **depositors** on one side, **borrowers** on the
other. Between them sits **risk** — will this borrower repay, is this transfer
fraud, did the money actually settle.

AI agents can do that risk work. But **an agent making risk decisions is itself
a risk** — wrong in ways nobody notices, acting on an identity nobody verified,
leaving no evidence of why.

So each agent runs under the same internal controls a bank applies to a person:
verified identity, segregation of duties, four-eyes approval, an audit trail
that cannot be rewritten, decisions that reproduce.

**That constraint is the project.**

---

## The controls

Every agent has a gateway-verified identity, stops at a recommendation a human
approves, and never decides alone — an LLM gives signals, a rule engine decides.
Loops end on budget. The ledger proves itself by re-reading persisted rows and
reconciling against KFTC/BOK clearing. Fraud is caught inline, post-hoc and in
windows.

Each control was verified by breaking it: reverting the ledger check fails 6
tests, removing the identity guard fails 5, deleting a gateway route fails 3.
Six of those tests read deployment config rather than `.java` — because the
recurring failure was **silence**: code compiled, screens worked, tests passed,
and the thing did nothing.

## My role

Frontend, customer domain, authentication and security, and the fraud
investigation agent.

This repo is my fork, and after the team phase I also refactored other people's
domains — the double-entry check, the inline fraud gate, the shared agent
harness, and moving identity out of request bodies across 24 endpoints.

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

## Stack

| | |
|---|---|
| Backend | Java 17 · Spring Boot 3.3.5 · Gradle multi-module |
| Agents | Python 3.11 · FastAPI · LangGraph |
| Frontend | Next.js · TypeScript |
| Data | PostgreSQL 16 (pgvector) · Redis 7 · Kafka 3.8 (KRaft) |
| Runtime | Docker Compose |
| Observability | Prometheus · Grafana · Loki · Langfuse · Phoenix |

## Run locally

```bash
./gradlew build
docker compose up -d

cd web && npm run dev
```

```bash
docker compose --profile doc up -d    # document agent (MinIO, Vault)
docker compose --profile rag up -d    # Elasticsearch, Kibana
```

## Docs

| | |
|---|---|
| [docs/README.md](docs/README.md) | Documentation index |
| [docs/decisions/](docs/decisions/) | Why things are built this way |
| [docs/OPEN_ITEMS.md](docs/OPEN_ITEMS.md) | What is not done yet |
