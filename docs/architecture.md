# DevSphere System Architecture & Operations Design

This document details the architectural topology, operational flows, and security boundaries implemented in DevSphere.

## High-Level System Architecture

```mermaid
graph TD
    Client["User / Web Browser"] -->|"HTTPS / WSS (443)"| Nginx["Nginx Reverse Proxy & TLS Termination"]
    
    subgraph "Internal Production Docker Network"
        Nginx -->|"Proxy /api & /actuator (filtered)"| Backend["Spring Boot 4.1.2 Backend (JDK 27)"]
        Nginx -->|"Proxy /*"| Frontend["Next.js 16.4 Frontend (Node.js 22)"]
        
        Backend -->|"JDBC (Port 5432)"| Postgres[("PostgreSQL 18.7 DB\n(devsphere)")]
        Backend -->|"Lettuce (Port 6379)"| Redis[("Redis 7 Cache")]
        Backend -->|"SigV4 REST API"| AWS_S3["AWS S3 Bucket\n(Encrypted Assets)"]
        
        Backend -->|"Internal Metrics (:8081)"| Prometheus["Prometheus Server (:9090)"]
        Alloy["Grafana Alloy Log Collector"] -->|"Docker Socket"| Containers["Docker Containers Logs"]
        Alloy -->|"Loki Push API"| Loki["Grafana Loki Log Storage (:3100)"]
        
        Grafana["Grafana Dashboards (:3000)"] -->|"Query"| Prometheus
        Grafana -->|"Query"| Loki
    end
    
    subgraph "Operational Background Processing"
        Backend -->|"JobQueue Claim"| Quartz["Quartz In-Memory Scheduler"]
        Quartz --> OutboxJob["Outbox Dispatcher Job"]
        Quartz --> AuditJob["Audit Cleanup Job"]
        Quartz --> RetentionJob["Session & Refresh Cleanup Job"]
    end
```

## Key Architectural Patterns

### 1. Transactional Outbox Pattern
To prevent distributed transaction anomalies across PostgreSQL and external message sinks, DevSphere implements the Transactional Outbox pattern:
- Domain mutations write events to the `background_jobs` table within the active database transaction.
- The `OutboxDispatcherJob` runs periodically via Quartz, claiming batches with optimistic locking and exponential backoff retry logic.
- Failed events transition to dead-letter states after exceeding maximum retry attempts, triggering Prometheus alerts.

### 2. Quartz Scheduler In-Memory Architecture
- `org.springframework.boot:spring-boot-starter-quartz` is configured with `spring.quartz.job-store-type=memory`.
- Because persistent state resides in the ACID `background_jobs` table, Quartz operates statelessly in memory without requiring extra database tables, maintaining schema cleanliness (strictly 36 Flyway-managed tables).

### 3. Observability & Security Segregation
- The management port is isolated to `8081` internally.
- Nginx blocks public access to `/actuator/**` by default while allowing `/actuator/health` for orchestrator probes.
- Scrapes on `/actuator/prometheus` require `ROLE_ADMIN` bearer token authorization, automatically refreshed via helper scripts.
