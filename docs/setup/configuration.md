# Runtime configuration

All examples below assume applications execute on the host, not as Docker Compose services.

## Agentic Service

| Variable | Purpose / default |
|---|---|
| `SERVER_PORT` | HTTP port; default `8086` |
| `GRPC_SERVER_PORT` | gRPC port; default `50056` |
| `AGENTIC_LLM_PROVIDER` | Main model provider; default `Deepseek` |
| `AGENTIC_LLM_MODEL` | Main model; default `deepseek-v4-flash` |
| `AGENTIC_LLM_API_KEY` | Main model credentials when required by the configured provider |
| `DEEPSEEK_API_KEY` | DeepSeek integration credentials |
| `SYSTEM_ONE_PROVIDER` | `jev` (default) or `laya` |
| `TYPESAFE_API_KEY` | Required when Jev is selected |
| `JEV_BASE_URL`, `JEV_MODEL` | Jev endpoint and model |
| `LAYA_BASE_URL`, `LAYA_MODEL` | Laya endpoint and model |
| `TEMPORAL_ENDPOINT` | Temporal; default `localhost:7233` |
| `TEMPORAL_NAMESPACE` | Default `default` |
| `TEMPORAL_TASK_QUEUE` | Default `agentic-mission-task-queue` |
| `INTERNAL_GRPC_HOST`, `INTERNAL_GRPC_PORT` | Internal gRPC; default `localhost:50055` |
| `DOCUMENT_GRPC_HOST`, `DOCUMENT_GRPC_PORT` | Document gRPC; default `localhost:50054` |

Intent policy thresholds are configured under `sparrowx.intent.*`. The default Jev client is instantiated with a required API key; selecting an alternate provider must also point to an available endpoint.

## Document Service

| Variable | Purpose / default |
|---|---|
| `DOCUMENT_GRPC_PORT` | Default `50054` |
| `MINIO_ENDPOINT`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY` | Object storage; development defaults connect to localhost:9000 |
| `ELASTICSEARCH_URL` | Development default `http://localhost:9200` |
| `QDRANT_URL` | Base default `http://localhost:6333` |
| `DOCUMENT_QDRANT_COLLECTION` | Vector collection name; development config uses a Qwen-specific collection |
| `DOCUMENT_EMBEDDING_ADAPTER` | Base `deterministic`; development profile `embabel` |
| `DOCUMENT_EMBEDDING_DIMENSION` | Base `768`; development profile `4096` |
| `DOCUMENT_EMBEDDING_MODEL` | Development default `Qwen/Qwen3-Embedding-8B` |
| `DEEPINFRA_API_KEY` | Hosted embedding API key |
| `DEEPSEEK_API_KEY` | Configured document LLM key |
| `NEO4J_URI`, `NEO4J_USERNAME`, `NEO4J_PASSWORD` | Graph data source |

Document Service's base configuration enables Flyway. Coordinate externally supplied schema initialization with migration ownership; do not apply overlapping DDL twice.

## Internal Service

- HTTP `8084`, gRPC `50055`.
- PostgreSQL: `localhost:5432/postgres` in the development profile.
- Neo4j: `bolt://localhost:7687` in the development profile.
- Kafka: `localhost:9092` in the development profile.

## Observability and secrets

- OTLP receiver: Alloy `localhost:4317` (gRPC) and `localhost:4318` (HTTP).
- Langfuse Cloud export: `LANGFUSE_PUBLIC_KEY` and `LANGFUSE_SECRET_KEY`, consumed by Alloy.
- `OTEL_TRACING_ENDPOINT` is used by Document Service's development profile.
- The Agentic/Document source YAML includes development-only plaintext defaults; never reuse these in a public environment.
- Treat loaded `.env` contents, model keys, log records and telemetry metadata as sensitive.
