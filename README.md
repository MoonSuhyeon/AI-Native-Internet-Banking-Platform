# AI Agents Under Banking Internal Controls

*Team project · 5 people*

![AI + Distributed / Financial System](https://img.shields.io/badge/AI%20%2B%20Distributed%20%2F%20Financial%20System-0B1220?style=for-the-badge)

![Agent](https://img.shields.io/badge/Agent-7C3AED?style=for-the-badge) ![FDS](https://img.shields.io/badge/FDS-BE123C?style=for-the-badge) ![RBAC](https://img.shields.io/badge/RBAC-BE123C?style=for-the-badge) ![audit](https://img.shields.io/badge/audit-BE123C?style=for-the-badge) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-475569?style=for-the-badge) ![Kafka](https://img.shields.io/badge/Kafka-475569?style=for-the-badge) ![Redis](https://img.shields.io/badge/Redis-475569?style=for-the-badge) ![PostgreSQL](https://img.shields.io/badge/PostgreSQL-475569?style=for-the-badge)

In a financial marketplace, **serving demand creates risks the financial
institution has to manage** — credit risk when someone borrows, and fraud and
settlement risk when money moves.

AI agents can help investigate these risks by connecting data, documents, and
policies across systems. But **how much autonomy should a financial institution
safely give an agent?**

We built a banking system where **agents investigate and recommend, while
identity, permissions, service boundaries, policy, human approval, and
auditability constrain what they can see and do.**

**My part**

- **Customer & auth** — customer / party model · JWT and certificate login ·
  step-up approval for fund movement · role-based access (`BankRole`) · access
  audit logging · per-customer transfer limits
- **Fraud investigation agent** — bounded investigation loop · evidence
  gathering · HITL approval gating
- **Frontend** — internet banking and admin console
- **System integrity** *(after the team phase, on this fork)* — persisted-row
  double-entry check · pre-transfer FDS gate · shared agent harness

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
