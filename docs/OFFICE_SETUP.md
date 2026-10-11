# Office laptop setup checklist

For the office Claude session: follow this list in order, it is all that should be needed.
Background: CLAUDE.md (D-025 sandbox on Linux, D-028 office LLM, D-030 agent design).

## 1. Get the code and tools

- `git clone https://github.com/musheer1999/CloudNext.git`
- JDK 21+, Node 22.12+, git, PostgreSQL 16 (database `failureforge`), Docker Engine on a Linux machine
  (or WSL2 Ubuntu + `docker.io`, see README).
- Maven mirror: if Maven Central is blocked, add the company mirror to `~/.m2/settings.xml`. The sandbox
  containers need it too (not wired up yet: mount `~/.m2/settings.xml` into the containers in
  `DockerTestRunner`, see D-026).

## 2. Check the office LLM by hand first

Use the curl from the team chat (the URL is internal, so it is not in this public repo). It must be
called without the proxy: `curl --noproxy "*" ...`. Check that the answer looks like:

```json
{"choices":[{"message":{"role":"assistant","content":"..."}}],"usage":{"total_tokens":123}}
```

If the shape differs, adapt `LlmClient.chat` (one method) to the real response.

## 3. Point FailureForge at it

Create `server/application-local.yml` (git-ignored, never commit it):

```yaml
spring:
  datasource:
    password: "<postgres password>"
failureforge:
  llm:
    base-url: "http://<office-llm-host>/<workspace-path>/v1"   # everything before /chat/completions
    model: "openai/muse-glimmer-30b"
    bypass-proxy: true
    prompt-suffix: ""                                          # the /no_think trick is for Qwen on Ollama only
    extra-body:
      chat_template_kwargs:
        reasoning_strength: "low"
  # On a Linux machine, docker is called directly (no "wsl ..." prefix), so leave sandbox.docker.command out.
  gerrit:                       # for the Samsung Gerrit (HTTP password from Gerrit Settings > HTTP Credentials)
    username: "<user>"
    password: "<http password>"
```

## 4. Run and verify

```
cd server && ./mvnw spring-boot:run
cd portal && npm install && npm run dev
```

Open http://localhost:5173, submit a change link + ticket text, and watch the log: the
`[agent]` lines show the rules, the generated test, compile attempts and the verdict.

Do **not** send confidential Samsung source code anywhere outside the office LLM (D-004).
