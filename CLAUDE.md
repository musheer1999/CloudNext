# FailureForge — Project Context & Decision Log

> Hand this file to any Claude session (office or personal) at the start of work.
> It is the single source of truth for what we are building, what is decided, and where we are.
> Update it every time a decision is made or a phase moves.

Last updated: 2026-10-12 (all 5 phases built as a POC: 4 specialists, feedback loop, Gerrit review, benchmark 4/4 caught, 0 false positives; D-032..D-039)
Owner: Musheer Ahmad (Samsung R&D Institute, Noida)

Full design doc: docs/DEVELOPMENT_GUIDE.md (read the relevant section before building each phase).

---

## 1. What we are building (one paragraph)

FailureForge is an AI agent for a Samsung internal agent contest. It takes a Gerrit code change plus its JIRA ticket and wiki pages, works out the rules the change must guarantee, has four specialists (Boundary, State, Concurrency, Compat) attack those rules with real tests in a Docker sandbox, shrinks any failure to its smallest form, and posts reproducible findings as Gerrit comments that developers mark Accept / Dispute / False Positive.

**Core rule:** the AI says what should be true; running code decides whether it is. The AI never invents a value and declares a bug.

## 2. Current status

| Item | State |
| --- | --- |
| Idea + design | Done (design doc shared with team) |
| Phase 1 — pipeline skeleton | **Done 2026-10-11.** Portal → API → Postgres queue → worker → Gerrit REST → git checkout → **Docker sandbox** (D-026) → JUnit results + live log in portal. Verified on GerritHub change 1236098, run #6: 88/88 tests pass in 72 s, test step with `--network none`, no containers left behind. Known gaps: Gradle path in Docker not yet tested on a real repo; no automated test of the timeout path. |
| Phase 2 — rules + Boundary specialist | **Done 2026-10-11 (D-031).** Ticket text + diff → quoted rules (local Qwen3-8B via Ollama) → Boundary specialist writes jqwik test → compile-repair → parent vs patch → verdict. Finds the planted SHOP-42 bug with a shrunk counterexample in ~4–7 min. Demo script: `docs/DEMO.md`. Not done yet: JIRA/Confluence fetching (office), flaky reruns (Phase 3), Gerrit comments (Phase 4). |
| Phase 3 — State + Concurrency specialists | **Done 2026-10-12 (D-037).** State: jqwik operation sequences, shrunk (SHOP-43 → `[PAY, CANCEL, REFUND, REFUND]`). Concurrency: latch-started threads ×200 (SHOP-44 → `threads=6, stock=2 -> reservations=3`). Flaky: generated tests re-run up to 5× via surefire; flaky → FLAKY verdict. Lincheck/jcstress not used (plain threads were enough). |
| Phase 4 — Compat + Gerrit comments + dispute loop | **Done 2026-10-12 (D-032, D-034, D-035).** Compat = japicmp (SHOP-45 → removed `finalPrice`). Accept / Dispute (re-run with the constraint) / False Positive (per-repo memory). Gerrit review: preview works; **posting not yet tried** (needs GerritHub HTTP credentials in `application-local.yml`). buf (gRPC) not wired. |
| Phase 5 — Benchmark, polish, pitch | **POC done 2026-10-12 (D-036, D-038).** Benchmark page: 5 sample changes → **4/4 planted bugs caught, 0 false positives**, avg 12m28s/run (one 35-min outlier from memory pressure, D-039), 25.6k tokens. Portal redesigned. Demo video script: `docs/DEMO.md`. **Still to do:** the real benchmark on 15–20 reverted past Samsung bug fixes (office). |
| JIRA / Confluence | **Done (D-033)**, tested anonymously on Apache's public JIRA/Confluence; office auth via `failureforge.atlassian.token`. |
| Code repository | GitHub (public): https://github.com/musheer1999/CloudNext, branch `main`. Download: https://github.com/musheer1999/CloudNext/archive/refs/heads/main.zip. Demo sample repo: https://github.com/musheer1999/failureforge-sample (changes on review.gerrithub.io). |
| LLM in use | Dev: local Ollama `failureforge-qwen3` (Qwen3-8B, 8k context, D-039). Demo/office: MuseGlimmer 30B (D-028), config only. Claude API not used. |
| Demo data | `docs/demo/failureforge-demo.sql`: database dump with the finished demo runs #16–#23 and the benchmark, so the office portal can show them without re-running (see section 7). |
| Dev machine (personal laptop) | JDK 23, Node 24.21, PostgreSQL 16 (Windows service; may need a manual start), git, WSL2 Ubuntu + Docker Engine 29.1 (called as `wsl -d Ubuntu -e docker`). Port 8080 taken by Apache httpd. |

## 3. Architecture (summary)

