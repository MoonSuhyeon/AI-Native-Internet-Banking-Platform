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

After the team phase I continued on the fork: fixing the double-entry check to
verify persisted rows, adding a pre-check that blocks transfers before money
moves, and extracting a shared agent harness.

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

---

## Trade-offs

### Java for the core, Python for the agents

**Buys** — money movement stays in a stack with mature transaction and migration
tooling, while agents are written where the AI ecosystem actually lives and can
be iterated on without redeploying core banking.
**Costs** — two toolchains, two deployment paths, and a contract boundary between
them that can drift. Frontend response types are generated from the backend
OpenAPI specification rather than written by hand, which closes one side of that
gap but not all of it.

### A database per service

**Buys** — services evolve without coordinating schema changes, and a failure is
contained to the service that caused it.
**Costs** — no cross-service joins. Anything that spans services has to be
carried by events, and consistency becomes something to design rather than
something the database provides.

### Fraud checked before the money moves

Post-hoc detection tells you a transfer was fraudulent after it settled.

**Buys** — a pre-authorization check runs on the transfer path, and the response
is tiered by suspicion level rather than binary: high-risk transfers are delayed
and the customer is notified instead of being silently blocked or silently
allowed. Detected cases are handed to the investigation agent as real
transactions, and its findings feed the next transfer's pre-check — a loop no
single service can hold.
**Costs** — latency on the transfer path, and false positives delay legitimate
transfers. The tiered response exists because a single threshold would have to
choose between missing fraud and blocking customers.

### Outbox and compensation for external clearing

Interbank transfers leave the system. KFTC and BOK can fail after we have already
committed.

**Buys** — messages are not lost when the external leg fails, and a failed
settlement compensates rather than leaving a booking half-done.
**Costs** — eventual consistency, plus an outbox to operate and monitor. There is
a window where the ledger and the external system disagree.

### Agents recommend, people approve — and adoption is the metric

**Buys** — autonomy has a boundary. Loan review escalates to a four-eye approval
when bias thresholds are exceeded; the fraud investigation agent produces a
recommendation and only acts after an analyst approves under RBAC. Because the
human decision is recorded, the agent can be measured by **how often its
recommendation is actually adopted** rather than by offline accuracy, and the
document agent by **how often its automatic verdict is overturned**. Those metrics
run as scheduled evaluation workflows in CI, alongside bias and drift monitoring
over `agent_audit_log`.
**Costs** — throughput is capped by human review, and every agent action needs a
logged decision to be measurable at all. An agent that is never wrong but never
adopted still counts as a failure under this metric, which is the intended
behaviour.

### Normalising the data dictionary across live schemas

**Buys** — 52 boolean-carrying `CHAR(1)` columns across four schemas became real
booleans, and amounts were unified on a single integer type. Ambiguity at service
boundaries disappears rather than being handled case by case.
**Costs** — a migration against tables already in use, coordinated across
services that were being developed in parallel. The alternative — leaving the
inconsistency and documenting it — would have been cheaper on the day and more
expensive afterwards.

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
