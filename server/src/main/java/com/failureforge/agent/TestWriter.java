package com.failureforge.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.failureforge.llm.LlmClient;
import org.springframework.stereotype.Component;

/**
 * The test writer behind the Boundary, State and Concurrency specialists (design doc sections 3 and 4):
 * writes one jqwik test that attacks a rule, and repairs it from compiler errors. jqwik, not the LLM, picks
 * the actual input values (or operation sequences) and shrinks any failure (D-006): the LLM only says
 * what to check and where.
 */
@Component
public class TestWriter {

	/** Rules every specialist's test follows; the specialist adds its role, technique and an example. */
	static String system(Specialist s) {
		return s.role + """
				Strict requirements:
				1. Output only Java code, in one ```java block.
				2. Use exactly the package and class name you are given. The class is package-private (no "public").
				3. Only these imports: net.jqwik.api.*, net.jqwik.api.constraints.*, java.util.* (plus what the
				   technique below allows). No JUnit, no AssertJ, no Mockito.
				4. CALL THE REAL CODE UNDER TEST and check what it does. Never copy or re-implement its logic
				   in the test: a test that does its own calculation proves nothing.
				5. """ + s.technique + """

				6. Call only public or package-private code. No reflection.
				7. Check the rule with an if statement and throw new AssertionError(message) when it is broken.
				   The message MUST contain every input value and the result.
				8. Do not catch exceptions from the code under test, except where the technique above says so.

				""" + s.example + "\n";
	}

	private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+[\\w.]+\\s*;");
	private static final Pattern FIRST_CLASS = Pattern.compile("(?m)^(\\s*(?:public\\s+|final\\s+)*class\\s+)(\\w+)");
	/** {@code @Property(...)} then the method signature up to the opening '(' of its parameters. */
	private static final Pattern PROPERTY_METHOD = Pattern.compile(
			"@Property\\b(?:\\s*\\([^)]*\\))?[\\s\\S]*?\\b\\w+\\s*\\(");

	private final LlmClient llm;

	public TestWriter(LlmClient llm) {
		this.llm = llm;
	}

	public record Draft(String code, int tokens, long seconds) {
	}

	/** @param constraints developer constraints from disputes (D-034), may be empty */
	public Draft write(Specialist specialist, Rule rule, ChangeContext change, String constraints, String packageName,
			String className) {
		StringBuilder user = new StringBuilder("RULE ").append(rule.key()).append(": ").append(rule.text())
			.append("\n(comes from: \"").append(rule.sourceQuote()).append("\")\n\nCODE UNDER TEST:\n");
		for (ChangeContext.SourceFile f : change.changedFiles()) {
			user.append("--- ").append(f.path()).append(" ---\n```java\n").append(f.content()).append("\n```\n");
		}
		user.append("\nWrite the test class ").append(className).append(" in package ").append(packageName)
			.append(" that tries to break rule ").append(rule.key()).append('.');
		if (!constraints.isBlank()) {
			user.append("\n\nThe developers say these are ALWAYS true; do not generate cases that break them:\n")
				.append(constraints);
		}
		return ask(specialist, user.toString(), packageName, className);
	}

	public Draft repair(Specialist specialist, String previousCode, String compilerErrors, String packageName,
			String className) {
		String user = "This test class did not compile:\n```java\n" + previousCode + "\n```\n\nCompiler errors:\n"
				+ compilerErrors + "\n\nReturn the full corrected class " + className + " in package " + packageName
				+ ", following all the requirements.";
		return ask(specialist, user, packageName, className);
	}

	private Draft ask(Specialist specialist, String user, String packageName, String className) {
		LlmClient.Answer answer = llm.chat(system(specialist), user);
		return new Draft(normalize(LlmJson.javaCode(answer.text()), packageName, className), answer.totalTokens(),
				answer.took().toSeconds());
	}

	/**
	 * Small models often forget {@code @ForAll}; without it jqwik can't generate the parameter
	 * ("cannot be resolved"). Adds it to every parameter of every {@code @Property} method that lacks it.
	 */
	static String addMissingForAll(String code) {
		StringBuilder out = new StringBuilder();
		int from = 0;
		Matcher m = PROPERTY_METHOD.matcher(code);
		while (m.find()) {
			int open = m.end() - 1; // the '(' of the parameter list
			int close = matchingParen(code, open);
			if (close < 0) {
				break;
			}
			out.append(code, from, open + 1);
			List<String> params = splitTopLevel(code.substring(open + 1, close));
			for (int i = 0; i < params.size(); i++) {
				String p = params.get(i);
				String fixed = p.isBlank() || p.contains("@ForAll") ? p
						: p.replaceFirst("^(\\s*)", "$1@ForAll ");
				out.append(fixed).append(i < params.size() - 1 ? "," : "");
			}
			out.append(')');
			from = close + 1;
		}
		return out.append(code.substring(from)).toString();
	}

	/** True if the test mentions one of the changed classes (it must call the real code, not copy it). */
	static boolean callsCodeUnderTest(String code, ChangeContext change) {
		return change.changedFiles().stream()
			.map(f -> f.path().substring(f.path().lastIndexOf('/') + 1).replace(".java", ""))
			.anyMatch(name -> Pattern.compile("\\b" + Pattern.quote(name) + "\\s*[.(]|new\\s+" + Pattern.quote(name) + "\\b")
				.matcher(code).find());
	}

	private static int matchingParen(String s, int open) {
		int depth = 0;
		for (int i = open; i < s.length(); i++) {
			if (s.charAt(i) == '(') {
				depth++;
			}
			else if (s.charAt(i) == ')' && --depth == 0) {
				return i;
			}
		}
		return -1;
	}

	/** Splits on commas that aren't inside parentheses/generics, e.g. inside @IntRange(min = 0, max = 100). */
	private static List<String> splitTopLevel(String s) {
		List<String> parts = new ArrayList<>();
		int depth = 0;
		int start = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '(' || c == '<') {
				depth++;
			}
			else if (c == ')' || c == '>') {
				depth--;
			}
			else if (c == ',' && depth == 0) {
				parts.add(s.substring(start, i));
				start = i + 1;
			}
		}
		parts.add(s.substring(start));
		return parts;
	}

	/** Forces the package and class name we asked for, so the file name and the class always match. */
	static String normalize(String code, String packageName, String className) {
		String fixed = PACKAGE.matcher(code).find()
				? PACKAGE.matcher(code).replaceFirst("package " + packageName + ";")
				: "package " + packageName + ";\n\n" + code;
		Matcher m = FIRST_CLASS.matcher(fixed);
		if (m.find()) {
			fixed = fixed.substring(0, m.start()) + m.group(1).replace("public ", "") + className + fixed.substring(m.end());
		}
		return addMissingForAll(fixed).strip() + "\n";
	}
}
