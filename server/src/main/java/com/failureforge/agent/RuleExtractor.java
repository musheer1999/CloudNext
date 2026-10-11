package com.failureforge.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import com.failureforge.llm.LlmClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Step 1 of the agent: turn the ticket + diff into testable rules (design doc section 2).
 * The LLM proposes; this code checks that every rule quotes text that really exists (D-008).
 */
@Component
public class RuleExtractor {

	private static final int ATTEMPTS = 2;

	static final String SYSTEM = """
			You are the rule extractor of FailureForge, a tool that tries to break code changes.
			From a ticket and a code change, list the rules the changed code must ALWAYS guarantee.
			Each rule must be checkable by calling the changed public Java code with different inputs.
			Prefer rules about limits and edge values (types: "boundary"). Other types: "state", "concurrency", "compat".
			Each rule MUST copy, word for word, the sentence or phrase it comes from into "source_quote".
			Answer with ONLY a JSON object, no other text, in exactly this form:
			{"rules":[{"id":"R1","rule":"...","type":"boundary","source_quote":"...","source":"ticket","code_location":"Class.method"}]}
			""";

	private final LlmClient llm;
	private final JsonMapper json;

	public RuleExtractor(LlmClient llm, JsonMapper json) {
		this.llm = llm;
		this.json = json;
	}

	public record Result(List<Rule> rules, int tokens) {
	}

	public Result extract(String ticket, ChangeContext change, int maxRules, Consumer<String> out) {
		String user = "TICKET:\n" + (ticket == null || ticket.isBlank() ? "(no ticket given)" : ticket.strip())
				+ "\n\nCODE CHANGE (git diff):\n" + change.diff()
				+ "\nList at most " + maxRules + " rules.";
		int tokens = 0;
		RuntimeException last = null;
		for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
			out.accept("[agent] asking " + llm.model() + " for rules (attempt " + attempt + "/" + ATTEMPTS + ")...");
			LlmClient.Answer answer = llm.chat(SYSTEM, user);
			tokens += answer.totalTokens();
			out.accept("[agent] answer after " + answer.took().toSeconds() + " s, " + answer.totalTokens() + " tokens");
			try {
				List<Rule> rules = parse(answer.text(), ticket, change.diff(), maxRules);
				return new Result(rules, tokens);
			}
			catch (RuntimeException e) {
				last = e;
				out.accept("[agent] answer was not valid rules JSON (" + e.getMessage() + "); "
						+ (attempt < ATTEMPTS ? "asking again" : "giving up"));
			}
		}
		throw new IllegalStateException("The LLM did not return valid rules: " + last.getMessage(), last);
	}

	List<Rule> parse(String answer, String ticket, String diff, int maxRules) {
		JsonNode rules = LlmJson.object(json, answer).path("rules");
		if (!rules.isArray() || rules.isEmpty()) {
			throw new IllegalArgumentException("no \"rules\" array");
		}
		List<Rule> result = new ArrayList<>();
		for (JsonNode r : rules) {
			if (result.size() >= maxRules) {
				break;
			}
			Rule rule = new Rule(
					r.path("id").asString("R" + (result.size() + 1)),
					r.path("rule").asString("").strip(),
					r.path("type").asString("boundary").strip().toLowerCase(Locale.ROOT),
					r.path("source_quote").asString("").strip(),
					r.path("source").asString("").strip(),
					r.path("code_location").asString("").strip(),
					true, null);
			result.add(check(rule, ticket, diff));
		}
		return result;
	}

	/** D-008: a rule survives only if its quote can be found in the ticket or the diff. */
	static Rule check(Rule rule, String ticket, String diff) {
		if (rule.text().isEmpty()) {
			return rule.dropped("empty rule");
		}
		if (rule.sourceQuote().isEmpty()) {
			return rule.dropped("no source quote");
		}
		String haystack = normalize((ticket == null ? "" : ticket) + "\n" + diff);
		String quote = normalize(rule.sourceQuote());
		if (haystack.contains(quote)) {
			return rule;
		}
		// Small models often trim or re-join words; accept if ~all meaningful words of the quote are there.
		List<String> words = Arrays.stream(quote.split(" ")).filter(w -> w.length() >= 3).toList();
		long found = words.stream().filter(haystack::contains).count();
		if (!words.isEmpty() && found >= Math.ceil(words.size() * 0.85)) {
			return rule;
		}
		return rule.dropped("source quote not found in the ticket or the diff");
	}

	private static String normalize(String s) {
		return s.toLowerCase(Locale.ROOT)
			.replaceAll("[*_`#>\"']", " ")
			.replaceAll("^[+-]", " ")
			.replaceAll("\\s+", " ")
			.strip();
	}
}
