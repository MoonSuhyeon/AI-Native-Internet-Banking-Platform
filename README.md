# AI Agents Under Banking Internal Controls

*Team project · 5 people*

Letting an AI agent move money is easy. Letting it move money in a way a bank
could actually sign off on is the hard part.

This platform runs AI agents across real banking work — transfers, loan review,
fraud investigation, document verification — and puts each of them under the
same internal controls a bank applies to a human doing that job: verified
identity, segregation of duties, four-eyes approval, an audit trail that cannot
be rewritten, and decisions that reproduce.

---

## What we built

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

## My role

**My assigned scope** was the frontend, the customer domain, authentication
and security, and the fraud investigation agent.

| | |
|---|---|
| Customer / auth | Customer, party and contract model · login and JWT issuance · certificate auth · step-up approval tokens for fund movement · `BankRole` · access audit logging · per-customer transfer limits |
| Fraud investigation agent | Bounded investigation loop · HITL approval gating · gateway-verified identity for approvals |
| Frontend | Customer and admin screens |

**Beyond that scope**, this repository is my fork, and I have kept working on
it after the team phase. Those changes touch other people's domains and are
refactors of their work rather than original ownership:

| | |
|---|---|
| Payment ledger | Made the double-entry check actually verify — it re-reads persisted rows instead of comparing a variable with itself. Built reconciliation against KFTC/BOK clearing records |
| Fraud detection | Added the inline pre-check so a transfer can be blocked *before* the money moves; detection had been after the fact only |
| Agent platform | Split `harness-core` out so agents share one contract for tracing, audit and retry |
| Service boundaries | deposit + payment → `core-banking` (one transaction instead of a two-database saga) · advisory → `loan-service` (it had never been in the build) |
| Security wiring | All traffic through the gateway · sidecar host ports closed · identity moved out of request bodies across 24 endpoints |
| Conventions | Amounts to `BIGINT`, timestamps to `TIMESTAMPTZ`, `*_yn CHAR(1)` to `BOOLEAN` — 52 columns across 4 schemas |

The sections below describe the system as it stands, not only my part.
Decisions are in [docs/decisions/](docs/decisions/); what is still missing is in
[docs/OPEN_ITEMS.md](docs/OPEN_ITEMS.md).

---

## How

### Identity never comes from the request

The gateway strips client-supplied identity headers and replaces them with
values taken from a verified JWT. Services and agents read only those headers.

This sounds obvious and was not the case. Endpoints took `customer_no` and
`staff_id` from the request body — including the one that moves money. The
ownership check existed but compared against the value the caller had sent, so
it could not fail.

Sidecar agents publish no host ports, and each one refuses identity headers
unless the request carries proof it came through the gateway.

### Agents propose, humans dispose

The investigation agent runs a bounded loop — hypothesize → plan → act →
observe → gate — and stops at a recommendation. Payment freeze and STR filing
are gated on human approval plus RBAC. Contract signing is blocked when a
CRITICAL advisory report is unacknowledged (four-eyes).

The loop terminates on a decisive fact, on confidence, or on budget exhaustion.
It cannot spin.

### The LLM does not decide

For loan review, the LLM produces discriminative signals; the decision comes
from a rule engine and a policy matrix. Track 1 (auto-approve), 2 (auto-reject)
and 3 (human review) are deterministic given the same input.

In the investigation agent, a decisive fact found mid-loop (death, guardianship)
terminates immediately regardless of what the model thinks — fail-closed in
code, not in a prompt.

### The ledger proves itself

Every transfer writes balanced journal entries, and the check re-reads the
persisted rows and sums debits against credits per journal group. Daily
reconciliation matches the internal ledger against KFTC/BOK clearing records
and classifies breaks by severity.

### Fraud detection sits in three time bands

Inline (milliseconds, before the money moves — pass, step-up, delay, block),
post-hoc (after settlement, via Kafka), and windowed aggregation (Spark).
Each has its own budget so the slow layers cannot stall a transfer.

