# FailureForge — Development Guide

Oct 7, 2026 · Musheer Ahmad

> Exported from the team's claude.ai doc for use in the repo. For decisions made after Oct 7, CLAUDE.md is the source of truth (e.g. D-004: Claude API now, office LLM later).

## Overview

FailureForge is an agent that tries to break a code change before production does. It reads a Gerrit change plus its JIRA ticket and wiki, works out the rules the change must guarantee, attacks those rules with real tests in a sandbox, and posts only failures it can reproduce.

**Problem it solves.** CI and code review check what developers already expect: it builds, old tests pass, the code looks right. The dangerous bugs come from what nobody anticipated: edge values, illegal state sequences, races and compatibility breaks.

**The one rule that makes it trustworthy:** the AI says what should be true; running code decides whether it is. The AI never invents a value and declares a bug. Every finding comes with a test that fails on its own.

**Inputs:** Gerrit change link, JIRA ticket link, wiki/Confluence links (entered in the portal).

**Outputs:** one of three verdicts per rule (nothing found, couldn't check, counterexample found), a minimal failing test, and an inline Gerrit comment the developer can mark Accept, Dispute or False Positive.

## Key design principle: AI picks the target, proven tools do the shooting

We do not build our own test runner, input generator or shrinker. Property-based testing (PBT) and concurrency-testing libraries already generate edge-case inputs, run them, and shrink any failure to its smallest form. The LLM only decides *what* to test.

| Job | Who does it | Why |
| --- | --- | --- |
| Understand intent, derive rules | LLM | Needs language understanding of ticket + diff |
| Choose attacks per rule | LLM (4 specialists) | Needs reasoning about how code can fail |
| Write test code | LLM, checked by compiler | Fast to generate; compiler rejects junk |
| Generate input values | jqwik / Lincheck | Systematic and reproducible, no invented values |
| Run tests | Docker sandbox | Real code, real result |
| Shrink failures | jqwik / Lincheck (built in) | Proven algorithms, free |
| Decide verdict | Plain Java code, no AI | Deterministic and auditable |

This split is what makes the system accurate. It also cuts build time: the hardest parts (generation, shrinking) come ready-made.

## System architecture

```
Portal (React)        Spring Boot API         PostgreSQL
Gerrit, JIRA, wiki ──► REST, SSE, job queue ──► runs, rules, findings
                            │ queued run
                            ▼
┌─ Orchestrator worker · Spring AI + LLM (behind LlmClient) ─────────────┐
│  Ingest ──► Rule extraction  ◄──────────────── on dispute, rerun ───┐  │
│  (diff, ticket,  (JSON rules, each cites a source)                  │  │
│   wiki, tests)        │                                             │  │
│        ┌──────────┬───┴──────┬─────────────┐                        │  │
│    Boundary     State     Concurrency    Compat                     │  │
│    (jqwik)  (jqwik stateful) (Lincheck,  (japicmp, buf)             │  │
│                            jcstress)                                │  │
│        └──────────┴───┬──────┴─────────────┘                        │  │
│  Test writer ──► Docker sandbox ──► Verdict engine                  │  │
│  (compile,       (run on parent     (plain code, no AI)             │  │
│   repair ≤3)      and on patch)            │                        │  │
└────────────────────────────────────────────┼────────────────────────┼──┘
                                             ▼                        │
                         Gerrit comment + portal report ──────────────┘
                         (minimal test; Accept / Dispute / False Positive)
```

A run flows top-down: the API queues it, one worker runs the whole pipeline, and results go back to Gerrit and the portal. The verdict engine is plain code, so no finding rests on the AI's word alone. A dispute sends the run back to rule extraction with the developer's constraint added.

## Components in detail

### 1. Ingest

Collects everything the agent needs, and nothing more.

- **Gerrit REST API:** fetch change metadata, the current patchset, the diff and the parent commit.
- **Git checkout:** clone the repo at the exact patchset ref, and keep the parent commit for comparison.
- **JIRA REST API:** ticket title, description, acceptance criteria, linked issues.
- **Confluence REST API:** wiki pages linked in the portal, converted to plain text.
- **Scope reduction:** send the LLM only the changed methods, their direct callers and the existing tests that cover them. Use a Java parser (JavaParser) to extract these. Smaller context = cheaper, faster and fewer hallucinations.

### 2. Rule extraction (invariants)

The LLM turns intent into a list of testable rules, as structured JSON:

```json
{
  "id": "R3",
  "rule": "Stock never goes below zero, even with concurrent orders",
  "type": "concurrency",
  "source_quote": "Orders must fail when the item is out of stock",
  "source": "JIRA-1234 acceptance criteria",
  "code_location": "OrderService.placeOrder"
}
```

- Every rule must quote where it came from (ticket, wiki or code). A rule with no source is dropped. This single check removes most hallucinated rules.
- Use Spring AI structured output so the response is parsed straight into a Java record; invalid JSON is retried.
- Rules are shown in the portal so the team can edit or delete one before attacks start (optional human-in-the-loop).

### 3. Four specialists

Each specialist is a focused prompt with its own toolset. It receives the rules of its type and returns attack plans: what to test, which inputs or sequences matter, and which library to use.

| Specialist | Library | What it attacks | Example |
| --- | --- | --- | --- |
| Boundary | jqwik (`@Property`, `@ForAll`, edge cases) | Limits and odd values | null, 0, -1, `Integer.MAX_VALUE`, empty list, emoji in a string |
| State | jqwik stateful (action chains) | Illegal or unexpected sequences | refund after cancel, double submit, retry after timeout |
| Concurrency | Lincheck (Kotlin), jcstress | Races, lost updates, deadlocks | 2 parallel orders with stock = 1 |
| Compat | japicmp, `buf breaking` (gRPC), Gson/Jackson round-trips | Breaking old clients or stored data | removed field, changed enum order, renamed proto field |

### 4. Test writer

- The LLM writes a test file in the project's own framework (JUnit 5 + jqwik), using the attack plan.
- The test is compiled in the sandbox. If it fails to compile, the compiler error goes back to the LLM. Maximum 3 repair attempts, then the rule is marked **couldn't check**.
- Generated tests may only call public or package-level APIs of the changed classes, never reflection hacks. This keeps findings meaningful.

### 5. Sandbox

- One Docker container per run, created from a base image with JDK, Gradle/Maven and the libraries above.
- **No network**, CPU and memory limits, a hard timeout (for example 10 minutes per run).
- A shared, read-only Gradle/Maven cache volume so builds don't download the internet every time. This is the biggest speed win.
- Container is destroyed after the run; logs and test files are saved first.

### 6. Verdict engine (no AI)

Every generated test runs **twice**: on the parent commit and on the patch. Plain Java code decides the verdict:

| Parent commit | Patch | Verdict |
| --- | --- | --- |
| Pass | Fail | **Counterexample found** (the change broke a rule) |
| Fail | Fail | Pre-existing bug, or the test is wrong: shown separately, low confidence |
| Pass | Pass | Nothing found for this rule |
| Doesn't compile | Doesn't compile | Couldn't check |

- Failures are rerun 5 times. If results differ, the finding is marked **flaky** and not posted as a bug.
- For new code with no parent behaviour, only the patch run counts, and the finding is labelled "new code".

### 7. Reporting

- **Gerrit:** inline review comment on the exact line, via the Gerrit REST review API. It contains the rule, the minimal counterexample (for example "2 requests, stock = 1"), and a link to the full report.
- **Portal:** full report per run: rules, attacks, verdicts, logs and the test file with a "copy test" button.
- Live progress in the portal using Server-Sent Events (SSE), since a run takes minutes.

### 8. Dispute loop

- **Accept:** finding confirmed; the developer can add the generated test to the change.
- **Dispute:** the developer writes a constraint ("amount is validated upstream, never negative"). It is saved as an assumption, rules are re-derived and the attack re-runs. The agent must reconsider, not repeat itself.
- **False Positive:** saved per repository and fed back into future prompts as "do not report patterns like this". The agent improves on each codebase over time.

## Tech stack

The backend is Spring Boot, as the team decided. Everything below fits that choice.

| Layer | Choice | Notes |
| --- | --- | --- |
| Portal UI | React + TypeScript | Submit links, watch progress, read reports |
| API | Spring Boot 4.1 (Java 21) | REST endpoints, SSE for live progress. Version decided in CLAUDE.md D-014 |
| Agent / LLM calls | Spring AI | Tool calling, structured output into Java records, prompt templates |
| LLM | Claude API now, Samsung office LLM later (D-004) | All calls behind one `LlmClient` interface; no confidential code to external APIs without approval |
| Job queue | PostgreSQL-backed queue | Runs take minutes, so they run in background workers |
| Database | PostgreSQL | Runs, rules, findings, feedback |
| Code parsing | JavaParser | Extract changed methods, callers, related tests |
| Sandbox | Docker | No network, limits, timeout, shared build cache |
| Testing libraries | JUnit 5, jqwik, Lincheck, jcstress | Generation and shrinking built in |
| Compat checks | japicmp, buf | Java API and gRPC proto compatibility |
| Integrations | Gerrit, JIRA, Confluence REST APIs | Read change + intent, post review comments |
| Observability | Spring Boot Actuator + structured logs | Track tokens, time and cost per run |

**MVP scope:** Java/Kotlin code that runs on the JVM (services, libraries, Android business logic in plain modules). Android instrumented and UI tests are out of scope: too slow for this loop.

## Data model

Five PostgreSQL tables cover the MVP.

| Table | Key columns | Purpose |
| --- | --- | --- |
| `run` | id, gerrit_change, patchset, parent_sha, status, started_at, tokens_used, cost | One analysis of one change |
| `rule` | id, run_id, text, type, source_quote, source, code_location, status | Invariants derived for the run |
| `attack` | id, rule_id, specialist, plan_json, test_code, compile_attempts | One attempt to break a rule |
| `finding` | id, attack_id, verdict, parent_result, patch_result, minimal_input, flaky, gerrit_comment_id | The outcome of an attack |
| `feedback` | id, finding_id, repo, type (accept / dispute / false_positive), text | Developer response; drives the dispute loop and per-repo learning |

Test files and logs go to disk or object storage, with the path saved in `attack` and `finding`.

## Additions beyond the original idea

Ranked by impact on accuracy and on the contest result.

1. **Parent vs. patch differential run.** Proves the change caused the failure. Without it, judges will ask how we know the bug is new. (Built into the verdict engine above.)
2. **Source-quoted rules.** Every rule cites the ticket, wiki or code line it came from. Unsourced rules are dropped.
3. **"Add this test" button.** Each finding ships a ready-to-commit test file. The developer gets a regression test, not just a warning.
4. **Budgets per run.** Max LLM iterations, tokens and wall-clock minutes. Stops runaway cost and stuck runs, and gives a cost-per-run number for the pitch.
5. **Flaky detection.** Reruns failures 5 times; inconsistent results are never posted as bugs.
6. **Per-repo memory of false positives.** The agent stops repeating the same wrong finding on the same codebase.
7. **Benchmark on real past bugs.** See the demo plan below. Hard numbers beat a polished demo.
8. **Later (post-contest):** mutation testing (PIT) to show where existing tests are weak; CI trigger on every new patchset instead of manual submission; support for Python and TypeScript using Hypothesis and fast-check.

## Build plan

Five phases. Each ends with something that runs end to end. Do not start a phase until the previous one's done criteria pass. Dates depend on the contest deadline (open question).

1. **Pipeline skeleton (no AI)**
   - Portal form, Spring Boot API, job queue, worker.
   - Worker clones a Gerrit change at its patchset and runs the existing tests in Docker.
   - Done when: submitting a Gerrit link shows the existing test results live in the portal.
2. **Rules + Boundary specialist (first real demo)**
   - Ingest JIRA/wiki, extract source-quoted rules, generate jqwik tests, compile-repair loop.
   - Parent vs. patch runs and the verdict engine.
   - Done when: on a change with a seeded boundary bug, FailureForge reports a shrunk counterexample.
3. **State + Concurrency specialists**
   - jqwik stateful tests and Lincheck/jcstress tests.
   - Flaky reruns.
   - Done when: the "2 requests, stock = 1" race is found and shrunk automatically.
4. **Compat + Gerrit comments + dispute loop**
   - japicmp and buf checks; inline Gerrit comments; Accept / Dispute / False Positive flow.
   - Done when: a dispute changes the rules and the re-run result.
5. **Benchmark, polish, pitch**
   - Run the benchmark, tune prompts, build the demo script.
   - Done when: we have measured recall, false-positive count and average time and cost per run.

One specialist that is genuinely accurate beats four shallow ones. If time runs short, ship phases 1, 2 and 3 well and present Compat as the next step.

## Suggested team split

Owners to be assigned; areas are independent enough to build in parallel after phase 1.

| Area | Scope | Owner |
| --- | --- | --- |
| Platform | Spring Boot API, queue, PostgreSQL, Docker sandbox, verdict engine | TBD |
| Agent | Spring AI, prompts, rule extraction, specialists, test writer, repair loop | TBD |
| Integrations + UI | Gerrit, JIRA, Confluence clients, React portal, SSE | TBD |
| Quality | Benchmark set, seeded bugs, metrics, demo script | TBD |

## Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| LLM access (Claude API approval for Samsung code; office LLM quality) | Develop on open-source/sample repos; keep the LLM behind one interface so it can be swapped |
| False positives hurt trust | Parent vs. patch runs, source-quoted rules, flaky reruns, verdict decided by code only |
| Generated tests don't compile | Compiler-error repair loop, max 3 tries, then "couldn't check" |
| Slow builds | Shared Gradle/Maven cache, run only tests for changed modules |
| Repos needing databases or services | MVP targets code that unit-tests on the JVM; Testcontainers later |
| Scope creep | Done criteria per phase; Compat is cuttable |

## Demo and benchmark plan

- Collect 15 to 20 past bug-fix commits from Samsung repos, covering all four specialist types.
- For each, revert the fix to recreate the buggy change, and write a short ticket describing the intended behaviour.
- Run FailureForge on each and record: bugs caught, false positives, time per run and LLM cost per run.
- Pitch with real numbers (for example "caught X of 18 known bugs with Y false positives"), then a live run on one change.

## Learning path

What each team member learns, in build order:

1. **Spring Boot + async jobs:** REST APIs, background workers, SSE.
2. **Docker:** images, containers, resource limits, network isolation, volumes.
3. **Spring AI:** prompt templates, tool calling, structured output. This is the core skill of agent engineering.
4. **Agent loop design:** plan, act, observe, replan, with budgets and stop conditions.
5. **Property-based testing (jqwik):** properties instead of examples, generators, shrinking.
6. **Concurrency testing (Lincheck, jcstress):** linearizability and race detection.
7. **Dev-tool APIs:** Gerrit, JIRA, Confluence REST.

## Open questions

- [ ] Contest deadline and demo format?
- [ ] Can Samsung source code be sent to the Claude API, or only sample/open-source repos?
- [ ] Which repositories will we use for the benchmark?
- [ ] Who owns each area?
