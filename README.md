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

| | |
|---|---|
| **Verified identity** | The gateway strips client headers and injects claims from a verified JWT. No service reads identity from a request body |
| **Agents propose, humans dispose** | The investigation agent stops at a recommendation. Payment freeze and STR need human approval + RBAC. Contract signing blocks on an unacknowledged CRITICAL advisory |
| **The LLM does not decide** | ML gives probability of default, the LLM gives signals — a rule engine decides. Same input, same track. Policy is retrieved, not recalled |
| **Bounded autonomy** | The loop ends on a decisive fact, on confidence, or on budget. It cannot spin |
| **Self-proving ledger** | Double-entry checked by re-reading persisted rows. Daily reconciliation against KFTC/BOK clearing |
| **Fraud in three time bands** | Inline (before the money moves) · post-hoc (Kafka) · windowed (Spark) |

**A control that cannot fail is worse than no control** — it reads as
protection. So each was verified by breaking it: reverting the ledger check to
compare a variable with itself fails 6 tests, removing the identity guard fails
5, deleting a gateway route fails 3. Six of those tests read deployment config
rather than `.java`, because that is where most of it broke.

> The recurring failure mode was **silence** — code compiled, screens worked,
> tests passed, and the thing did nothing.

Verification proves the control works; **adoption rate** — how often a human
accepts what an agent recommended — proves the agent does.

## My role

**My scope** was the frontend, the customer domain, authentication and security,
and the fraud investigation agent: the customer and party model, JWT and
certificate auth, step-up approval tokens, `BankRole`, access audit logging,
per-customer transfer limits, and the agent's bounded loop and HITL gating.

**Beyond it** — this repo is my fork and I kept working after the team phase.
These are refactors of other people's domains, not original ownership: making
the double-entry check actually verify and building reconciliation; adding the
inline fraud pre-check so a transfer can be blocked *before* the money moves;
splitting out `harness-core` so agents share one contract for tracing and audit;
merging deposit + payment into `core-banking`; routing all traffic through the
gateway and moving identity out of request bodies across 24 endpoints.

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