---

## How we verified it

A control that cannot fail is worse than no control: it reads as protection.
So every guard here was checked by breaking it and confirming the failure.

**Mutation over assertion.** Reverting the ledger check to compare a variable
with itself fails 6 tests. Removing the FDS cumulative sum fails 6. Dropping
the identity guard from the consultation service fails 5. Removing a gateway
route fails 3. Each number was observed, not estimated.

**Guarding configuration, not just code.** Most of what broke was never in a
`.java` file. These tests read deployment config and fail the build:

| | |
|---|---|
| `SidecarExposureTest` | agent ports are not published to the host |
| `ComposeNetworkReachabilityTest` | network splits do not silently sever a caller |
| `FrontendGatewayOnlyTest` | the frontend never calls a service directly |
| `ScrapeTargetConsistencyTest` | no dead metrics targets |
| `DockerBuildContextTest` | module dependencies reach the image |
| `InternalRouteConfigTest` | gateway routes still exist |

**Real stack, not mocks.** Ledger and reconciliation tests run against
PostgreSQL in Testcontainers. Network isolation was confirmed by booting the
stack and checking that a peer service cannot resolve a sidecar by name while
the gateway can.

The recurring failure mode was silence — code compiled, screens worked, tests
passed, and the thing did nothing. A `fail-open` fallback that never fired. A
Docker image missing a module while Gradle built fine. Metrics scraping a port
that had been closed. All of these now fail loudly.

---

## Why it is built this way

Written up in [docs/decisions/](docs/decisions/). The ones that shaped the most:

**[Merging deposit and payment](docs/decisions/core-banking-merge.md)** — an
intra-bank transfer is one fact. Split across two services it needed a saga,
and the compensation could itself fail, leaving money withdrawn and not
deposited. Service purity lost to atomicity.

**[Security zones](docs/decisions/security-zone-topology.md)** — gateway
unification applies to north-south traffic only. East-west needs different
controls. Getting this distinction wrong turns half the system into exceptions.

**[Agent harness consolidation](docs/decisions/agent-harness-consolidation.md)** —
agents share one contract for audit, tracing and retry, so an agent's evidence
does not depend on who wrote it.

---

## Reusing the pieces

**`harness-core`** — audit, tracing and retry as one contract, implemented for
both Java and Python. Any agent that plugs in produces the same evidence
shape.

**The identity pattern** — gateway strips and re-injects identity, sidecars
trust the headers only with proof of passage, and no service accepts identity
from a request body. Portable to any system where a language boundary tempts
you to pass identity by hand.

**Structural tests** — the six above are not domain-specific. They encode
"deployment must match intent" and transfer to any Docker Compose project.

---

## Operating it

Metrics, logs and traces go to Prometheus / Loki / Grafana; LLM and agent
traces to Langfuse and Phoenix.

The metric that matters most is **adoption rate** — how often a human accepts
what the agent recommended, split from disagreement caused by shadow
divergence. An agent that is always overridden is not working, and without this
you cannot tell.

Reconciliation breaks persist with a first-seen timestamp, so "how many days
has this been open" is answerable.

What is *not* done is tracked in [docs/OPEN_ITEMS.md](docs/OPEN_ITEMS.md) —
including gaps against real banking infrastructure (batch isolation, read
replicas, ISO 20022, HSM, DR) and why each was out of scope.

---

## Stack

| | |
|---|---|
| Backend | Java 17 · Spring Boot 3.3.5 · Gradle multi-module |
| Agents | Python 3.11 · FastAPI · LangGraph |
| Frontend | Next.js · TypeScript |
| Data | PostgreSQL 16 (pgvector) · Redis 7 · Kafka 3.8 (KRaft) |
| Runtime | Docker Compose |

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
| [docs/OPEN_ITEMS.md](docs/OPEN_ITEMS.md) | What is not done yet |
| [docs/decisions/](docs/decisions/) | Why things are built this way |
