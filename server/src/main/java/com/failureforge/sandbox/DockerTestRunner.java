package com.failureforge.sandbox;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.failureforge.config.FailureForgeProperties;
import com.failureforge.process.ProcessRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The real sandbox (D-009, D-025, D-026): runs a repo's tests in throwaway Linux containers
 * using plain {@code docker} commands, each one written to the run log so it can be re-run by hand.
 * <pre>
 * 1. copy     checkout -> a fresh Docker volume (the container never touches the host checkout)
 * 2. prepare  network ON:  download dependencies, compile, no tests run
 * 3. test     network OFF: run the tests, then gather the JUnit XML reports
 * 4. collect  copy only the reports back to the host
 * 5. cleanup  remove the containers and the volume
 * </pre>
 * Downloaded dependencies live in shared volumes (failureforge-m2, failureforge-gradle) so later runs are fast.
 */
@Component
@ConditionalOnProperty(name = "failureforge.sandbox.mode", havingValue = "docker")
public class DockerTestRunner implements TestRunner {

	static final String REPORTS_DIR = ".ff-reports";
	private static final Duration SETUP_TIMEOUT = Duration.ofMinutes(5);
	private static final Pattern SUREFIRE_VERSION = Pattern.compile("surefire:([0-9][\\w.-]*):test");
	/** Test-runner add-ons Maven downloads only when tests actually run; fetched in prepare so the offline step has them. */
	private static final List<String> SUREFIRE_PROVIDERS = List.of("surefire-junit4", "surefire-junit47",
			"surefire-junit-platform", "surefire-testng");

	/** Copies every JUnit report into /workspace/.ff-reports, keeping folder structure. Single quotes only (Windows argv). */
	private static final String GATHER_REPORTS = "mkdir -p /workspace/" + REPORTS_DIR + " && cd /workspace && find . "
			+ "'(' -path '*/surefire-reports/TEST-*.xml' -o -path '*/test-results/*/TEST-*.xml' ')' "
			+ "-not -path './" + REPORTS_DIR + "/*' -exec cp --parents '{}' " + REPORTS_DIR + " ';'";

	private final ProcessRunner processes;
	private final FailureForgeProperties.Sandbox config;
	private final List<String> docker;
	private final boolean wslPaths;

	public DockerTestRunner(ProcessRunner processes, FailureForgeProperties props) {
		this.processes = processes;
		this.config = props.sandbox();
		this.docker = Arrays.asList(config.docker().command().trim().split("\\s+"));
		this.wslPaths = docker.getFirst().toLowerCase().matches("wsl(\\.exe)?");
	}

	@Override
	public TestRunResult runTests(Path repoDir, Consumer<String> out, String testClass) {
		// e.g. work/run-7/patch -> failureforge-run-7-patch (unique per run and side)
		String name = ("failureforge-" + repoDir.getParent().getFileName() + "-" + repoDir.getFileName())
			.toLowerCase().replaceAll("[^a-z0-9_.-]", "-");
		String volume = name + "-ws";
		BuildCommand.Tool tool = BuildCommand.detect(repoDir);
		BuildCommand.Steps steps = BuildCommand.forContainer(repoDir, testClass);
		Duration stepTimeout = Duration.ofMinutes(config.timeoutMinutes());
		// A checkout is tested several times (existing tests, then each attack): never mix in old reports.
		deleteRecursively(repoDir.resolve(REPORTS_DIR));
		try {
			out.accept("[sandbox: docker] 1/4 copying the checkout into volume " + volume);
			mustRun(out, "volume", "create", volume);
			mustRun(out, "create", "--name", name + "-copy", "-v", volume + ":/workspace", config.docker().image());
			mustRun(out, "cp", hostPath(repoDir) + "/.", name + "-copy:/workspace");
			mustRun(out, "rm", "-f", name + "-copy");

			out.accept("[sandbox: docker] 2/4 prepare (network on): download dependencies and compile, no tests");
			StringBuilder surefireVersion = new StringBuilder();
			ProcessRunner.Result prepare = container(name + "-prepare", volume, true, steps.prepare(), stepTimeout,
					line -> {
						out.accept(line);
						Matcher m = SUREFIRE_VERSION.matcher(line);
						if (surefireVersion.isEmpty() && m.find()) {
							surefireVersion.append(m.group(1));
						}
					});
			if (!prepare.ok()) {
				return new TestRunResult(prepare.exitCode(), prepare.timedOut(), List.of());
			}
			if (tool == BuildCommand.Tool.MAVEN && !surefireVersion.isEmpty()) {
				ProcessRunner.Result providers = container(name + "-prepare", volume, true,
						surefireProviders(surefireVersion.toString()), stepTimeout, out);
				if (!providers.ok()) {
					return new TestRunResult(providers.exitCode(), providers.timedOut(), List.of());
				}
			}

			out.accept("[sandbox: docker] 3/4 test (network OFF, " + config.docker().cpus() + " CPUs, "
					+ config.docker().memory() + " memory, " + config.timeoutMinutes() + " min limit)");
			// Keep the build's exit code, but always gather reports (failing tests still write them).
			String script = steps.test() + "; rc=$?; " + GATHER_REPORTS + "; exit $rc";
			ProcessRunner.Result test = container(name + "-test", volume, false, script, stepTimeout, out);

			out.accept("[sandbox: docker] 4/4 copying test reports back");
			Path reports = repoDir.resolve(REPORTS_DIR);
			mustRun(out, "create", "--name", name + "-copy", "-v", volume + ":/workspace", config.docker().image());
			run(out, "cp", name + "-copy:/workspace/" + REPORTS_DIR + "/.", hostPath(reports)); // absent if build died early
			return new TestRunResult(test.exitCode(), test.timedOut(),
					reports.toFile().isDirectory() ? JUnitXmlReports.collect(reports) : List.of());
		}
		finally {
			// Best effort, silent: never leave containers or volumes behind, even after errors or timeouts.
			// (Most are already gone thanks to --rm; Docker's "No such container" replies would only confuse the log.)
			Consumer<String> quiet = line -> { };
			run(quiet, "rm", "-f", name + "-copy", name + "-prepare", name + "-test");
			run(quiet, "volume", "rm", "-f", volume);
			out.accept("[sandbox: docker] cleaned up containers and volume " + volume);
		}
	}

