<p align="center">
  <img src="resources/sparrow-logo.svg" width="270" alt="SparrowX" />
</p>

<h1 align="center">Sparrow-X</h1>
<p align="center"><strong>Agent-Powered Company Intelligence</strong></p>

**SparrowX** is an internal knowledge and agentic investigation platform for engineering organizations. It combines structured company context, document intelligence, bounded semantic decisions, goal-directed execution and verifiable evidence in a single assistant.

![SparrowX runtime traffic flow](resources/sp-architecture-flow.gif)

## Capabilities

- **Internal knowledge discovery** — resolve service owners, engineers, teams, repositories, dependencies, architecture documents and runbooks.
- **Evidence-backed investigations** — connect incident reports, operating procedures and company relationships; identify supported findings and unresolved questions.
- **Operational analysis** — compare deployment practices, documented standards and incident history; produce grounded assessments and recommendations.
- **Observable agent execution** — inspect mission status, action selection, goal reviews, evidence acquisition, model usage, traces and streamed results.
- **Durable missions** — persist mission identity and execution inputs, coordinate retries and approvals, and support cancellation through Temporal.

SparrowX retrieves and reasons over authorized internal sources. A security-oriented question is not itself proof of a detected intrusion: incident detection and telemetry correlation require the corresponding data sources and integrations.

## Architecture

| Component | Responsibility | Runtime |
|---|---|---|
| **Agentic Service** (`agenticsvc`) | Mission submission, intent classification, bounded decisions, Embabel planning, governance, execution and synthesis | Spring Boot; HTTP + gRPC |
| **Internal Service** (`intsvc`) | Engineers, teams, modules, repositories, ownership, runbooks and organizational graph context | Spring Boot; PostgreSQL + Neo4j |
| **Document Service** (`docsvc`) | Ingestion, extraction, chunking, semantic/keyword retrieval, citations, evidence graphs and DICE proposition workflows | Spring Boot; PostgreSQL + MinIO + Qdrant + Elasticsearch + Neo4j |
| **Building Blocks** (`bb`) | Shared command/query handling, validation, resilience, context propagation and observability primitives | Maven library, not a running service |

```mermaid
flowchart LR
    UI[Assistant UI] <-->|HTTP / SSE| AG[Agentic Service]
    AG <-->|gRPC| IN[Internal Service]
    AG <-->|gRPC| DOC[Document Service]
    AG <-->|Workflow activities| TEMP[Temporal]
    IN --> PG[(PostgreSQL)]
    IN --> NEO[(Neo4j)]
    DOC --> PG
    DOC --> OBJ[(MinIO)]
    DOC --> VEC[(Qdrant)]
    DOC --> ES[(Elasticsearch)]
    DOC --> NEO
    AG --> PG
```

Infrastructure runs under Docker Compose; the three application services run locally with Maven. See [Architecture](docs/architecture/agent-decisions.md) and [Mission runtime](docs/architecture/mission-runtime.md).

## How the agent makes decisions

SparrowX separates **bounded judgment** from **goal-directed execution**.

### System 1 — bounded semantic decisions

System 1 evaluates known, typed questions against the current mission state. It does not invent tools, create an execution plan or execute actions.

- **Provider:** Glide or Jev.
- **Intent:** determine required evidence sources, FAST versus RESEARCH execution path and GOAP versus HYBRID planner mode.
- **Utility:** assess the marginal value of known capabilities using available evidence and mission constraints.
- **Uncertainty:** intent classification applies explicit conservative policies when bounded answers are unavailable; eligible utility assessments may escalate unresolved fields to a structured LLM.

Intent decisions and generative LLM operations have different responsibilities. An LLM does not override source-authorization rules or select the intent's planner policy.

### System 2 — goal-directed planning and review

Embabel executes registered actions using **GOAP** or **HYBRID** planning, with dynamic action eligibility and values derived from mission state. The agent maintains one terminal goal—produce a grounded result or a truthful unresolved outcome—and repeatedly evaluates whether additional evidence is warranted.

```mermaid
flowchart TD
    A[Submit mission] --> B[Validate and classify intent]
    B --> C[Persist mission and checkpoints]
    C --> D[Temporal workflow]
    D --> E[Embabel: register goal]
    E --> F[Review evidence and goal]
    F --> G{Goal review}
    G -->|Need evidence| H[Select eligible capability]
    H --> I[Document / internal / graph action]
    I --> F
    G -->|Ready or satisfied| J[Synthesis and grounding]
    G -->|Blocked or ambiguous| K[Unresolved outcome]
    J --> L[Persist and stream result]
    K --> L
    D -.->|Approval required| M[Human review gate]
    M -.-> D
```

