---
name: refresh-anthropic-e2e-model
description: Fix post-credit Anthropic E2E model-not-found failure and verify live E2E tests.
steps:
  - phase: discovery
    steps:
      - "- [x] step 1: rerun E2E after Anthropic credit and capture model-not-found failure"
      - "- [x] step 2: test a current Anthropic model with a tiny live request"
  - phase: implementation
    steps:
      - "- [x] step 1: update Anthropic E2E model default and allow env override"
  - phase: validation
    steps:
      - "- [x] step 1: rerun focused/live E2E checks"
      - "- [x] step 2: run lint and unit/full tests"
      - "- [x] step 3: commit with jj and open next change"
---

# Refresh Anthropic E2E model

## Phase 1 — Discovery
- [x] step 1: rerun E2E after Anthropic credit and capture model-not-found failure
- [x] step 2: test a current Anthropic model with a tiny live request

## Phase 2 — Implementation
- [x] step 1: update Anthropic E2E model default and allow env override

## Phase 3 — Validation
- [x] step 1: rerun focused/live E2E checks
- [x] step 2: run lint and unit/full tests
- [x] step 3: commit with jj and open next change
