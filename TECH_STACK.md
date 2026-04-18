# Stop @ Estimate Tech Stack

## Runtime

- Java 21
- Spring Boot 3.x
- Spring MVC
- Thymeleaf for the sidebar HTML shell
- Vanilla JavaScript for sidebar behavior
- Maven wrapper
- Embedded Tomcat

## Clockify Integration

- Clockify `addon-sdk` for manifest and Clockify auth helpers
- RS256 JWT verification using the Clockify public key
- Spring `RestClient` for Clockify backend and reports API calls

## Persistence

- PostgreSQL 16
- Flyway migrations
- JSON columns for snapshot and membership restoration data where appropriate

## Scheduling And Background Work

- Spring scheduling for cutoff-job processing and workspace reconcile intervals
- Database-backed pending cutoff jobs instead of in-memory timers

## Observability

- SLF4J + Logback structured logs
- Spring Actuator health endpoint
- Request correlation ID support on lifecycle, webhook, and `/api/*` paths

## Packaging And Deployment

- Docker image for local and production parity
- One Spring Boot service container
- One PostgreSQL service or managed Postgres instance
- HTTPS base URL required for private Clockify install

## Local Tooling

- Docker Compose for local PostgreSQL
- `ngrok` or an equivalent HTTPS tunnel for Clockify private install during development

## Why This Stack

This stack is fixed because the local source material already proves the hard parts:

- The starter Java SDK already demonstrates the right Clockify auth, manifest, lifecycle, iframe, and protected addon API patterns.
- The local Estimate Guard reference already demonstrates the required event set, cutoff logic, reconcile pattern, and lock/restore model.
- The local HTTP Actions reference already demonstrates a production-grade Java + Postgres + Flyway deployment posture.

That means Claude can build on known-good patterns instead of inventing a new stack.

## Required Libraries And Responsibilities

### Spring Boot

- App bootstrap
- HTTP routing
- configuration binding
- scheduler wiring
- health endpoint

### Thymeleaf

- Render the sidebar shell with addon name, workspace bootstrap data, and token bootstrap hooks

### Vanilla JavaScript

- Extract `auth_token`
- strip it from the URL
- call `/api/context`
- call guard summary and reconcile APIs
- render summary table and status blocks

### PostgreSQL + Flyway

- Durable installation state
- durable webhook registration storage
- lock snapshot restore data
- pending cutoff jobs
- operational event history

### RestClient

- Project fetch
- project membership update
- project visibility update
- running time entry lookup
- timer stop patch
- report summary and expense aggregation calls

## Versioning Expectations

- Prefer the versions already proven in the local Java references unless a build error forces a newer compatible version.
- Do not switch to a TypeScript or Worker-first architecture.
- Do not replace PostgreSQL with file storage or SQLite.
