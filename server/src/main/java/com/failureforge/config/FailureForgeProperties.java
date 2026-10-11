package com.failureforge.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed view of the {@code failureforge.*} section of application.yml.
 * Spring fills this record at startup, so code never reads raw strings from config.
 */
@ConfigurationProperties(prefix = "failureforge")
public record FailureForgeProperties(Path workDir, Worker worker, Gerrit gerrit, Sandbox sandbox, Llm llm,
		Agent agent, Atlassian atlassian) {

	/**
	 * JIRA + Confluence login, both optional (public servers need none).
	 * Data Center personal access token: only {@code token}. Cloud: {@code username} (email) + API {@code token}.
	 */
	public record Atlassian(String username, String token) {
	}

	/**
	 * Any OpenAI-compatible chat-completions server (D-028): Ollama for dev, the office LLM for the demo.
	 *
	 * @param baseUrl        up to and including {@code /v1}, e.g. {@code http://localhost:11434/v1}
	 * @param apiKey         sent as a Bearer token if set; the office LLM needs none
	 * @param bypassProxy    connect directly, ignoring any JVM proxy settings (office: like {@code curl --noproxy "*"})
	 * @param promptSuffix   appended to every user message (e.g. {@code /no_think} for Qwen3 models on Ollama)
	 * @param extraBody      extra JSON fields sent as-is, e.g. {@code chat_template_kwargs} for the office model
	 */
	public record Llm(String baseUrl, String model, String apiKey, boolean bypassProxy, int maxTokens,
			double temperature, int timeoutSeconds, String promptSuffix, java.util.Map<String, Object> extraBody) {
	}

	/**
	 * @param maxRules        rules kept per run (the rest are ignored)
	 * @param maxAttacks      rules attacked per run
	 * @param maxRepairs      compile-repair attempts per generated test (D-010)
	 * @param jqwikVersion    jqwik added to the repo's test dependencies when it has none
	 */
	public record Agent(boolean enabled, int maxRules, int maxAttacks, int maxRepairs, String jqwikVersion) {
	}

	public record Worker(long pollIntervalMs) {
	}

	public record Gerrit(String username, String password) {

		public boolean hasCredentials() {
			return username != null && !username.isBlank();
		}
	}

	public record Sandbox(SandboxMode mode, int timeoutMinutes, Path fallbackMavenWrapper, Docker docker) {
	}

	/**
	 * @param command   how to call docker, split on spaces: {@code docker} on Linux,
	 *                  {@code wsl -d Ubuntu -e docker} on a Windows dev PC (D-025)
	 * @param image     container image with a JDK + Maven
	 * @param cpus      CPU limit per container, e.g. {@code 2}
	 * @param memory    memory limit per container, e.g. {@code 4g}
	 * @param pidsLimit max processes per container (stops fork bombs)
	 */
	public record Docker(String command, String image, String cpus, String memory, int pidsLimit) {
	}

	public enum SandboxMode {
		LOCAL, DOCKER
	}
}
