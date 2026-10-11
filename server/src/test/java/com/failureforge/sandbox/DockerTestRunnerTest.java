package com.failureforge.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import com.failureforge.config.FailureForgeProperties;
import com.failureforge.process.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class DockerTestRunnerTest {

	private static DockerTestRunner runner(String dockerCommand) {
		var docker = new FailureForgeProperties.Docker(dockerCommand, "maven:3.9-eclipse-temurin-21", "2", "4g", 2048);
		var sandbox = new FailureForgeProperties.Sandbox(FailureForgeProperties.SandboxMode.DOCKER, 10, Path.of("mvnw"), docker);
		return new DockerTestRunner(new ProcessRunner(), new FailureForgeProperties(Path.of("work"), null, null, sandbox, null, null));
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void windowsPathsAreTranslatedForDockerInsideWsl() {
		assertThat(runner("wsl -d Ubuntu -e docker").hostPath(Path.of("D:\\CloudNext\\server\\work\\run-6")))
			.isEqualTo("/mnt/d/CloudNext/server/work/run-6");
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void plainDockerKeepsPathsAsTheyAre() {
		assertThat(runner("docker").hostPath(Path.of("D:\\work\\run-6"))).isEqualTo("D:\\work\\run-6");
	}

	@Test
	@EnabledOnOs({ OS.LINUX, OS.MAC })
	void linuxPathsAreUnchanged() {
		assertThat(runner("docker").hostPath(Path.of("/srv/ff/work/run-6"))).isEqualTo("/srv/ff/work/run-6");
	}
}
