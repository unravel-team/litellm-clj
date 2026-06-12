---
name: repro-fix-e2e-tests
description: Reproduce failing end-to-end tests, fix root cause, and verify repo gates.
steps:
  - phase: discovery
    steps:
      - "- [x] step 1: run e2e tests and capture exact failure"
      - "- [x] step 2: inspect e2e test config and relevant provider code"
  - phase: implementation
    steps:
      - "- [x] step 1: add or confirm failing coverage for observed behavior"
      - "- [x] step 2: apply minimal fix"
  - phase: validation
    steps:
      - "- [x] step 1: rerun focused e2e/focused tests"
      - "- [x] step 2: run make format, make check, make test or documented fallbacks"
      - "- [x] step 3: describe jj change and open next change"
---

# Reproduce and fix E2E tests

## Phase 1 — Discovery
- [x] step 1: run e2e tests and capture exact failure
- [x] step 2: inspect e2e test config and relevant provider code

## Phase 2 — Implementation
- [x] step 1: add or confirm failing coverage for observed behavior
- [x] step 2: apply minimal fix

## Phase 3 — Validation
- [x] step 1: rerun focused e2e/focused tests
- [x] step 2: run make format, make check, make test or documented fallbacks
- [x] step 3: describe jj change and open next change
