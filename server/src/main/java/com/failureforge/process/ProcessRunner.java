package com.failureforge.process;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

/**
 * Runs an external program (git, mvn, gradle), streaming each output line to a callback.
 * stderr is merged into stdout so the log reads in the order things happened.
 */
@Component
public class ProcessRunner {

	public static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().startsWith("windows");

	public record Result(int exitCode, boolean timedOut) {

		public boolean ok() {
			return !timedOut && exitCode == 0;
		}
	}

	public Result run(List<String> command, Path workDir, Map<String, String> env, Duration timeout,
			Consumer<String> onLine) {
		ProcessBuilder pb = new ProcessBuilder(command).directory(workDir.toFile()).redirectErrorStream(true);
		pb.environment().putAll(env);
		Process process;
		try {
			process = pb.start();
		}
		catch (IOException e) {
			throw new UncheckedIOException("Could not start '" + command.getFirst() + "': " + e.getMessage(), e);
		}
		try {
			process.getOutputStream().close(); // no input: a tool waiting for a keypress fails fast instead of hanging
		}
		catch (IOException ignored) {
			// Already closed.
		}

		// Read output on a separate thread so waitFor(timeout) below isn't blocked by a silent process.
		Thread reader = Thread.ofVirtual().start(() -> {
			try (BufferedReader r = new BufferedReader(
					new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) {
					onLine.accept(line);
				}
			}
			catch (IOException ignored) {
				// Stream closes when the process is killed.
			}
		});

		try {
			boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
			if (!finished) {
				killTree(process);
				reader.join(Duration.ofSeconds(5));
				return new Result(-1, true);
			}
			reader.join(Duration.ofSeconds(30));
			return new Result(process.exitValue(), false);
		}
		catch (InterruptedException e) {
			killTree(process);
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while running " + command.getFirst(), e);
		}
	}

	/** Maven/Gradle start child JVMs; kill those too, not just the parent. */
	private static void killTree(Process process) {
		process.descendants().forEach(ProcessHandle::destroyForcibly);
		process.destroyForcibly();
	}
}
