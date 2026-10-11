package com.failureforge.agent;

/**
 * A rule the change must guarantee, as the LLM proposed it (D-008).
 *
 * @param key         R1, R2, ...
 * @param type        boundary, state, concurrency or compat
 * @param sourceQuote exact words from the ticket or diff that the rule comes from
 * @param kept        false if dropped (see {@code dropReason})
 */
public record Rule(String key, String text, String type, String sourceQuote, String source, String codeLocation,
		boolean kept, String dropReason) {

	public Rule dropped(String reason) {
		return new Rule(key, text, type, sourceQuote, source, codeLocation, false, reason);
	}
}
