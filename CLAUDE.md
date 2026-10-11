# FailureForge — Project Context & Decision Log

> Hand this file to any Claude session (office or personal) at the start of work.
> It is the single source of truth for what we are building, what is decided, and where we are.
> Update it every time a decision is made or a phase moves.

Last updated: 2026-10-11 (Phase 1 done: Docker sandbox, D-026)
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
| Phase 2 — rules + Boundary specialist | Not started. Next. |
| Code repository | Local git repo at `D:\CloudNext` (`server/`, `portal/`, `docs/`); not committed or pushed yet |
| LLM in use | Claude API (see D-004); no LLM calls yet (Phase 1 has no AI) |
| Dev machine (personal laptop) | JDK 23, Node 24.21, PostgreSQL 16 (Windows service; may need a manual start), git, WSL2 Ubuntu + Docker Engine 29.1 (called as `wsl -d Ubuntu -e docker`). Port 8080 taken by Apache httpd. |

## 3. Architecture (summary)

```
Portal (React) ──► Spring Boot API ──► PostgreSQL
                        │ queued run
                        ▼
Orchestrator worker (Spring AI + LLM behind LlmClient interface)
  Ingest (Gerrit diff, JIRA, wiki, existing tests)
   → Rule extraction (JSON, each rule cites its source)
   → Specialists: Boundary | State | Concurrency | Compat
   → Test writer (compile, repair up to 3 times)
   → Docker sandbox (run on parent commit AND on patch)
   → Verdict engine (plain code, no AI)
   → Gerrit comment + portal report
  Dispute → back to rule extraction with developer's constraint
```

Verdict table (verdict engine, no AI):

| Parent | Patch | Verdict |
| --- | --- | --- |
| Pass | Fail | Counterexample found |
| Fail | Fail | Pre-existing bug or wrong test (low confidence) |
| Pass | Pass | Nothing found |
| No compile | No compile | Couldn't check |

## 4. Tech stack

| Layer | Choice |
| --- | --- |
| Portal | React 19 + TypeScript, Vite 8 (D-024) |
| API | Spring Boot 4.1, Java 21, Maven wrapper, SSE for live progress (D-014, D-015, D-016) |
| LLM framework | Spring AI 2.0 (D-014) |
| LLM (now) | Claude API via Spring AI Anthropic starter |
| LLM (later) | Samsung office LLM API, swapped via config |
| DB + queue | PostgreSQL 16, plain SQL via JdbcClient, Flyway migrations, `SKIP LOCKED` queue (D-019) |
| Code parsing | JavaParser |
| Sandbox | Docker Engine on Linux (WSL2 for dev), `docker` CLI commands; no network, CPU/mem limits, timeout, shared build cache (D-025) |
| Test libraries | JUnit 5, jqwik (boundary + stateful), Lincheck, jcstress |
| Compat checks | japicmp (Java API), buf breaking (gRPC protos) |
| Integrations | Gerrit, JIRA, Confluence REST APIs |

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

## 6. Open questions

- [ ] Contest deadline and demo format?
- [ ] Can real Samsung code be sent to the Claude API, or only sample/open-source repos until the office LLM is used?
- [ ] Which office LLM API, and does it support tool calling + structured JSON output?
- [ ] Which repositories for the benchmark?
- [ ] Owners per area (Platform, Agent, Integrations + UI, Quality)?
- [ ] Confirm MVP scope D-011.
- [ ] Office laptop: does the internal Maven mirror carry Spring Boot 4.1 / Spring AI 2.0? Which npm registry? Which JDK and Node versions are installed?
- [x] Container engine: Docker Engine on Linux, WSL2 on the personal laptop (D-025). Installed and working 2026-10-11.
- [x] Upgrade home Node and move the portal to current Vite (done 2026-10-11, D-024)

## 7. How to continue in another Claude session

1. Give Claude this file first and say: "This is my project context. Read it before helping. Add a new D-xxx entry for every decision we make and update Current status."
2. In Claude Code, you can copy this file into the repo root as `CLAUDE.md`; Claude Code reads that automatically at the start of each session.
3. After each session, download the updated file and keep it in the repo (commit it), so the repo always carries its own history.

## 8. Conventions for updating this file

- New decision → new `D-xxx` entry at the end of section 5 (never renumber).
- Changed decision → mark the old one **Superseded by D-yyy**; don't delete it.
- Keep "Last updated" and section 2 current.
- Proposed decisions become Decided only when the team confirms.
