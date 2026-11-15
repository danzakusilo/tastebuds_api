-- Baseline: the core schema (created by Flyway) plus pgvector for the vibe engine (docs/PLAN.md section 5).
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
