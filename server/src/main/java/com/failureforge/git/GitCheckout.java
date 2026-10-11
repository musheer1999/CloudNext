package com.failureforge.git;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import com.failureforge.gerrit.GerritChange;
import com.failureforge.process.ProcessRunner;
import org.springframework.stereotype.Component;

/**
 * Checks out one Gerrit patchset using the git command line (D-020).
 * <p>
 * Fetches only the patchset ref with {@code --depth 2}: the patch commit plus its parent,
 * which Phase 2 needs for the parent-vs-patch runs (D-007). Much faster than a full clone.
 */
@Component
public class GitCheckout {

	private static final Duration TIMEOUT = Duration.ofMinutes(10);
	private static final int FETCH_ATTEMPTS = 3;

	private final ProcessRunner processes;

	public GitCheckout(ProcessRunner processes) {
		this.processes = processes;
	}

	public void checkout(GerritChange change, Path dir, Optional<String> authHeader, Consumer<String> log) {
		dir.toFile().mkdirs();
		git(dir, Optional.empty(), log, "init", "-q", ".");
		fetchWithRetry(change, dir, authHeader, log);
		git(dir, Optional.empty(), log, "checkout", "-q", "--detach", "FETCH_HEAD");
	}

	/** Fetch is the only network step; servers occasionally drop a request, so try up to 3 times. */
	private void fetchWithRetry(GerritChange change, Path dir, Optional<String> authHeader, Consumer<String> log) {
		for (int attempt = 1;; attempt++) {
			try {
				git(dir, authHeader, log, "fetch", "--depth", "2", "--no-tags", change.fetchUrl(), change.ref());
				return;
			}
			catch (IllegalStateException e) {
				if (attempt == FETCH_ATTEMPTS) {
					throw e;
				}
				log.accept(e.getMessage() + "; retrying (" + (attempt + 1) + "/" + FETCH_ATTEMPTS + ")");
				sleep(Duration.ofSeconds(5L * attempt));
			}
		}
	}

	private static void sleep(Duration d) {
		try {
			Thread.sleep(d);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted", e);
		}
	}

	private void git(Path dir, Optional<String> authHeader, Consumer<String> log, String... args) {
		// core.autocrlf=false: keep files byte-for-byte as in the repo. Git for Windows otherwise turns
		// LF into CRLF, which breaks tests in the Linux sandbox.
		List<String> command = new ArrayList<>(List.of("git", "-c", "core.longpaths=true", "-c", "core.autocrlf=false",
				"-c", "advice.detachedHead=false"));
		authHeader.ifPresent(h -> command.addAll(List.of("-c", "http.extraHeader=Authorization: " + h)));
		command.addAll(List.of(args));

		log.accept("$ git " + String.join(" ", args)); // never log the auth header
		ProcessRunner.Result result = processes.run(command, dir, Map.of("GIT_TERMINAL_PROMPT", "0"), TIMEOUT, log);
		if (!result.ok()) {
			throw new IllegalStateException("git " + args[0] + (result.timedOut() ? " timed out" : " failed with exit code " + result.exitCode()));
		}
	}
}
