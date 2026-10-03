# ADR 0001: Foundation stack and module boundaries

Status: accepted for Phase 0, 2026-10-03.

Use Java 21, Maven Wrapper 3.9.16, Spring Boot 4.1.1, PostgreSQL 17 and Flyway in one Spring Boot process. This is a modular monolith. Packages for a domain module are created only when that module has behavior. Calls between modules should use a narrow application interface, never reach into another module's repository or entity internals. `com.fleetflow` currently contains foundation code only; planned module ownership is in [ARCHITECTURE_PLAN.md](ARCHITECTURE_PLAN.md).

PostgreSQL is durable truth. Flyway owns schema changes; applied `Vn__description.sql` files are immutable, forward-only, and use explicit constraints and indexes. Hibernate validates but never creates deployable schema. Phase 0 has no domain table, so its migration directory intentionally has no versioned SQL. Phase 1 introduces `V1` with the first real entities.

The conceptual entity relationships in [DOMAIN_MODEL.md](DOMAIN_MODEL.md) remain planned. `/api/v1` is reserved for later APIs. Health is the only live HTTP capability in Phase 0.
