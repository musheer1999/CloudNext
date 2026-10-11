package com.failureforge.sandbox;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.failureforge.config.FailureForgeProperties;
import com.failureforge.process.ProcessRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * DEV ONLY (D-017): runs the repo's tests directly on this machine, with no isolation.
 * The repo's build scripts can do anything your user account can, so only use this with
 * sample/open-source repos you trust. The Docker runner replaces it for real use.
 */
@Component
@ConditionalOnProperty(name = "failureforge.sandbox.mode", havingValue = "local")
public class LocalProcessTestRunner implements TestRunner {

	private static final Logger log = LoggerFactory.getLogger(LocalProcessTestRunner.class);

	private final ProcessRunner processes;
	private final FailureForgeProperties.Sandbox config;

	public LocalProcessTestRunner(ProcessRunner processes, FailureForgeProperties props) {
		this.processes = processes;
		this.config = props.sandbox();
		log.warn("Sandbox mode is LOCAL: tests run on this machine without isolation. Use only trusted sample repos.");
	}

	@Override
	public TestRunResult runTests(Path repoDir, Consumer<String> out) {
		List<String> command = BuildCommand.forRepo(repoDir, config.fallbackMavenWrapper());
		out.accept("[sandbox: local, no isolation] $ " + String.join(" ", command));
		// Use the same JDK that runs FailureForge, so the build doesn't depend on the machine's JAVA_HOME.
		Map<String, String> env = Map.of("JAVA_HOME", System.getProperty("java.home"));
		ProcessRunner.Result result = processes.run(command, repoDir, env,
				Duration.ofMinutes(config.timeoutMinutes()), out);
		return new TestRunResult(result.exitCode(), result.timedOut(), JUnitXmlReports.collect(repoDir));
	}
}
