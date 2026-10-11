# FailureForge

Tries to break a Gerrit code change before production does. It reads the change plus its ticket, works out the
rules the change must guarantee, has four specialists attack those rules with real tests in a sealed Docker
sandbox, and runs every check on the code **before and after** the change. Plain code, not the AI, decides
whether the change broke something.

Project context and decisions: [CLAUDE.md](CLAUDE.md) · Design: [docs/DEVELOPMENT_GUIDE.md](docs/DEVELOPMENT_GUIDE.md) ·
Demo script: [docs/DEMO.md](docs/DEMO.md) · Office laptop: [docs/OFFICE_SETUP.md](docs/OFFICE_SETUP.md)

## What it does

| Step | How |
| --- | --- |
| Ingest | Gerrit change (REST), checkout of patch + parent, ticket text, JIRA issue and Confluence pages (REST) |
| Rules | The LLM lists rules; each must quote the ticket or diff, or it is dropped |
| Boundary specialist | LLM writes a jqwik property test over edge values; jqwik generates and shrinks inputs |
| State specialist | LLM writes a jqwik test over random operation sequences; jqwik shrinks to the shortest failing sequence |
| Concurrency specialist | LLM writes a test that calls the code from many threads at the same moment, 200 times |
| Compat specialist | japicmp compares the compiled public API of parent and patch (no LLM) |
| Repair loop | Compiler errors go back to the LLM, at most 3 times |
| Sandbox | One Linux container per step: dependencies with network, tests with `--network none`, CPU/memory/process limits |
| Verdict | Plain code: passes before + fails after = counterexample; fails on both = not trusted; different on re-runs = flaky |
| Report | Portal (live), Gerrit review comment on request, Accept / Dispute / False Positive |
| Benchmark | Planted-bug changes, one per specialist, plus a correct change: bugs caught, false positives, time, tokens |

## Layout

| Folder | What |
| --- | --- |
| `server/` | Spring Boot 4.1 app (Java 21, Maven): REST API + SSE + worker + agent |
| `portal/` | React + TypeScript portal (Vite 8) |
| `docs/` | Design, decisions, demo script, demo tickets |

The demo project with planted bugs is a separate repo: https://github.com/musheer1999/failureforge-sample
(changes on https://review.gerrithub.io).

## Prerequisites

- JDK 21 or newer (`java -version`). Maven is **not** needed: `server/mvnw` downloads it.
- PostgreSQL 16 running locally, with a database named `failureforge`:
  `psql -U postgres -c "CREATE DATABASE failureforge"`
- Node.js 22.12 or newer (24 LTS recommended) and git.
- Docker Engine on Linux (tests run in containers). On Windows: WSL2 + Ubuntu with Docker inside it:
  `wsl --install -d Ubuntu`, then in Ubuntu `sudo apt install -y docker.io && sudo usermod -aG docker $USER`,
  then `wsl --shutdown` from PowerShell. Check with `docker run hello-world` in Ubuntu.
- An LLM with an OpenAI-compatible API. For development: [Ollama](https://ollama.com) with
  `ollama pull hf.co/Qwen/Qwen3-8B-GGUF:Q4_K_M` then `ollama create failureforge-qwen3 -f docs/ollama/Modelfile`
  (free, runs on the laptop; 8k context to save memory). Office: see docs/OFFICE_SETUP.md.

## Run it (development)

1. **Local settings.** Create `server/application-local.yml` (git-ignored, never commit it):

   ```yaml
   spring:
     datasource:
       password: "<your postgres password>"
   failureforge:
     sandbox:
       docker:
         command: "wsl -d Ubuntu -e docker"   # Windows + WSL2 only; leave out on Linux
     gerrit:                                  # private Gerrit servers, and to post review comments
       username: "<user>"
       password: "<Gerrit HTTP password / token>"
     # atlassian:                             # private JIRA/Confluence only
     #   token: "<personal access token>"
   ```

2. **Server** (port 8090). Flyway creates the tables on first start:

   ```
   cd server
   .\mvnw.cmd spring-boot:run      (Windows PowerShell)
   ./mvnw spring-boot:run          (Linux/macOS)
   ```

3. **Portal** (port 5173, forwards `/api` to the server):

   ```
   cd portal
   npm install
   npm run dev
   ```

4. Open http://localhost:5173, paste a Gerrit change link and its ticket text (or a JIRA link), Start run.
   Or open **Benchmark** and run all planted-bug cases.

Unit tests: `cd server` then `.\mvnw.cmd test` · portal checks: `cd portal` then `npm run build` and `npm run lint`.

## Useful settings (`server/src/main/resources/application.yml`, or environment variables)

| Setting | Env var | Default |
| --- | --- | --- |
| DB URL / user / password | `DB_URL`, `DB_USER`, `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/failureforge`, `postgres`, (empty) |
| Server port | `PORT` | `8090` |
| LLM base URL / model | `FF_LLM_BASE_URL`, `FF_LLM_MODEL` | Ollama on localhost, `failureforge-qwen3` |
| Rules attacked per run | `FF_MAX_ATTACKS` | `3` (each takes ~1–2 min with the laptop model) |
| Agent on/off | `FF_AGENT_ENABLED` | `true` (false = existing tests only) |
| Sandbox mode | `FF_SANDBOX_MODE` | `docker` (or `local`, dev only, no isolation) |
| How to call docker | `FF_DOCKER_COMMAND` | `docker` (Windows + WSL2: `wsl -d Ubuntu -e docker`) |
| Run checkouts folder | `FF_WORK_DIR` | `./work` (one `run-<id>` folder per run; safe to delete) |
