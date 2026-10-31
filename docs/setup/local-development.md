# Local development

The application services run with Maven on the host; Docker Compose supplies infrastructure. No Java service Dockerfiles are required.

## Requirements

- Docker Engine/Desktop and Compose plugin.
- JDK 25 and Maven, with access to required Maven/Embabel/DICE repositories.
- Credentials for configured LLM, System 1 and embedding providers.
- Sufficient memory for the selected infrastructure services.

## Directory layout

The infrastructure Compose uses relative mounts. Keep the following directories together:

```text
Sparrow-x/
├── infrastructure/
│   ├── docker/
│   │   └── docker-compose.infrastructure.yaml
│   ├── configs/
│   │   ├── alloy.hcl
│   │   ├── prometheus.yaml
│   │   ├── loki-config.yaml
│   │   ├── tempo.yaml
│   │   └── grafana/
│   └── postgres-init-scripts/
│       ├── 00-create-keycloak.sql
│       ├── 10-sparrowx-schema.sql
│       └── 90-verify-schema.sql
├── buildingblocks/
├── internal-service/
├── document-service/
└── agentic-service/
```

The Compose file also expects a repository-root `.env` via the Alloy `env_file` setting. If that file is absent, copy the provided `.env.example` to `.env` and populate the values required by your selected services. Place only non-secret example values in source control; provide real keys through an ignored `.env` or a managed secret mechanism. Ensure existing relative mounts (including agentic log files) point to the active checkout.

## Infrastructure startup

Run from `infrastructure/docker`:

```bash
docker compose -f docker-compose.infrastructure.yaml config
docker compose -f docker-compose.infrastructure.yaml up -d \
  postgres temporal temporal-ui kafka neo4j minio qdrant elasticsearch keycloak
```

Kafka pulls in its Zookeeper dependency. To run the configured observability path, ensure Langfuse keys are set and start:

```bash
docker compose -f docker-compose.infrastructure.yaml up -d \
  alloy tempo loki prometheus grafana
```

Optional services such as Redis and Cassandra can be started separately. The entire stack may exceed typical laptop memory budgets.

## Module builds

Use JDK 25 for Document Service; the other modules target Java 24. Install the non-executable shared dependency first:

```bash
# In buildingblocks
mvn clean install

# In internal-service
mvn clean package

# In document-service
mvn clean package

# In agentic-service
mvn clean package
```

`document-service` declares Embabel 1.5.0 and DICE snapshot dependencies; `agentic-service` declares Embabel 1.5.3. Their versions are service-local and should not be assumed identical.

## Application startup

Run each application in its own terminal (or IDE process), with the relevant environment variables configured:

```bash
# internal-service
mvn spring-boot:run

# document-service
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8081"

# agentic-service
mvn spring-boot:run
```

| Service | HTTP | gRPC |
|---|---:|---:|
| Internal | 8084 | 50055 |
| Document | 8081 when overridden | 50054 |
| Agentic | 8086 | 50056 |

Document Service does not explicitly set its HTTP port in its application YAML. Use 8081 to avoid the cAdvisor mapping on host port 8080. The application configurations use `localhost` for downstream connections, matching host Maven processes.

## Verification

```bash
curl -f http://localhost:8084/actuator/health
curl -f http://localhost:8081/actuator/health
curl -f http://localhost:8086/actuator/health
```

Actuator access can depend on security rules. You can inspect Temporal at `http://localhost:8233` and Grafana at `http://localhost:3000` when enabled.

Do not expose the demo Compose ports, default passwords or disabled Elasticsearch security to untrusted networks. For a shared deployment, use proper secrets, private networks, TLS, backups and dedicated production configuration.
