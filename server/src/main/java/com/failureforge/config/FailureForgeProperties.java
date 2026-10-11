package com.failureforge.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed view of the {@code failureforge.*} section of application.yml.
 * Spring fills this record at startup, so code never reads raw strings from config.
 */
@ConfigurationProperties(prefix = "failureforge")
public record FailureForgeProperties(Path workDir, Worker worker, Gerrit gerrit, Sandbox sandbox) {

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