The execution path depends on the mission and evidence encountered; action count and ordering are not hard-coded. **FAST and RESEARCH both use Temporal** in this runtime. The labels *System 1* and *System 2* describe engineering roles, not access to an LLM's private reasoning.

Read [Agent decisions](docs/architecture/agent-decisions.md) for confidence, utility, GOAP/HYBRID and review semantics.

### Examples

### 1. Internal Security Investigation

**Example multi-hop query:**

> “An incident report describes suspected credential exposure involving the
> Agentic Orchestrator service. Identify the responsible team, find the applicable
> credential-handling policies and incident-response runbooks, and outline the
> investigation and remediation steps supported by those documents.”

**Expected SparrowX execution:**

* **`intsvc`** resolves the affected service, owning team, responsible engineers, and available service relationships.
* **`docsvc`** retrieves the incident report, credential-handling policies, relevant service documentation, and incident-response runbooks.
* **`agenticsvc`** correlates the reported incident with service context and security guidance, identifies responsible owners, and produces a cited investigation plan that distinguishes documented facts from questions requiring verification.

### 2. Internal Knowledge Discovery

**Example multi-hop query:**

> “For the Agentic Orchestrator service, identify the owning team and primary
> engineers, find its architecture documents and deployment procedures, and
> locate the runbooks relevant to increased latency during agent execution.”

**Expected SparrowX execution:**

* **`intsvc`** resolves the service, owning team, associated engineers, and service metadata.
* **`docsvc`** retrieves relevant architecture documents, deployment procedures, and operational runbooks.
* **`agenticsvc`** connects ownership and document evidence into a cited answer explaining who is responsible, how the service operates, and which guidance applies.


### 3. Operational Risk & Process Analysis

**Example multi-hop query:**

> “Assess the Agentic Orchestrator service’s readiness for production deployments.
> Compare its documented procedures with company deployment standards, examine
> incident reports for recurring model timeouts, and identify gaps in rollback
> guidance, operational ownership, and runbook coverage.”

**Expected SparrowX execution:**

* **`intsvc`** gathers service metadata, ownership, team assignments, and available dependency information.
* **`docsvc`** retrieves company deployment standards, service procedures, architecture documents, incident reports, and runbooks.
* **`agenticsvc`** compares documented practices against requirements, identifies recurring issues and evidence gaps, and produces a cited risk assessment with recommended follow-up actions and responsible owners.

---

## Quick start

### Prerequisites

- Docker Engine or Docker Desktop with Docker Compose.
- JDK **25** and Maven available on `PATH`. Document Service targets Java 25; the other Java modules target Java 24.
- Provider credentials for the configured models and permission to resolve the Maven dependencies, including Embabel and DICE artifacts.
- The repository's infrastructure configuration and PostgreSQL initialization scripts in the [documented layout](docs/setup/local-development.md).
- An ignored repository-root `.env` for the Compose file; copy `.env.example` if no `.env` exists and set the required values before starting optional Alloy/Langfuse exports.

### 1. Start infrastructure

From the directory containing `docker-compose.infrastructure.yaml`:

```bash
docker compose -f docker-compose.infrastructure.yaml config
docker compose -f docker-compose.infrastructure.yaml up -d \
  postgres temporal temporal-ui kafka neo4j minio qdrant elasticsearch keycloak
```

The Compose file also defines optional observability and supporting services, including Grafana, Prometheus, Alloy, Loki, Tempo, Redis and Cassandra. Start only the services required for the workflow being exercised. The full stack is memory-intensive.

On first initialization of an **empty PostgreSQL data volume**, the mounted `postgres-init-scripts/` directory creates the SparrowX schema and Keycloak database. Temporal manages its own database initialization. **Existing PostgreSQL volumes are not reinitialized automatically.** See [Database setup](docs/setup/postgresql.md).

### 2. Build the Java modules

Run each command in the corresponding module directory:

```bash
# buildingblocks/
mvn clean install

# internal-service/
mvn clean package

# document-service/
mvn clean package

# agentic-service/
mvn clean package
```

Building Blocks must be installed before building the dependent services. It is a library; do not run it with `spring-boot:run`.

### 3. Run the application services

Start each service in a separate terminal:

