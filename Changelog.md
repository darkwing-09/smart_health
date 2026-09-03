# Changelog.md — Chronological Project History

All notable changes to the Personal Health OS project will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Planned (Phase 1)
- Backend project scaffolding with FastAPI 0.111+ and SQLAlchemy 2.0 asyncio.
- Docker Compose setup with PostgreSQL 16, TimescaleDB, and Redis 7.
- Alembic database migration scripts for core health timeline and audit schema.
- Deterministic rolling baseline computation engine with 30-day windowing and circadian hourly profiles.
- Anomaly detection service evaluating z-score deviations against personal baseline.

---

## [0.1.0] - 2026-09-04

### Added
- **Foundational Architecture System:** Established the complete 21-document engineering operating system governing design, safety, implementation, and maintenance.
- **Master Operating Manual (`CLAUDE.md`):** Formalized operating instructions, health safety rules, agent boundaries, and definition of done.
- **System Overview & Quickstart (`README.md`):** Authored system overview, core architectural thesis, tech stack, and developer setup instructions.
- **Product Requirements Document (`PRD.md`):** Formulated target personas, user journeys, 5-tier severity classification, MVP/V1/V2 scope partitioning, and non-goals.
- **Technical Architecture (`Architecture.md`):** Designed end-to-end telemetry pipeline from Android Health Connect through deterministic analytics to LLM interpretation and vector PDF generation.
- **Master Development Plan (`Plan.md`):** Formulated phased milestones with strict prerequisite gates and exit criteria.
- **Implementation Playbook (`Implementation.md`):** Created concrete Kotlin and Python implementation blueprints with code patterns and schema definitions.
- **Engineering Rules (`Rules.md`):** Enacted 28 enforceable rules across health safety, architecture, security, database immutability, and testing.
- **Skills Catalog (`SKILL.md`):** Defined 12 core reusable operational skills for engineers and AI co-developers.
- **Agent System Specifications (`AGENTS.md`):** Formulated missions, boundaries, tools, and prohibitions for 12 system agents.
- **Canonical Prompt Library (`PROMPTS.md`):** Created versioned, structured prompt templates with strict JSON schemas and evaluation benchmarks.
- **API Contract (`API.md`):** Defined complete OpenAPI/REST specifications covering auth, idempotent sync, findings, reports, and care navigation.
- **Conceptual Data Model (`DataModel.md`):** Designed entity relationships, immutability rules, provenance tags, and audit logging tables.
- **Configuration & Environment (`Config.md`, `.env.example`):** Documented environment profiles, physiological thresholds, and sanitized environment variables.
- **Architecture Decision Records (`Decisions.md`):** Captured ADR-001 through ADR-008 resolving wearable scopes, notification channels, booking boundaries, and database choices.
- **Issues & Blockers Registry (`Issues.md`):** Documented active research questions, external dependencies, and regulatory considerations.
- **Testing & Quality Strategy (`TestPlan.md`):** Established comprehensive test vectors for deterministic analytics, agent evaluation, and offline sync.
- **Deployment & DevOps Runbook (`Deployment.md`):** Formulated CI/CD workflows, Docker containerization, database migration runbooks, and disaster recovery plans.
- **Security & Privacy Specification (`Security.md`):** Authored STRIDE threat model, AES-256-GCM encryption specifications, DPDP compliance protocols, and audit retention policies.

### Changed
- Refactored high-level vision to explicitly distinguish deterministic statistical anomaly gating from LLM narrative explanation (ADR-004).
- Isolated external appointment booking into a user-controlled "Doctor Visit Summary" draft flow to eliminate clinical and legal liability in early releases (ADR-003).