```
Portal (React, :5173) ──► Spring Boot API (:8090, REST + SSE) ──► PostgreSQL (runs = job queue)
                                │ worker claims the oldest QUEUED run (SKIP LOCKED)
                                ▼
RunPipeline (server/src/main/java/com/failureforge/worker/RunPipeline.java)
  1. Gerrit REST: change → patchset ref, patch + parent SHA          (gerrit/GerritClient)
  2. git fetch --depth 2, patch dir + parent worktree                (git/GitCheckout)
  3. Existing tests on patch and parent in the Docker sandbox        (sandbox/DockerTestRunner)
  4. Compat: japicmp on compiled parent vs patch classes, no LLM      (agent/CompatCheck)
  5. Ingest: ticket text + JIRA issue + Confluence pages             (ticket/TicketSource)
  6. Rules: LLM → JSON, each quote checked, false positives dropped   (agent/RuleExtractor)
  7. Attacks: per rule type → Boundary | State | Concurrency          (agent/AttackLoop, TestWriter, Specialist)
     LLM writes a jqwik test → compile on patch, repair ≤3 → run on parent → reruns ×5 for flakiness
  8. Verdict engine: plain code, parent vs patch per check            (verdict/VerdictEngine)
  9. Report: portal; Gerrit review on request; Accept/Dispute/FP       (gerrit/GerritReview, run/RunController)
  Dispute → new run with the developer's constraint as a fact (run.constraints)
LLM: one LlmClient, OpenAI-compatible /v1/chat/completions (Ollama dev, office MuseGlimmer)
```

Sandbox per check: copy checkout into a Docker volume → "prepare" container (network on: deps + compile) →
"test" container (`--network none`, 2 CPU, 4 GB, 2048 pids, 10 min) → copy only the JUnit reports back.

Verdict table (verdict engine, no AI):

| Parent | Patch | Verdict |
| --- | --- | --- |
| Pass | Fail | Counterexample found |
| Fail | Fail | Pre-existing bug or wrong test (low confidence) |
| Pass | Pass | Nothing found (rule holds) |
| Fail | Pass | Fixed by the change |
| Different on re-runs | | Flaky (never a finding) |
| — (only in patch) | any | New test |
| No compile | No compile | Couldn't check |

## 4. Tech stack

