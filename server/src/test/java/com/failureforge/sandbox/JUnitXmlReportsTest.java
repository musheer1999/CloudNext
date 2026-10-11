package com.failureforge.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.failureforge.run.TestCaseResult;
import com.failureforge.run.TestCaseResult.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JUnitXmlReportsTest {

	private static final String REPORT = """
			<?xml version="1.0" encoding="UTF-8"?>
			<testsuite name="com.example.CartTest" tests="4" failures="1" errors="1" skipped="1">
			  <testcase name="addsItem" classname="com.example.CartTest" time="0.012"/>
			  <testcase name="rejectsNegative" classname="com.example.CartTest" time="1,250.5">
			    <failure message="expected: 0 but was: -1" type="org.opentest4j.AssertionFailedError">stack...</failure>
			  </testcase>
			  <testcase name="loads" classname="com.example.CartTest" time="0.001">
			    <error type="java.lang.NullPointerException">java.lang.NullPointerException: cart
			    at com.example.Cart.load(Cart.java:10)</error>
			  </testcase>
			  <testcase name="slow" classname="com.example.CartTest" time="0">
			    <skipped message="disabled"/>
			  </testcase>
			</testsuite>
			""";

	@Test
	void readsMavenAndGradleReports(@TempDir Path repo) throws Exception {
		Path maven = Files.createDirectories(repo.resolve("module-a/target/surefire-reports"));
		Files.writeString(maven.resolve("TEST-com.example.CartTest.xml"), REPORT);
		Path gradle = Files.createDirectories(repo.resolve("module-b/build/test-results/test"));
		Files.writeString(gradle.resolve("TEST-com.example.CartTest.xml"), REPORT);
		// Not a test report location: must be ignored.
		Files.writeString(repo.resolve("TEST-stray.xml"), REPORT);

		List<TestCaseResult> results = JUnitXmlReports.collect(repo);

		assertThat(results).hasSize(8);
		assertThat(results.subList(0, 4)).containsExactly(
				new TestCaseResult("com.example.CartTest", "addsItem", Status.PASSED, 12, null),
				new TestCaseResult("com.example.CartTest", "rejectsNegative", Status.FAILED, 1250500,
						"expected: 0 but was: -1"),
				new TestCaseResult("com.example.CartTest", "loads", Status.ERROR, 1,
						"java.lang.NullPointerException: cart"),
				new TestCaseResult("com.example.CartTest", "slow", Status.SKIPPED, 0, "disabled"));
	}

	@Test
	void failedThenPassedOnRerunIsFlaky(@TempDir Path repo) throws Exception {
		Path dir = Files.createDirectories(repo.resolve("target/surefire-reports"));
		Files.writeString(dir.resolve("TEST-x.xml"), """
				<testsuite name="x">
				  <testcase name="race" classname="x.RaceTest" time="0.5">
				    <flakyFailure message="lost update" type="java.lang.AssertionError">trace</flakyFailure>
				  </testcase>
				  <testcase name="always" classname="x.RaceTest" time="0.5">
				    <failure message="boom" type="java.lang.AssertionError">trace</failure>
				    <rerunFailure message="boom" type="java.lang.AssertionError">trace</rerunFailure>
				  </testcase>
				</testsuite>
				""");
		assertThat(JUnitXmlReports.collect(repo)).extracting(TestCaseResult::name, TestCaseResult::status)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("race", Status.FLAKY),
					org.assertj.core.groups.Tuple.tuple("always", Status.FAILED));
	}

	@Test
	void refusesDoctypeToBlockXxe(@TempDir Path repo) throws Exception {
		Path dir = Files.createDirectories(repo.resolve("target/surefire-reports"));
		Files.writeString(dir.resolve("TEST-evil.xml"), """
				<?xml version="1.0"?>
				<!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]>
				<testsuite><testcase name="&e;" classname="x"/></testsuite>
				""");
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> JUnitXmlReports.collect(repo))
			.hasMessageContaining("Unreadable test report");
	}
}