	/** Runs one build step in a fresh container. No network unless {@code network} is true. */
	private ProcessRunner.Result container(String containerName, String volume, boolean network, String script,
			Duration timeout, Consumer<String> out) {
		List<String> args = new ArrayList<>(List.of("run", "--rm", "--name", containerName,
				"--cpus", config.docker().cpus(), "--memory", config.docker().memory(),
				"--pids-limit", String.valueOf(config.docker().pidsLimit())));
		if (!network) {
			args.addAll(List.of("--network", "none"));
		}
		args.addAll(List.of("-v", volume + ":/workspace", "-w", "/workspace",
				"-v", "failureforge-m2:/root/.m2", "-v", "failureforge-gradle:/root/.gradle",
				config.docker().image(), "sh", "-c", script));
		ProcessRunner.Result result = run(out, args.toArray(String[]::new), timeout);
		if (result.timedOut()) {
			// Killing the docker client doesn't stop the container; stop it explicitly.
			run(out, "rm", "-f", containerName);
		}
		return result;
	}

	private static String surefireProviders(String version) {
		return String.join(" && ", SUREFIRE_PROVIDERS.stream()
			.map(p -> "mvn -B -ntp -q dependency:get -Dtransitive=true -Dartifact=org.apache.maven.surefire:" + p + ":" + version)
			.toList());
	}

	private void mustRun(Consumer<String> out, String... args) {
		ProcessRunner.Result result = run(out, args, SETUP_TIMEOUT);
		if (!result.ok()) {
			throw new IllegalStateException("docker " + args[0] + " failed"
					+ (result.timedOut() ? " (timed out)" : " with exit code " + result.exitCode())
					+ ". Is Docker running? Try: " + String.join(" ", docker) + " version");
		}
	}

	private ProcessRunner.Result run(Consumer<String> out, String... args) {
		return run(out, args, SETUP_TIMEOUT);
	}

	private ProcessRunner.Result run(Consumer<String> out, String[] args, Duration timeout) {
		List<String> command = new ArrayList<>(docker);
		command.addAll(List.of(args));
		out.accept("$ " + String.join(" ", command));
		return processes.run(command, Path.of(".").toAbsolutePath(), Map.of(), timeout, out);
	}

	private static void deleteRecursively(Path dir) {
		if (!java.nio.file.Files.exists(dir)) {
			return;
		}
		try (var paths = java.nio.file.Files.walk(dir)) {
			paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		}
		catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	/** Docker inside WSL sees Windows drives under /mnt: {@code D:\a\b} -> {@code /mnt/d/a/b}. */
	String hostPath(Path path) {
		String p = path.toAbsolutePath().normalize().toString();
		if (!wslPaths || p.length() < 2 || p.charAt(1) != ':') {
			return p;
		}
		return "/mnt/" + Character.toLowerCase(p.charAt(0)) + p.substring(2).replace('\\', '/');
	}
}