| Layer | Choice |
| --- | --- |
| Portal | React 19 + TypeScript, Vite 8 (D-024) |
| API | Spring Boot 4.1, Java 21, Maven wrapper, SSE for live progress (D-014, D-015, D-016) |
| LLM client | Own small OpenAI-compatible client (`llm/LlmClient`), not Spring AI (D-030) |
| LLM (dev) | Ollama on the laptop, OpenAI-compatible API (D-028) |
| LLM (demo) | Office MuseGlimmer 30B, OpenAI-compatible, swapped via config only (D-028) |
| DB + queue | PostgreSQL 16, plain SQL via JdbcClient, Flyway migrations, `SKIP LOCKED` queue (D-019) |
| Code context | `git diff` + full changed main Java files, size-capped (JavaParser not needed so far) |
| Sandbox | Docker Engine on Linux (WSL2 for dev), `docker` CLI commands; no network, CPU/mem limits, timeout, shared build cache (D-025, D-026) |
| Test libraries | JUnit 5 + jqwik 1.10.2 (added to FailureForge's copy of the pom); Lincheck/jcstress not used |
| Compat checks | japicmp 0.26.3 (Java API); buf (gRPC) not wired |
| Integrations | Gerrit (REST + git), JIRA + Confluence (REST) |

## 5. Decision log

Status: **Decided** = team/owner confirmed · **Proposed** = in design doc, not yet confirmed · **Superseded** = replaced by a later decision.

### D-001 — Idea: FailureForge
- Date: 2026-09 · Status: Decided
- Decision: Build an adversarial verification agent for code changes.
- Why: Fits contest rule (coding/dev helper agent for Samsung engineers); targets bugs CI and review miss.

### D-002 — Backend in Spring Boot, not Node.js
- Date: 2026-09 · Status: Decided
- Decision: Spring Boot (Java 21) for API and worker.
- Why: Team choice; code will be written with AI assistance, and the JVM matches the code under test and the testing libraries.

### D-003 — Portal input = Gerrit link + JIRA + wiki links
- Date: 2026-09 · Status: Decided
- Decision: Portal-style UI where the user submits a Gerrit change link plus JIRA and wiki links.

### D-004 — LLM: Claude API now, office LLM later
- Date: 2026-10-10 · Status: Decided
- Decision: Use the Claude API during development. All LLM calls go through one `LlmClient` interface (backed by Spring AI `ChatClient`). The provider, model name, API key and base URL come from `application.yml` / environment variables, never hard-coded.
- Why: Lets us switch to the Samsung office LLM API by changing config and one adapter, without touching agent logic.
- Default model: `claude-sonnet-5-5` (configurable per step; a cheaper model can be used for simple steps later).
- Constraint: Until security approval is confirmed, do not send confidential Samsung source code to the external Claude API. Develop and benchmark on open-source or sample repos.

### D-005 — AI proposes, code decides
- Date: 2026-10-07 · Status: Decided (core idea)
- Decision: The verdict engine is plain Java code. The LLM never decides whether a bug exists.

### D-006 — Use property-based testing libraries for generation and shrinking
- Date: 2026-10-07 · Status: Proposed
- Decision: jqwik and Lincheck/jcstress generate inputs and shrink failures. We do not build our own generator or shrinker.
- Why: Proven, reproducible, saves the hardest build work.

### D-007 — Parent vs. patch differential runs
- Date: 2026-10-07 · Status: Proposed
- Decision: Every generated test runs on the parent commit and on the patch; verdict from the table in section 3.
- Why: Proves the change caused the failure.

### D-008 — Rules must cite their source
- Date: 2026-10-07 · Status: Proposed
- Decision: Each rule is structured JSON with `source_quote` + `source`. Rules without a source are dropped.
- Why: Removes most hallucinated rules.

### D-009 — Sandbox design
- Date: 2026-10-07 · Status: Proposed
- Decision: One Docker container per run, no network, CPU/memory limits, ~10 min timeout, shared read-only Gradle/Maven cache.

### D-010 — Reliability limits
- Date: 2026-10-07 · Status: Proposed
- Decision: Max 3 compile-repair attempts per test; failures rerun 5 times, inconsistent = flaky, never posted as a bug; per-run budget on LLM iterations, tokens and minutes.

### D-011 — MVP scope: JVM code only
- Date: 2026-10-07 · Status: Proposed (awaiting team confirmation)
- Decision: Java/Kotlin code that unit-tests on the JVM. No Android instrumented/UI tests.
- Why: Instrumented tests are too slow for the attack loop.

### D-012 — Storage
- Date: 2026-10-07 · Status: Proposed
- Decision: PostgreSQL with tables `run`, `rule`, `attack`, `finding`, `feedback`; job queue also in Postgres; test files and logs on disk/object storage.

### D-013 — Build plan in five phases
- Date: 2026-10-07 · Status: Proposed
1. Pipeline skeleton (no AI): portal → API → queue → worker runs existing tests in Docker.
2. Rules + Boundary specialist + verdict engine (first real demo).
3. State + Concurrency specialists + flaky reruns.
4. Compat + Gerrit comments + dispute loop.
5. Benchmark (15–20 reverted past bug fixes), polish, pitch.

### D-014 — Spring Boot 4.1 + Spring AI 2.0 (not Boot 3)
- Date: 2026-10-10 · Status: Decided (owner delegated the choice; criterion: "must be easy to use on the office laptop")
- Decision: Spring Boot 4.1.x (4.1.1 at start) and Spring AI 2.0.x (2.0.1 at start). Refines D-002; replaces "Spring Boot 3" in the tech stack.
- Why: Spring AI 2.0 (GA June 2026) needs Boot 4; Spring AI 1.1.x is the last line for Boot 3. Free (OSS) support for Boot 3.5 ended 2026-06-30, so it gets no free security fixes, and office security scans are likely to flag it. start.spring.io no longer offers Boot 3. Boot 4.1 has free support until 2027-07-31. Boot 4 still runs on Java 17+, so Java 21 stays.
- Note: Boot 4 uses Jackson 3 (package `tools.jackson.*`, e.g. `asString()` instead of `asText()`). Check that the office Maven mirror has Boot 4.1 / Spring AI 2.0 (see open questions).

### D-015 — Build tool: Maven with the Maven wrapper
- Date: 2026-10-10 · Status: Proposed
- Decision: `server/` builds with Maven through `mvnw` / `mvnw.cmd` (no Maven install needed). No Gradle toolchain auto-download.
- Why: Easiest on a corporate laptop: one standard `~/.m2/settings.xml` mirror entry points everything at the internal Nexus/Artifactory. Gradle wrapper and JDK auto-download are more often blocked by proxies. The pom is also easy for newcomers to read.

### D-016 — Java 21 language level, any JDK 21+ builds it
- Date: 2026-10-10 · Status: Proposed
- Decision: Compile with `--release 21` (`<java.version>21</java.version>`). Any JDK 21 or newer can build and run it (home PC uses JDK 23). The Docker sandbox base image uses JDK 21 LTS.
- Why: Keeps the D-002 Java 21 target without forcing everyone to install exactly JDK 21.

### D-017 — Sandbox behind a TestRunner interface; dev-only local runner until a container engine exists
- Date: 2026-10-10 · Status: Proposed
- Decision: The worker calls a `TestRunner` interface, selected by `failureforge.sandbox.mode`. `local` = run tests directly on the machine (dev only, trusted sample repos only, logs a warning). `docker` = the D-009 sandbox, built 2026-10-11 (see D-026); it is now the default.
- Why: Docker/WSL2 are not installed on the home PC; this let Phase 1 be built and verified now without changing worker code later. The Windows-only CRLF test failures seen on 2026-10-10 show why the real runs must use a Linux container.
- Open: Docker Desktop needs a paid licence for companies over 250 employees. For the office laptop consider Docker Engine inside WSL2, Rancher Desktop, or Podman (docker-java can talk to their Docker-compatible API).

### D-018 — Phase 1 runs API and worker in one Spring Boot process
- Date: 2026-10-10 · Status: Proposed
- Decision: One app hosts the REST API, SSE and the queue worker (polls every 2 s, one run at a time). Live log lines are saved to `run_log` and pushed to SSE subscribers held in memory.
- Why: Simplest to run and learn. Splitting into separate worker processes later needs only Postgres LISTEN/NOTIFY for SSE; the queue already supports many workers (D-019).

### D-019 — Data access: plain SQL (JdbcClient) + Flyway; queue with FOR UPDATE SKIP LOCKED
- Date: 2026-10-10 · Status: Proposed
- Decision: No JPA/Hibernate. All SQL is in `RunRepository` using Spring's `JdbcClient`. Schema is versioned by Flyway files in `server/src/main/resources/db/migration` (never edit an applied file; add V2, V3, ...). The `run` table is the job queue (`status='QUEUED'`), claimed atomically with `FOR UPDATE SKIP LOCKED`. On startup, runs left `RUNNING` by a dead worker are marked `FAILED`.
- Phase 1 tables: `run`, `run_log`, `test_case`. D-012's `rule`, `attack`, `finding`, `feedback` are added from Phase 2.
- Why: The queue needs hand-written SQL anyway; plain SQL is transparent for a team new to the stack.

### D-020 — Checkout via git CLI: fetch only the patchset ref, depth 2
- Date: 2026-10-10 · Status: Proposed
- Decision: Gerrit REST `GET /changes/{project~number}?o=ALL_REVISIONS&o=ALL_COMMITS` gives the patchset ref, fetch URL, patch SHA and parent SHA. The worker runs `git init`, `git fetch --depth 2 <url> <ref>` (patch + parent, needed for D-007), `git checkout --detach FETCH_HEAD`. Fetch retries up to 3 times. Private Gerrit uses the Gerrit HTTP password via a Basic auth header (REST `/a/` prefix, `git -c http.extraHeader`), never in the URL or the logs.
- Supported link shapes: `/c/<project>/+/<n>[/<patchset>]`, `/c/<n>`, `/#/c/<n>`, `/<n>`, with optional context path (e.g. `/gerrit`).

### D-021 — Portal on Vite 6 until Node is upgraded
- Date: 2026-10-10 · Status: Superseded by D-024
- Decision: React 19 + TypeScript, built with Vite 6 and `@vitejs/plugin-react` 4. Dev server on :5173 proxies `/api` to the server, so no CORS setup is needed.
- Why: Current Vite 8 needs Node 22.12+; the home PC has 22.11. After upgrading Node (and checking the office laptop's Node), move to current Vite by changing two versions in `portal/package.json`.

### D-022 — Server default port 8090
- Date: 2026-10-10 · Status: Proposed
- Decision: `server.port` defaults to 8090 (env `PORT` overrides).
- Why: 8080 is commonly taken (Apache httpd on the home PC; often other tools on office machines).

### D-023 — Phase 1 test target: GerritHub `amarula/chatgpt-code-review-gerrit-plugin`
- Date: 2026-10-10 · Status: Proposed
- Decision: Public, Maven, Java 21, ~88 tests, about 1 minute per run. Use change 1236098 (works). Changes after 2026-05-05 currently fail in the repo's own JS lint step before tests run (reported correctly as a pipeline error).
- Why: Open-source, so no D-004 concern. Later: our own small sample repo on GerritHub with seeded bugs for Phase 2.

### D-024 — Portal on current Vite 8 (Node 24 LTS)
- Date: 2026-10-11 · Status: Decided (owner)
- Decision: Owner upgraded the home PC to Node 24.21 (LTS). Portal moved to Vite 8.3 + `@vitejs/plugin-react` 6.1. Supersedes D-021.
- Why: Current, supported tools. Office laptop needs Node 22.12+ too (see open questions).
- Verified: clean install with no engine warnings, `npm run build` and `npm run lint` pass; full run #5 through the portal on 2026-10-11 (live log + results, 88 tests).

### D-025 — Sandbox = Docker Engine on Linux, driven by `docker` CLI commands
- Date: 2026-10-11 · Status: Decided (owner + senior)
- Decision: The sandbox runs on Linux with Docker Engine (no Docker Desktop / Rancher Desktop; not allowed on the office laptop). The worker runs plain `docker run ...` commands through `ProcessRunner` (same pattern as git/mvn), not the docker-java library. Replaces "docker-java" in the tech stack and D-009's tooling detail (D-009's limits still apply: no network, CPU/memory limits, timeout, shared build cache).
- Dev setup: personal laptop with WSL2 (Ubuntu) + Docker Engine inside it, so the Docker runner is built and tested now on the same kind of setup as the office. Office: same project on a Linux machine with `docker`.
- Why: Docker Engine on Linux is free and native (fast; no Windows CRLF test failures). CLI commands are easy to debug: the exact command is in the log and can be pasted into a terminal.
- Constraint: personal laptop = public open-source repos only (D-004).

### D-026 — Sandbox flow: copy into a volume, "prepare" with network, "test" without
- Date: 2026-10-11 · Status: Proposed
- Decision: Per run, `DockerTestRunner` (1) copies the checkout into a fresh Docker volume with `docker cp` (the container never writes to the host checkout); (2) **prepare** container, network on: downloads dependencies and compiles, runs no tests; for Maven it also fetches the surefire test-runner add-ons (junit4, junit47, junit-platform, testng) for the detected surefire version, because Maven only downloads them when tests actually run; (3) **test** container with `--network none`, `--cpus 2 --memory 4g --pids-limit 2048`, 10 min limit, then gathers JUnit XML reports into `/workspace/.ff-reports`; (4) copies only the reports back; (5) removes containers and volume (also on errors/timeouts). Dependencies are cached in shared volumes `failureforge-m2` / `failureforge-gradle`. Image `maven:3.9-eclipse-temurin-21`. Git checkout uses `core.autocrlf=false`. Default sandbox mode is now `docker`; `local` stays as an explicit dev-only option.
- Why: Tests (and from Phase 2, AI-written tests) are the untrusted part, so they get no network. Bind-mounting `/mnt/d` from WSL broke Mockito (Java attach refuses files whose owner looks wrong) and was ~6× slower; a volume is native Linux disk. With this flow the 2 Windows-only CRLF failures disappeared (88/88 pass).
- Trade-off: the prepare step runs the project's build plugins with network on (needed to download dependencies). Later option: allow only the Maven mirror instead of the whole internet. D-009's "read-only cache" is not applied yet (cache is read-write in both steps).

### D-027 — Every run tests parent AND patch; verdict table extended
- Date: 2026-10-11 · Status: Proposed
- Decision: The worker checks out the patch in `work/run-N/patch` and the parent in `work/run-N/parent` (`git worktree`, no extra download), runs the sandbox on both, and `VerdictEngine` (plain code) compares every test by `class#name`. Verdicts: COUNTEREXAMPLE (pass → fail), PRE_EXISTING (fail → fail), FIXED (fail → pass, new), NOTHING_FOUND (pass → pass), NEW_TEST (only in patch, new), REMOVED (only in parent, new), COULDNT_CHECK (skipped, or that side didn't build). Stored in `test_comparison`; counts on `run`. A patch that doesn't build while the parent does is reported, not hidden. Implements D-007 for existing tests now; generated tests (Phase 2) reuse it.
- Why: Proves whether the change caused a failure. Run #7 (change 1236098, a bug fix) showed 88/88 NOTHING_FOUND: the existing tests never noticed the fixed bug, which is the gap generated tests must fill (good pitch point).
- Cost: each run takes about twice as long (~2 min for that repo); dependencies are cached so the parent's prepare step is fast.

### D-028 — Office LLM facts; LlmClient speaks OpenAI-compatible chat completions
- Date: 2026-10-11 · Status: Decided (facts from owner's seniors) / design Proposed
- Facts: the office LLM is **MuseGlimmer 30B** (`openai/muse-glimmer-30b`) on a dedicated office GPU, served as an **OpenAI-compatible** `POST <base>/v1/chat/completions` endpoint. **No API key.** Must be called **without the corporate proxy** (their curl uses `--noproxy "*"`). Their request uses `max_tokens: 2048`, `temperature: 0`, `stream: false`, and an extra field `chat_template_kwargs: {"reasoning_strength": "low"}`.
- **The endpoint URL is internal: it goes only in the git-ignored `server/application-local.yml` on the office laptop, never in this public repo.** Get it from the team chat.
- Design: `LlmClient` targets OpenAI-compatible chat completions, configured by base URL, model, optional API key, `bypass-proxy` flag, and an `extra-body` map (for `chat_template_kwargs`). Dev uses Ollama, which serves the same `/v1/chat/completions` API, so the office switch is config only. Keep each LLM answer well under 2048 tokens. Don't depend on tool calling (unknown support on the office server); ask for JSON in the prompt and validate/retry in code.
- Office step (senior's advice): call the endpoint once with curl on the office laptop and check the response matches what the code expects.
- This refines D-004 (LLM provider now = Ollama for dev, office LLM for the demo; the Claude API is not used, since a Claude.ai subscription doesn't cover API calls).

### D-029 — Demo sample repo with a planted boundary bug
- Date: 2026-10-11 · Status: Decided (owner set up the repos)
- Decision: `failureforge-sample` (github.com/musheer1999/failureforge-sample, imported into GerritHub as `musheer1999/failureforge-sample`): a tiny Maven/JUnit 5 price calculator (`Pricing.finalPrice(pricePaise, discountPercent, member)`). Base commit 0400608 is correct. Gerrit change **1254028** "Members get an extra 5% off (SHOP-42)" adds +5% for members but doesn't cap the total at 100%, so member + coupon ≥ 96% gives a negative price (₹1 item, 96% → −1 paise). Its tests (7) pass on both sides. The ticket text is in `docs/demo/SHOP-42.md` (acceptance criterion 4: price never negative). Local clone: `D:\failureforge-sample`. No jqwik in its pom on purpose: FailureForge must add it itself.
- Baseline (run #8, no AI): 5 NOTHING_FOUND + 2 NEW_TEST, 0 counterexamples, 33 s. **Phase 2 done criterion:** the same change gives a COUNTEREXAMPLE for the "never negative" rule with a shrunk input around discount = 96, member = true.
- Uploading changes: `git push https://musheer1999@review.gerrithub.io/a/musheer1999/failureforge-sample HEAD:refs/for/main` (commit needs a `Change-Id:` line; password = GerritHub HTTP token, entered in the Windows login prompt).

### D-030 — Agent design for Phase 2 (rules → Boundary attack → verdict)
- Date: 2026-10-11 · Status: Proposed
- `LlmClient` is a small hand-written client for OpenAI-compatible chat completions (Spring `RestClient` + JDK HttpClient with optional `NO_PROXY`), not Spring AI: it mirrors the office curl exactly, sends `extra-body` fields as-is, and strips `<think>` blocks. No tool calling; the LLM answers JSON or a ```java block and code validates it. Partly supersedes the "Spring AI ChatClient" part of D-004.
- `RuleExtractor`: one call, ticket text + `git diff` (capped 8k chars) → up to 6 rules as JSON; each `source_quote` must be found in the ticket or diff (normalized, or ≥85% of its words), else DROPPED (D-008). 2 attempts for valid JSON.
- `BoundarySpecialist`: one call per rule with the full patch version of the changed main Java files (≤3 files, 8k chars each) → one jqwik `@Property(tries = 1000)` class named `FailureForge<Rk>Test` in the same package; only valid inputs via `@IntRange`/`@LongRange`; violation → `throw new AssertionError(...)` whose message lists every input. Because jqwik re-runs the shrunk sample last, that message is the minimal counterexample (jqwik's own "Shrunk Sample" report doesn't reach the surefire XML).
- `AttackLoop`: write test into FailureForge's patch copy, add jqwik **first** in the pom (`JqwikSupport`; Maven nearest/first wins, so jqwik's newer JUnit Platform is used; verified with JUnit 5.12), sandbox runs only that class; no test results + non-zero exit = compile error → javac error lines back to the LLM, max 3 repairs (D-010), then COULDNT_CHECK. Then the same test on the parent; `VerdictEngine` decides. If the test doesn't compile on the parent, only the patch counts (new code).
- Phase 2 attacks rules typed "boundary" (max 3 per run); if none are typed boundary, the kept rules are attacked anyway.
- Any agent failure is logged and the run still completes with the existing-test results.
- Ticket input for now: a "Ticket text" box in the portal (`run.ticket_text`); JIRA/Confluence fetching later (office).
- Dev model: `hf.co/Qwen/Qwen3-8B-GGUF:Q4_K_M` (official Qwen build via Hugging Face; Ollama's own registry kept stalling), with `prompt-suffix: " /no_think"` to skip slow thinking.

### D-031 — Phase 2 done criterion met; demo setup
- Date: 2026-10-11 · Status: Decided (verified)
- Result: on Gerrit change 1254028 (SHOP-42) the agent extracts 6 quoted rules; R1 "The final price is never negative" → generated jqwik test passes on the parent, fails on the patch → **COUNTEREXAMPLE, shrunk: `pricePaise=20, discountPercent=100, member=true -> -1`**. Reproduced: run #12 (3 attacks, 7 min 23 s, 4.8k tokens) and run #13 (`FF_MAX_ATTACKS=1`, 3 min 54 s, 2.5k tokens). Wrong generated tests (R2, R3) failed on both sides → PRE_EXISTING, not reported as bugs.
- Fixes found by running it: small models forget `@ForAll` (now added by code), re-implement the logic instead of calling it (now rejected → repair), and use `Long.MAX_VALUE` ranges that overflow on the parent too (prompt asks for realistic ranges); "always/never" rules are attacked first.
- Demo: `docs/DEMO.md` (script, setup, fallback = show run #12). `FF_MAX_ATTACKS=1` for a ~4 min live run.

### D-032 — Compat specialist = japicmp, no LLM
- Date: 2026-10-12 · Status: Proposed
- Decision: Every full sandbox run also copies the compiled main classes back (`.ff-reports/ff-classes`, Maven `target/classes` or Gradle `build/classes/java/main`, root module). After the existing tests, a container runs japicmp 0.26.3 (`--only-incompatible -a public`) on parent vs patch; each `!`-marked line (removed/changed public method, field, class) is a break. Stored as rule C1 + an attack with specialist COMPAT; any break = COUNTEREXAMPLE. LLM rules typed "compat" are not attacked separately.
- Why: API compatibility is decidable by a proven tool; no reason to ask an LLM. Verified: SHOP-45 → `REMOVED METHOD ... long finalPrice(long, int, boolean)`.
- Limit: root module only; buf (gRPC) not wired.

### D-033 — Ingest: JIRA + Confluence via REST, pasted text still first
- Date: 2026-10-12 · Status: Proposed
- Decision: `TicketSource` joins the pasted ticket text, the JIRA issue (`/rest/api/2/issue/KEY?fields=summary,description`, wiki markup → text) and each Confluence page (`/rest/api/content/{id}` or `?spaceKey&title`, storage HTML → text), each capped at 6k chars, and saves the result as `run.ticket_text` so the portal shows exactly what the AI read. Auth optional: `failureforge.atlassian.token` (Data Center PAT → Bearer) or username + token (Cloud → Basic). A source that fails is logged and skipped. Portal "Fetch" button uses `GET /api/ingest?url=`. Verified anonymously on issues.apache.org/jira (MNG-7000) and cwiki.apache.org.

### D-034 — Feedback loop: Accept / Dispute / False Positive
- Date: 2026-10-12 · Status: Proposed
- Decision: `feedback` table (run, rule, repo = Gerrit project, type, text). **Dispute** requires a constraint and queues a re-run (`run.disputed_run_id`, `run.constraints` = earlier constraints + the new one); constraints go into the rule-extraction and test-writer prompts as facts. **False Positive** is remembered per repo: on later runs the rule texts are listed in the prompt and any new rule with ≥80% of the same words is DROPPED by code. **Accept** is recorded (for metrics). Verified on run #17: dispute → re-run #23 queued; empty dispute refused (400).

### D-035 — Gerrit review on request, not automatic
- Date: 2026-10-12 · Status: Proposed
- Decision: "Post to Gerrit" (portal) → `POST /a/changes/{project~n}/revisions/{sha}/review` with a summary (every counterexample: rule, quote, shrunk input, test class, report link) and one unresolved inline comment on the first added line of the changed Java file; tag `autogenerated:failureforge`. Preview via `GET /api/runs/{id}/gerrit-review`. Needs Gerrit HTTP credentials.
- Why: a person decides what gets posted on a colleague's change; avoids noise while the tool is new.

### D-036 — Benchmark: one planted bug per specialist + one correct change
- Date: 2026-10-12 · Status: Decided (verified)
- Decision: `server/src/main/resources/benchmark.json` lists the cases; `POST /api/benchmark` queues one run per case (`run.benchmark_batch/case/expected`); the Benchmark page shows caught/bugs, false positives (counterexamples from an unexpected specialist, or any on the correct change), average time and tokens. Sample repo base `3dc3456` (Pricing, Order, Inventory); changes 1254028 (Boundary), 1254070 (State), 1254071 (Concurrency), 1254072 (Compat), 1254073 (no bug).
- Next: the real benchmark of the design doc (15–20 reverted past bug fixes) on the office laptop.

### D-037 — State + Concurrency specialists, flaky reruns
- Date: 2026-10-12 · Status: Proposed
- Decision: one `TestWriter` with a per-specialist role, technique and example (`Specialist` enum). State: an `enum Op` inside the test and `@ForAll @Size(min=1,max=12) List<Op> ops` (jqwik generates and shrinks the sequence); rejected ops (IllegalState/IllegalArgument) are allowed; rule checked after every op. Concurrency: one `@Example` repeating 200 experiments, 2–8 threads behind a `CountDownLatch` start gate, 10 s timeout, rule checked after each. Rule types are routed by `Specialist.forRuleType`; `pickTargets` attacks one rule per specialist first, then always/never rules. Generated tests run with `-Dsurefire.rerunFailingTestsCount=4` (5 runs, D-010): surefire's `flakyFailure` → status FLAKY → verdict FLAKY (never a finding).
- Verified: SHOP-43 → `ops=[PAY, CANCEL, REFUND, REFUND] -> refunded 2000 > 1000`; SHOP-44 → `threads=6, stock=2 -> reservations=3` (consistent over 5 reruns).

### D-038 — Portal redesign
- Date: 2026-10-12 · Status: Proposed
- Decision: top bar (Runs / Benchmark), pipeline progress steps, findings first (red cards with specialist, rule, quote, shrunk input, test, Accept/Dispute/False positive), other rules grouped (holds / not trusted / not attacked / dropped), Gerrit panel, "every check before vs after" table, log folds away when finished. Specialist identity = validated categorical colors (dataviz palette slots 1–4, light + dark checked with the validator) shown as a dot next to the name, never color alone.

### D-039 — Dev model with an 8k context window; laptop memory
- Date: 2026-10-12 · Status: Proposed
- Finding: Ollama ran Qwen3-8B with its default 40,960-token context (11 GB, only 6/37 layers on the 4 GB GPU). With WSL holding ~5.6 GB, Windows memory commit hit 32.2/32.6 GB and one LLM call took 31 minutes (benchmark SHOP-45).
- Decision: dev model `failureforge-qwen3` = Qwen3-8B with `num_ctx 8192` (`docs/ollama/Modelfile`; create with `ollama create failureforge-qwen3 -f docs/ollama/Modelfile`); it is the default `failureforge.llm.model`. Prompts stay under ~6k tokens (diff and files are capped). Size drops to 6.4 GB.
- Still open on the personal laptop: cap WSL memory with `%UserProfile%\.wslconfig` (`[wsl2]` `memory=4GB`; `[experimental]` `autoMemoryReclaim=gradual`, then `wsl --shutdown`); not applied yet. Not relevant at the office (the LLM runs on the office GPU server).

### D-040 — Everything in the public repo, including the demo config
- Date: 2026-10-12 · Status: Decided (owner)
- Decision: owner asked to publish everything so the project can be downloaded as a zip on the office laptop: `server/application-local.yml` (local Postgres password, WSL docker command) is committed, plus the demo database dump `docs/demo/failureforge-demo.sql`.
- Still kept out on purpose: the office LLM's internal URL (Samsung internal network address; take it from the team chat) and any Gerrit/JIRA token (a public token lets anyone act as the owner).

## 6. Open questions

- [ ] Contest deadline and demo format?
- [ ] Can real Samsung code be sent to the Claude API, or only sample/open-source repos until the office LLM is used?
- [x] Which office LLM API: MuseGlimmer 30B, OpenAI-compatible, no key (D-028). Still unknown: tool calling / JSON mode support; test with curl on the office laptop.
- [ ] Which repositories for the real benchmark (15–20 past Samsung bug fixes)? (POC benchmark on the sample repo: done, D-036)
- [ ] Owners per area (Platform, Agent, Integrations + UI, Quality)?
- [ ] Confirm MVP scope D-011.
- [ ] Office laptop: does the internal Maven mirror carry Spring Boot 4.1, jqwik 1.10.2, japicmp 0.26.3? Which npm registry? Which JDK and Node versions are installed?
- [ ] Does MuseGlimmer follow "answer with only JSON / one java block" reliably? (test on the office laptop)
- [x] Container engine: Docker Engine on Linux, WSL2 on the personal laptop (D-025). Installed and working 2026-10-11.
- [x] Upgrade home Node and move the portal to current Vite (done 2026-10-11, D-024)

## 7. How to continue (office laptop or a new Claude session)

The owner is new to this stack: explain each step in simple words, build and run as you go, add a D-xxx entry
for every decision and keep section 2 current. Office Claude tokens are limited: prefer small, targeted steps.

### 7.1 Get it running on the office laptop (follow `docs/OFFICE_SETUP.md` for details)

1. Download https://github.com/musheer1999/CloudNext/archive/refs/heads/main.zip (or `git clone`) and unzip.
2. Install/check: JDK 21+, Node 22.12+, git, PostgreSQL 16, and Docker Engine on Linux (office: Linux machine,
   or WSL2 + `docker.io`; Docker Desktop / Rancher Desktop are not allowed, D-025).
3. Create the database: `psql -U postgres -c "CREATE DATABASE failureforge"`.
4. Edit `server/application-local.yml` (committed with the home laptop's values, D-040):
   - `spring.datasource.password` → the office Postgres password.
   - `failureforge.sandbox.docker.command` → remove it on a Linux machine (plain `docker`); keep
     `wsl -d Ubuntu -e docker` only on Windows + WSL2.
   - Add the office LLM (URL from the team chat, model `openai/muse-glimmer-30b`, `bypass-proxy: true`,
     `prompt-suffix: ""`, `extra-body.chat_template_kwargs.reasoning_strength: low`); exact block in
     `docs/OFFICE_SETUP.md`. First check the endpoint with the team's curl (`--noproxy "*"`).
   - Maven mirror if Maven Central is blocked (`~/.m2/settings.xml`); the sandbox containers need it too
     (not wired yet: mount settings.xml in `DockerTestRunner.container`).
5. Optional, to show the finished demo results: `psql -U postgres -d failureforge -f docs/demo/failureforge-demo.sql`
   on an **empty** database, **before** the first server start (it contains the schema + Flyway history).
6. Start: `cd server` → `.\mvnw.cmd spring-boot:run` (Linux: `./mvnw spring-boot:run`); `cd portal` →
   `npm install` → `npm run dev`; open http://localhost:5173.
7. Smoke test: Benchmark page shows 4/4 (if the dump was loaded); then start one run, e.g. SHOP-43
   (Gerrit link + ticket text from `server/src/main/resources/benchmark.json`).

### 7.2 What to do next (in priority order)

1. Office LLM: verify the response shape against `LlmClient.chat`; tune if MuseGlimmer formats JSON/code
   differently. It is much stronger than the laptop model, so expect fewer repairs.
2. Post a Gerrit review once on a test change (D-035): needs Gerrit HTTP credentials in `application-local.yml`.
3. Samsung JIRA/Confluence auth (D-033): personal access token; test with the portal's Fetch button.
4. Real benchmark (design doc): 15–20 reverted past Samsung bug fixes covering all four specialists; add them to
   `benchmark.json` (or a second file) and report caught / false positives / time / tokens.
5. Gaps: Maven settings.xml mirror inside the sandbox; multi-module Maven (Compat reads the root module only);
   Gradle repos (code path exists, never tested on a real repo); buf for gRPC protos; timeout path untested.

### 7.3 Where things are

| Path | What |
| --- | --- |
| `server/src/main/java/com/failureforge/worker/RunPipeline.java` | The whole pipeline, start here |
| `server/src/main/java/com/failureforge/agent/` | Rules, specialists (`Specialist`, `TestWriter`), attack loop, compat, jqwik support |
| `server/src/main/java/com/failureforge/sandbox/DockerTestRunner.java` | Docker sandbox (volumes, prepare/test, japicmp) |
| `server/src/main/java/com/failureforge/llm/LlmClient.java` | The only LLM call site |
| `server/src/main/resources/application.yml` | All settings (env-var overridable) |
| `server/src/main/resources/db/migration/` | Database schema (Flyway V1–V5) |
| `server/src/main/resources/benchmark.json` | Benchmark cases with ticket texts |
| `portal/src/` | React portal (`RunView`, `RulesSection`, `BenchmarkView`) |
| `docs/DEMO.md` | Video / live demo script |
| `docs/OFFICE_SETUP.md` | Office laptop setup |
| `docs/demo/` | Demo ticket SHOP-42 and the database dump |
| github.com/musheer1999/failureforge-sample | Demo project; changes 1254028 / 1254070–73 on review.gerrithub.io |

## 8. Conventions for updating this file

- New decision → new `D-xxx` entry at the end of section 5 (never renumber).
- Changed decision → mark the old one **Superseded by D-yyy**; don't delete it.
- Keep "Last updated" and section 2 current.
- Proposed decisions become Decided only when the team confirms.
