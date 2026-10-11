package com.failureforge.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.failureforge.process.ProcessRunner;

/**
 * What the LLM is shown about a change (design doc "scope reduction"): the diff and the full
 * patch version of the changed main (non-test) Java files. Sizes are capped so prompts stay small:
 * small local models have small context windows, and smaller context means fewer hallucinations.
 *
 * @param diff         {@code git diff parent..patch}, possibly cut
 * @param changedFiles path (relative to the repo) to full content of changed main Java files
 */
public record ChangeContext(String diff, List<SourceFile> changedFiles) {

	private static final int MAX_DIFF_CHARS = 8_000;
	private static final int MAX_FILE_CHARS = 8_000;
	private static final int MAX_FILES = 3;

	public record SourceFile(String path, String content) {

		/** {@code src/main/java/com/example/shop/Pricing.java} -> {@code com.example.shop} */
		public String packageName() {
			int i = path.indexOf("/java/");
			String rel = i >= 0 ? path.substring(i + 6) : path;
			int slash = rel.lastIndexOf('/');
			return slash < 0 ? "" : rel.substring(0, slash).replace('/', '.');
		}

		/** Folder for a test in the same package: {@code src/test/java/com/example/shop}. */
		public String testDir() {
			int i = path.indexOf("/main/java/");
			String moduleRoot = i >= 0 ? path.substring(0, i) : "src";
			return moduleRoot + "/test/java/" + packageName().replace('.', '/');
		}
	}

	public static ChangeContext read(ProcessRunner processes, Path patchDir, String parentSha) {
		String diff = git(processes, patchDir, "diff", "--no-color", "-U5", parentSha, "HEAD");
		List<SourceFile> files = new ArrayList<>();
		for (String path : git(processes, patchDir, "diff", "--name-only", "--diff-filter=AM", parentSha, "HEAD").split("\n")) {
			path = path.strip();
			if (path.endsWith(".java") && path.contains("src/main/java/") && files.size() < MAX_FILES) {
				try {
					files.add(new SourceFile(path, cut(Files.readString(patchDir.resolve(path)), MAX_FILE_CHARS)));
				}
				catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}
		}
		return new ChangeContext(cut(diff, MAX_DIFF_CHARS), files);
	}

	private static String git(ProcessRunner processes, Path dir, String... args) {
		List<String> command = new ArrayList<>(List.of("git", "-c", "core.autocrlf=false"));
		command.addAll(List.of(args));
		StringBuilder out = new StringBuilder();
		ProcessRunner.Result result = processes.run(command, dir, Map.of(), Duration.ofMinutes(1),
				line -> out.append(line).append('\n'));
		if (!result.ok()) {
			throw new IllegalStateException("git " + args[0] + " failed: " + out);
		}
		return out.toString();
	}

	private static String cut(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max) + "\n... (cut, " + (s.length() - max) + " more characters)\n";
	}
}
