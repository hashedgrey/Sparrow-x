# SparrowX Document Service

## Overview

The Document Service provides document ingestion, semantic enrichment, retrieval, evidence construction, grounding verification, and citation verification for SparrowX.

It owns the unstructured knowledge pipeline used by Agentic Service.

Document ingestion uses Embabel Agent RAG for parsing and chunking and DICE for ingestion-time semantic extraction. Persisted propositions, entities, relationships, and source grounding are reused during retrieval.

Document Service owns grounded document semantics and provenance. Agentic Service owns mission-level planning, reasoning, support/contradiction judgments, and final synthesis.

## Implementation Progress

🟩 **Document upload and persistence** ██████████ **100%**

🟩 **Document ingestion and chunking** █████████░ **98%**

🟩 **Embedding and indexing pipeline** █████████░ **98%**

🟩 **Hybrid keyword/vector retrieval** █████████░ **98%**

🟩 **Access and permission filtering** █████████░ **98%**

🟩 **Evidence graph construction** █████████░ **98%**

🟩 **Citation and grounding verification** █████████░ **98%**

🟩 **Ingestion recovery and workers** █████████░ **98%**

🟩 **gRPC API and resilience policies** █████████░ **98%**

🟩 **Observability** █████████░ **98%**

```text
Overall                         🟩 █████████░ 98%
```

## Document Ingestion

Uploaded documents pass through:

```text
Object Storage
     ↓
Embabel RAG Parsing + Chunking
     ↓
DICE Semantic Enrichment
     ├─ Propositions
     ├─ Entities
     └─ Relationships
     ↓
Persist Chunks + Semantic Graph
     ↓
Elasticsearch + Qdrant
```

The ingestion layer provides:

* document validation and storage
* Apache Tika-backed extraction
* page-aware parsing and chunking
* DICE proposition extraction and entity resolution
* Neo4j graph projection
* embedding generation
* keyword/vector indexing
* lifecycle tracking and recovery

Current chunking configuration:

```text
Maximum chunk size    1500
Chunk overlap          200
Embedding batch size   100
```

A DICE failure prevents the document from becoming ready.

## Storage

```text
PostgreSQL
  → documents, chunks, ingestion jobs

MinIO
  → original files

Elasticsearch
  → keyword retrieval

Qdrant
  → vector retrieval

Neo4j
  → DICE propositions, entities, relationships and projection lineage
```

## Retrieval

Retrieval combines Elasticsearch and Qdrant:

```text
Query
  ↓
Keyword Search ──┐
                 ├─→ Merge → Deduplicate → Rerank
Vector Search ───┘
                              ↓
                       Permission Filter
                              ↓
                         Source Spans
```

SourceSpans remain the grounding authority.

Retrieved chunk IDs are then used to hydrate persisted DICE semantics:

```text
SourceSpan
   ↓ chunk_id
DICE Proposition
   ↓
Entity + Relationship Lineage
```

Document Service does not perform query-time mission reasoning.

## Evidence Graph

Evidence construction combines retrieved SourceSpans with persisted DICE semantics.

```text
Source Spans
     ↓
DICE Propositions
     ↓
CLAIM + ENTITY Nodes
     ↓
Persisted Semantic Edges
     ↓
Schema + Policy Validation
     ↓
Document Evidence Graph
```

DICE relationships such as:

```text
USES
PART_OF
DEPENDS_ON
CONTAINS
```

remain document-domain relations.

They are not converted into mission judgments such as:

```text
SUPPORTS
CONTRADICTS
MODIFIES
```

Those belong to Agentic Service.

A grounded normalization fallback remains available when persisted DICE evidence is unavailable.

## Evidence Verification

Document Service verifies that evidence remains grounded in source material.

Verification includes:

* source-span validation
* citation validation
* graph validation
* evidence-node support checks
* confidence and coverage scoring

Persisted DICE propositions are not automatically trusted. They may later verify as:

```text
SUPPORTED
PARTIALLY_SUPPORTED
UNSUPPORTED
```

This separates:

```text
DICE
→ what semantic information was extracted

Verification
→ whether the source text actually supports it
```

## Service Boundary

```text
Document Service

ingest
→ parse
→ chunk
→ extract semantics
→ persist graph
→ index
→ retrieve
→ construct evidence
→ verify grounding
```

```text
Agentic Service

interpret
→ plan
→ choose tools
→ collect evidence
→ reason across sources
→ build citations
→ synthesize answer
```

The boundary is therefore:

**Document Service owns grounded document semantics. Agentic Service owns mission reasoning.**

## Agentic Service Integration

For a document-only mission:

```text
Interpret
   ↓
Plan
   ↓
BuildDocumentEvidence
   ↓
VerifyEvidenceGraph
   ↓
Build Citations
   ↓
Compose Answer
```

If a document ID is already known, Agentic Service can call `BuildDocumentEvidence` directly. Document Service performs retrieval within that document scope.

Mixed missions can combine Document Service and Internal Service evidence before synthesis.

## Service API

```text
UploadDocument
GetDocument
GetIngestionJob
SearchDocumentSpans
BuildDocumentEvidence
VerifyEvidenceGraph
```

## Security

Security boundaries include:

* tenant-scoped document access
* tenant-scoped DICE semantic context
* permission filtering
* caller identity propagation
* gRPC transport policies

## Recovery

Persisted ingestion jobs can resume after service restart.

```text
Persisted Job
   ↓
Embabel Parse / Chunk
   ↓
DICE Enrichment
   ↓
Persist + Index
   ↓
Complete
```

## Observability

Tracing covers:

* ingestion
* Embabel parsing/chunking
* DICE extraction and graph projection
* indexing
* retrieval
* evidence construction
* evidence verification
* citation verification

Request, tenant, and trace context propagate across Agentic Service → Document Service gRPC calls.

## Technology

The service uses:

* Java 25
* Spring Boot
* Spring AI
* gRPC
* PostgreSQL
* MinIO
* Elasticsearch
* Qdrant
* Neo4j
* Drivine
* Arrow
* Embabel Agent RAG
* DICE
* Apache Tika
* OpenTelemetry-compatible observability

## Current State

```text
Ingestion Pipeline              🟩 █████████░ 98%
Storage / Persistence           🟩 █████████░ 98%
Hybrid Retrieval                🟩 █████████░ 98%
Evidence Construction           🟩 █████████░ 98%
Evidence Verification           🟩 █████████░ 98%
Service API                     🟩 █████████░ 98%

Overall                         🟩 █████████░ 98%
```

Remaining work is primarily:

* production hardening
* DICE transaction hardening
* ontology/retrieval quality tuning
* failure and load testing
* ingestion format coverage
* observability validation
* Agentic Service integration validation
