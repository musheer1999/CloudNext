package com.failureforge.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AgentPartsTest {

	private static final String TICKET = """
			**Acceptance criteria:**
			4. The final price is never negative: the total discount can never be more than 100%.
			""";

	@Test
	void ruleWithRealQuoteIsKeptAndInventedQuoteIsDropped() {
		RuleExtractor extractor = new RuleExtractor(null, JsonMapper.builder().build());
		String answer = """
				Here are the rules:
				```json
				{"rules":[
				 {"id":"R1","rule":"finalPrice never returns a negative value","type":"Boundary",
				  "source_quote":"The final price is never negative","source":"ticket","code_location":"Pricing.finalPrice"},
				 {"id":"R2","rule":"prices are cached","type":"state",
				  "source_quote":"prices must be cached for 5 minutes","source":"ticket","code_location":"Pricing"}
				]}
				```""";
		List<Rule> rules = extractor.parse(answer, TICKET, "+ int effectivePercent = ...", 6);
		assertThat(rules).extracting(Rule::key, Rule::kept, Rule::type)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("R1", true, "boundary"),
					org.assertj.core.groups.Tuple.tuple("R2", false, "state"));
		assertThat(rules.get(1).dropReason()).contains("not found");
	}

	@Test
	void javaStyleStringConcatenationInJsonIsRepaired() {
		var node = LlmJson.object(JsonMapper.builder().build(),
				"{\"rules\":[{\"id\":\"R1\",\"source_quote\":\"int x = a\" + \" + 5;\"}]}");
		assertThat(node.path("rules").path(0).path("source_quote").asString()).isEqualTo("int x = a + 5;");
	}

	@Test
	void quoteCheckToleratesMarkdownAndSmallRewording() {
		Rule rule = new Rule("R1", "x", "boundary", "the total discount can never be more than 100%", "ticket", "", true, null);
		assertThat(RuleExtractor.check(rule, TICKET, "").kept()).isTrue();
		Rule noQuote = new Rule("R2", "x", "boundary", "", "ticket", "", true, null);
		assertThat(RuleExtractor.check(noQuote, TICKET, "").kept()).isFalse();
	}

	@Test
	void jqwikGoesFirstInTheProjectDependenciesNotInPluginsOrManagement() {
		String pom = """
				<project>
				  <dependencyManagement><dependencies><dependency>x</dependency></dependencies></dependencyManagement>
				  <dependencies>
				    <dependency><artifactId>junit-jupiter</artifactId></dependency>
				  </dependencies>
				  <build><plugins><plugin><dependencies></dependencies></plugin></plugins></build>
				</project>""";
		String updated = JqwikSupport.withJqwik(pom, "1.10.2");
		int jqwik = updated.indexOf("<artifactId>jqwik</artifactId>");
		assertThat(jqwik).isGreaterThan(updated.indexOf("</dependencyManagement>"));
		assertThat(jqwik).isLessThan(updated.indexOf("junit-jupiter"));
		assertThat(JqwikSupport.withJqwik(updated, "1.10.2")).isEqualTo(updated); // only once
	}

	@Test
	void pomWithoutDependenciesGetsTheBlock() {
		assertThat(JqwikSupport.withJqwik("<project>\n</project>", "1.10.2"))
			.contains("<dependencies>").contains("<artifactId>jqwik</artifactId>").endsWith("</project>");
	}

	@Test
	void generatedCodeGetsOurPackageAndClassName() {
		String code = LlmJson.javaCode("Sure!\n```java\npackage wrong;\nimport net.jqwik.api.*;\npublic class Whatever {\n}\n```\nDone.");
		String fixed = TestWriter.normalize(code, "com.example.shop", "FailureForgeR1Test");
		assertThat(fixed).startsWith("package com.example.shop;").contains("class FailureForgeR1Test").doesNotContain("public class");
		assertThat(TestWriter.normalize("class A {}", "p", "B")).isEqualTo("package p;\n\nclass B {}\n");
	}

	@Test
	void missingForAllIsAddedToEveryPropertyParameter() {
		String code = """
				class T {
					@Property(tries = 1000)
					void p(@LongRange(min = 0, max = 10) long price,
					       @IntRange(min = 0, max = 100) int discount,
					       @ForAll boolean member) {
					}
					void helper(int x) {}
				}""";
		String fixed = TestWriter.addMissingForAll(code);
		assertThat(fixed).contains("@ForAll @LongRange(min = 0, max = 10) long price")
			.contains("@ForAll @IntRange(min = 0, max = 100) int discount")
			.contains("@ForAll boolean member")
			.doesNotContain("@ForAll @ForAll")
			.contains("void helper(int x)");
	}

	@Test
	void testMustCallTheChangedClass() {
		var change = new ChangeContext("", List.of(new ChangeContext.SourceFile("src/main/java/a/Pricing.java", "")));
		assertThat(TestWriter.callsCodeUnderTest("long r = Pricing.finalPrice(1, 2, true);", change)).isTrue();
		assertThat(TestWriter.callsCodeUnderTest("long r = p * 3 / 100; // like Pricing does", change)).isFalse();
	}

	@Test
	void neverRulesAreAttackedFirst() {
		Rule example = new Rule("R2", "A member with a 10% coupon gets 15% off", "boundary", "", "", "", true, null);
		Rule invariant = new Rule("R1", "The final price is never negative", "boundary", "", "", "", true, null);
		assertThat(AttackLoop.invariantScore(invariant)).isGreaterThan(AttackLoop.invariantScore(example));
	}

	@Test
	void compilerErrorsAreTheJavacLinesOnly() {
		List<String> log = List.of("[INFO] Scanning", "[ERROR] /workspace/src/test/java/A.java:[5,9] cannot find symbol",
				"[ERROR]   symbol:   method foo()", "[ERROR] Failed to execute goal ...", "symbol:   method foo()");
		assertThat(AttackLoop.compilerErrors(log)).isEqualTo("src/test/java/A.java:[5,9] cannot find symbol\nsymbol:   method foo()");
	}

	@Test
	void sourceFileKnowsItsPackageAndTestFolder() {
		var f = new ChangeContext.SourceFile("src/main/java/com/example/shop/Pricing.java", "");
		assertThat(f.packageName()).isEqualTo("com.example.shop");
		assertThat(f.testDir()).isEqualTo("src/test/java/com/example/shop");
		assertThat(new ChangeContext.SourceFile("core/src/main/java/a/B.java", "").testDir()).isEqualTo("core/src/test/java/a");
	}
}