```bash
# internal-service/
mvn spring-boot:run

# document-service/ — bind its HTTP endpoint to 8081
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8081"

# agentic-service/
mvn spring-boot:run
```

Configure provider keys and endpoint variables in your shell or IDE run configuration before starting the services. With the default Jev provider, `TYPESAFE_API_KEY` is required at startup. Other configured model and embedding integrations require their own credentials. The document HTTP override avoids a collision with cAdvisor on port 8080.

| Service | HTTP | gRPC |
|---|---:|---:|
| Internal Service | 8084 | 50055 |
| Document Service | 8081 (startup override) | 50054 |
| Agentic Service | 8086 | 50056 |
| Temporal UI | 8233 | — |
| Grafana (optional) | 3000 | — |

Test service readiness:

```bash
curl -f http://localhost:8084/actuator/health
curl -f http://localhost:8081/actuator/health
curl -f http://localhost:8086/actuator/health
```

Actuator access depends on the active security configuration. Complete setup and environment-variable mapping are in [Local development](docs/setup/local-development.md) and [Configuration](docs/setup/configuration.md).

## Run a mission

The Assistant API submits a mission and streams progress using Server-Sent Events.

```bash
curl -N -X POST http://localhost:8086/api/assistant/missions/stream \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"tenant-test","userId":"demo-user","query":"Who owns the Agentic Orchestrator service?"}'
```

The stream can contain `mission`, `progress`, `answer_delta`, `usage`, `heartbeat`, `result`, `error` and `complete` events. Progress records include step/state data and, when available, a structured trace envelope. Results depend on the tenant's actual internal graph and document records; schema initialization alone does not seed those sources.

### Investigation example

> An incident report describes suspected credential exposure involving the Agentic Orchestrator service. Identify the owning team, find applicable credential-handling policies and response runbooks, and outline supported investigation steps.

The mission may resolve ownership in Internal Service, retrieve source passages from Document Service, review whether the evidence satisfies the goal and return cited findings. Missing sources remain explicit evidence gaps rather than inferred facts.

See [Investigation walkthrough](docs/missions/credential-exposure.md).

## Observability

SparrowX exposes operational events at two levels:

- **Mission telemetry:** persisted mission progress, goal evaluations, action events and decision traces, projected to the assistant through SSE.
- **Infrastructure telemetry:** application traces to Alloy → Tempo and configured Langfuse Cloud; Agentic Service file logs to Loki; configured Prometheus scrapes and Grafana visualization.

The provided Prometheus configuration covers Document Service and a gateway target; add Agentic and Internal Service scrape targets before relying on platform-wide metrics. Langfuse is configured as a **cloud trace destination**, not a locally hosted service.

See [Execution traces](docs/observability/execution-traces.md) and [Telemetry](docs/observability/telemetry.md).

## Security and data handling

Mission execution applies tool and source authorization, grounding policies, execution budgets and human-approval controls where required. Document contents and model prompts should not be exposed through telemetry by default; the agent configuration disables message-content capture.

The bundled infrastructure values are intended for isolated **local development**. Replace default passwords, restrict network exposure, configure authentication and TLS, and establish data retention and backup policies before deploying into a shared environment.

## Technology

Java 24/25 · Spring Boot 4.1.1 · Maven · Embabel · Temporal · gRPC · PostgreSQL · Neo4j · MinIO · Qdrant · Elasticsearch · Kafka · DICE · OpenTelemetry · Alloy · Tempo · Loki · Prometheus · Grafana · Langfuse Cloud

## Documentation

| Guide | Coverage |
|---|---|
| [Agent decisions](docs/architecture/agent-decisions.md) | System 1, System 2, GOAP/HYBRID and goal review |
| [Mission runtime](docs/architecture/mission-runtime.md) | Persistence, Temporal, checkpoints, approvals and cancellation |
| [Local development](docs/setup/local-development.md) | Docker infrastructure, Maven builds and service startup |
| [Configuration](docs/setup/configuration.md) | Model providers, ports and environment settings |
| [PostgreSQL](docs/setup/postgresql.md) | Schema initialization, validation and existing-volume handling |
| [Investigation walkthrough](docs/missions/credential-exposure.md) | End-to-end example and evidence expectations |
| [Execution traces](docs/observability/execution-traces.md) | Events, SSE contract and UI graph interpretation |
| [Telemetry](docs/observability/telemetry.md) | OTLP, Tempo, Langfuse, Loki, Prometheus and Grafana |
