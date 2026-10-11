package com.failureforge.agent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.failureforge.llm.LlmClient;
import org.springframework.stereotype.Component;

/**
 * The Boundary specialist + test writer (design doc sections 3 and 4): writes one jqwik property test
 * that attacks a rule at its limits, and repairs it from compiler errors. jqwik, not the LLM, picks
 * the actual input values and shrinks any failure (D-006): the LLM only says what to check and where.
 */
@Component
public class BoundarySpecialist {

	static final String SYSTEM = """
			You are the Boundary specialist of FailureForge. You write ONE jqwik property-based test class
			that tries to break a rule by testing limits and edge values of the code under test.
			Strict requirements:
			1. Output only Java code, in one ```java block.
			2. Use exactly the package and class name you are given. The class is package-private (no "public").
			3. Only these imports: net.jqwik.api.*, net.jqwik.api.constraints.*, java.util.*, and the code under test.
			   No JUnit, no AssertJ, no Mockito.
			4. Write @Property(tries = 1000) methods with @ForAll parameters. Constrain every parameter to the VALID
			   inputs the method accepts (read its checks and its comments) using @IntRange(min = .., max = ..),
			   @LongRange(min = .., max = ..) and similar. The range must include the limits themselves.
			   Never generate inputs the code is documented to reject.
			5. Call only public or package-private code. No reflection.
			6. Check the rule with an if statement and throw new AssertionError(message) when it is broken.
			   The message MUST contain every input value and the result, for example:
			   "price=" + price + ", discount=" + discount + ", member=" + member + " -> result=" + result
			7. Do not catch exceptions from the code under test.
			""";

	private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+[\\w.]+\\s*;");
	private static final Pattern FIRST_CLASS = Pattern.compile("(?m)^(\\s*(?:public\\s+|final\\s+)*class\\s+)(\\w+)");

	private final LlmClient llm;

	public BoundarySpecialist(LlmClient llm) {
		this.llm = llm;
	}

	public record Draft(String code, int tokens, long seconds) {
	}

	public Draft write(Rule rule, ChangeContext change, String packageName, String className) {
		StringBuilder user = new StringBuilder("RULE ").append(rule.key()).append(": ").append(rule.text())
			.append("\n(comes from: \"").append(rule.sourceQuote()).append("\")\n\nCODE UNDER TEST:\n");
		for (ChangeContext.SourceFile f : change.changedFiles()) {
			user.append("--- ").append(f.path()).append(" ---\n```java\n").append(f.content()).append("\n```\n");
		}
		user.append("\nWrite the test class ").append(className).append(" in package ").append(packageName)
			.append(" that tries to break rule ").append(rule.key()).append('.');
		return ask(user.toString(), packageName, className);
	}

	public Draft repair(String previousCode, String compilerErrors, String packageName, String className) {
		String user = "This test class did not compile:\n```java\n" + previousCode + "\n```\n\nCompiler errors:\n"
				+ compilerErrors + "\n\nReturn the full corrected class " + className + " in package " + packageName
				+ ", following all the requirements.";
		return ask(user, packageName, className);
	}

	private Draft ask(String user, String packageName, String className) {
		LlmClient.Answer answer = llm.chat(SYSTEM, user);
		return new Draft(normalize(LlmJson.javaCode(answer.text()), packageName, className), answer.totalTokens(),
				answer.took().toSeconds());
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
		return fixed.strip() + "\n";
	}
}
