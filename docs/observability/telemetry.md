# Telemetry architecture

## Traces

Applications export OpenTelemetry spans to Grafana Alloy on ports `4317` (gRPC) and `4318` (HTTP). Alloy sends trace batches to Tempo and a configured Langfuse Cloud OTLP endpoint. Tempo serves its HTTP API on port `3200`; the Temporal workflow UI is on port `8233`.

The Alloy configuration uses `LANGFUSE_PUBLIC_KEY` and `LANGFUSE_SECRET_KEY` for Langfuse Cloud authentication. There is no local Langfuse container in the infrastructure Compose.

## Logs

Alloy tails the agent log file mounted at `/var/log/services/agentic-service.log`, attempts to extract trace/span identifiers and forwards records to Loki on port `3100`. Extend the config to include other application logs if cross-service log searches are required.

## Metrics

Prometheus scrapes every 10 seconds. The bundled configuration targets `document-service` at host port `8081`, a gateway target at host port `8080` and Prometheus itself.

To monitor all three core services, add Agentic Service (`8086`) and Internal Service (`8084`) scrape jobs. The gateway target should be disabled or corrected if cAdvisor occupies host port `8080`.

Grafana listens on port `3000` and reads dashboard provisioning from mounted infrastructure directories. Availability of a particular dashboard depends on provisioning files.

## Retention and environment

The sample Loki and Tempo configurations use local filesystem storage without durable named mounts; Tempo's configured trace retention is 1 hour. Treat the configuration as a development topology. Persist storage, pin image versions, use TLS and set retention/backups for shared environments.

The Agentic Service disables message-content capture in its Embabel observability settings. Ensure downstream exporters and log formatting follow the same data minimization rules.
