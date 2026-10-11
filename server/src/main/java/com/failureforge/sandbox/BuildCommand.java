package com.failureforge.sandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.failureforge.process.ProcessRunner;

/**
 * Works out which build tool a repo uses and the command that runs its tests.
 * Tests that fail must not stop the build, so we can collect every result:
 * Maven {@code -Dmaven.test.failure.ignore=true}, Gradle {@code --continue}.
 */
public final class BuildCommand {

	private BuildCommand() {
	}

	public enum Tool {
		MAVEN, GRADLE
	}

	public static Tool detect(Path repo) {
		if (Files.exists(repo.resolve("pom.xml"))) {
			return Tool.MAVEN;
		}
		if (Files.exists(repo.resolve("build.gradle")) || Files.exists(repo.resolve("build.gradle.kts"))) {
			if (!Files.exists(repo.resolve("gradlew"))) {
				throw new IllegalStateException("Gradle project without a Gradle wrapper (gradlew) is not supported");
			}
			return Tool.GRADLE;
		}
		throw new IllegalStateException("No pom.xml or build.gradle(.kts) at the repository root; can't tell how to run its tests");
	}

	/** Command for running the tests directly on this machine (local sandbox mode). */
	public static List<String> forRepo(Path repo, Path fallbackMavenWrapper) {
		if (detect(repo) == Tool.MAVEN) {
			Path wrapper = repo.resolve(ProcessRunner.WINDOWS ? "mvnw.cmd" : "mvnw");
			List<String> cmd = new ArrayList<>();
			if (Files.exists(wrapper)) {
				cmd.addAll(script(wrapper));
			}
			else {
				// No wrapper in the repo: use FailureForge's own (it downloads Maven by itself).
				cmd.addAll(script(fallbackScript(fallbackMavenWrapper)));
				cmd.addAll(List.of("-f", repo.resolve("pom.xml").toString()));
			}
			cmd.addAll(List.of("-B", "-ntp", "test", "-Dmaven.test.failure.ignore=true"));
			return cmd;
		}
		Path wrapper = repo.resolve(ProcessRunner.WINDOWS ? "gradlew.bat" : "gradlew");
		List<String> cmd = new ArrayList<>(script(wrapper));
		cmd.addAll(List.of("test", "--continue", "--console=plain"));
		return cmd;
	}

	/**
	 * Shell commands run inside the Linux sandbox container, in two steps (D-026):
	 * {@code prepare} downloads dependencies and compiles (network on, no tests),
	 * {@code test} runs the tests offline (network off).
	 */
	public static Steps forContainer(Path repo, String testClass) {
		if (testClass != null && !testClass.matches("[\\w.$]+")) {
			throw new IllegalArgumentException("bad test class name: " + testClass);
		}
		if (detect(repo) == Tool.MAVEN) {
			// The repo's own wrapper pins its Maven version; otherwise the image's mvn.
			String mvn = Files.exists(repo.resolve("mvnw")) ? "sh ./mvnw" : "mvn";
			// For generated tests: re-run failures up to 4 more times (5 runs in all, D-010); surefire marks
			// "failed, then passed" as flaky in the report.
			String only = testClass == null ? "" : " -Dtest=" + testClass + " -Dsurefire.failIfNoSpecifiedTests=false"
					+ " -Dsurefire.rerunFailingTestsCount=4";
			return new Steps(
					// -Dtest=none with failIfNoSpecifiedTests=false resolves the test runner plugins
					// without running any test, so the offline step finds everything it needs.
					mvn + " -B -ntp test -Dtest=FailureForgeNoSuchTest -Dsurefire.failIfNoSpecifiedTests=false",
					mvn + " -B -ntp -o test -Dmaven.test.failure.ignore=true" + only);
		}
		String only = testClass == null ? "" : " --tests " + testClass;
		return new Steps(
				"sh ./gradlew testClasses --console=plain",
				"sh ./gradlew test --offline --continue --console=plain" + only);
	}

	public record Steps(String prepare, String test) {
	}

	private static Path fallbackScript(Path configured) {
		Path script = configured.toAbsolutePath().normalize();
		if (ProcessRunner.WINDOWS && !script.toString().endsWith(".cmd")) {
			script = Path.of(script + ".cmd");
		}
		if (!Files.exists(script)) {
			throw new IllegalStateException("Fallback Maven wrapper not found at " + script
					+ " (set failureforge.sandbox.fallback-maven-wrapper)");
		}
		return script;
	}

	/** .cmd/.bat files need cmd.exe on Windows; on Linux/macOS run the script with sh. */
	private static List<String> script(Path path) {
		if (ProcessRunner.WINDOWS) {
			return List.of("cmd.exe", "/c", path.toAbsolutePath().toString());
		}
		return List.of("sh", path.toAbsolutePath().toString());
	}
}
