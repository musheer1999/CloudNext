package com.failureforge.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.failureforge.ticket.TicketSourceTestAccess;
import com.failureforge.worker.RunPipelineTestAccess;
import org.junit.jupiter.api.Test;

class SpecialistsTest {

	private static Rule rule(String key, String type, String text) {
		return new Rule(key, text, type, "q", "ticket", "", true, null);
	}

	@Test
	void rulesGoToTheRightSpecialist() {
		assertThat(Specialist.forRuleType("boundary")).isEqualTo(Specialist.BOUNDARY);
		assertThat(Specialist.forRuleType("State")).isEqualTo(Specialist.STATE);
		assertThat(Specialist.forRuleType("concurrency")).isEqualTo(Specialist.CONCURRENCY);
		assertThat(Specialist.forRuleType("compat")).isEqualTo(Specialist.COMPAT);
		assertThat(Specialist.forRuleType("whatever")).isEqualTo(Specialist.BOUNDARY);
	}

	@Test
	void everySpecialistPromptKeepsTheSharedRules() {
		for (Specialist s : List.of(Specialist.BOUNDARY, Specialist.STATE, Specialist.CONCURRENCY)) {
			String prompt = TestWriter.system(s);
			assertThat(prompt).contains("CALL THE REAL CODE UNDER TEST").contains("AssertionError").contains("```java");
		}
		assertThat(TestWriter.system(Specialist.STATE)).contains("List<Op> ops");
		assertThat(TestWriter.system(Specialist.CONCURRENCY)).contains("CountDownLatch");
	}

	@Test
	void oneRulePerSpecialistFirstThenInvariantsFirst() {
		List<Rule> rules = List.of(
				rule("R1", "boundary", "A member with a 10% coupon gets 15% off"),
				rule("R2", "boundary", "The final price is never negative"),
				rule("R3", "state", "An order can never be refunded twice"),
				rule("R4", "compat", "Old callers keep working"),
				rule("R5", "concurrency", "Stock is never oversold"),
				new Rule("R6", "x", "boundary", "", "", "", false, "dropped"));
		List<Integer> picked = RunPipelineTestAccess.pickTargets(rules, 3);
		assertThat(picked).extracting(i -> rules.get(i).key()).containsExactly("R2", "R3", "R5");
		assertThat(RunPipelineTestAccess.pickTargets(rules, 10)).extracting(i -> rules.get(i).key())
			.containsExactly("R2", "R3", "R5", "R1");
	}

	@Test
	void falsePositiveMemoryMatchesTheSameRuleReworded() {
		List<String> rejected = List.of("The final price is never negative for members");
		assertThat(RuleExtractor.isFalsePositive(rule("R1", "boundary", "Final price never negative"), rejected)).isTrue();
		assertThat(RuleExtractor.isFalsePositive(rule("R2", "state", "An order cannot be refunded twice"), rejected)).isFalse();
	}

	@Test
	void jiraMarkupAndConfluenceHtmlBecomePlainText() {
		assertThat(TicketSourceTestAccess.jira("h2. Acceptance\n* price is [never negative|http://x]\n{code}x{code}"))
			.isEqualTo("Acceptance\n* price is never negative\nx");
		assertThat(TicketSourceTestAccess.html("<h1>Rules</h1><p>Price &amp; tax</p><ul><li>never &lt; 0</li></ul>"))
			.isEqualTo("Rules\nPrice & tax\n- never < 0");
	}
}
