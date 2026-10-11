# FailureForge

Adversarial verification agent for Gerrit changes. Project context and decisions: [CLAUDE.md](CLAUDE.md).
Design: [docs/DEVELOPMENT_GUIDE.md](docs/DEVELOPMENT_GUIDE.md).

## Layout

| Folder | What |
| --- | --- |
| `server/` | Spring Boot 4.1 app (Java 21, Maven): REST API + SSE + background worker |
| `portal/` | React + TypeScript portal (Vite) |

## Prerequisites

- JDK 21 or newer (`java -version`). Maven is **not** needed: `server/mvnw` downloads it.
- PostgreSQL 16 running locally, with a database named `failureforge`:
  `psql -U postgres -c "CREATE DATABASE failureforge"`
- Node.js 22.12 or newer (24 LTS recommended) and git.
- Docker Engine on Linux (tests run in containers). On Windows: WSL2 + Ubuntu with Docker inside it:
  `wsl --install -d Ubuntu`, then in Ubuntu `sudo apt install -y docker.io && sudo usermod -aG docker $USER`,
  then `wsl --shutdown` from PowerShell. Check with `docker run hello-world` in Ubuntu.

## Run it (development)

1. **Secrets.** Create `server/application-local.yml` (git-ignored):

   ```yaml
   spring:
     datasource:
       password: "<your postgres password>"
   failureforge:
     sandbox:
       docker:
         command: "wsl -d Ubuntu -e docker"   # Windows + WSL2 only; leave out on Linux
   #   gerrit:   # only for private Gerrit servers (Gerrit "HTTP password" from Settings > HTTP Credentials)
   #     username: "<user>"
   #     password: "<http password>"
   ```

2. **Server** (port 8090). Flyway creates the tables on first start:

   ```
   cd server
   mvnw.cmd spring-boot:run        (Windows)
   ./mvnw spring-boot:run          (Linux/macOS)
   ```

3. **Portal** (port 5173, forwards `/api` to the server):

   ```
   cd portal
   npm install
   npm run dev
   ```

4. Open http://localhost:5173 and submit a Gerrit change link, for example
   `https://review.gerrithub.io/c/amarula/chatgpt-code-review-gerrit-plugin/+/1236098`.

Run the server's unit tests with `mvnw.cmd test`.

## Office laptop notes

- **Maven mirror / proxy.** If the company blocks Maven Central, put the internal mirror in
  `%USERPROFILE%\.m2\settings.xml` (`<mirrors>`). `mvnw` itself downloads Maven from the URL in
  `server/.mvn/wrapper/maven-wrapper.properties`; point that at the mirror too if needed.
- **npm registry.** `npm config set registry <internal registry URL>` if npmjs.org is blocked.
- **Sandbox.** Tests run in Docker by default (D-026): a "prepare" container downloads dependencies
  (needs the Maven mirror), then a "test" container runs the tests with no network. For the mirror, the
  container needs a `settings.xml` too (not wired up yet). `failureforge.sandbox.mode=local` runs tests
  directly on the machine with no isolation: dev only, trusted sample repos only.
- **Confidential code.** Never with the external Claude API until the D-004 approval is in place.

## Useful settings (`server/src/main/resources/application.yml`, or environment variables)

| Setting | Env var | Default |
| --- | --- | --- |
| DB URL / user / password | `DB_URL`, `DB_USER`, `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/failureforge`, `postgres`, (empty) |
| Server port | `PORT` | `8090` |
| Run checkouts folder | `FF_WORK_DIR` | `./work` (one `run-<id>` folder per run; safe to delete) |
| Sandbox mode | `FF_SANDBOX_MODE` | `docker` (or `local`, dev only) |
| How to call docker | `FF_DOCKER_COMMAND` | `docker` (Windows + WSL2: `wsl -d Ubuntu -e docker`) |
