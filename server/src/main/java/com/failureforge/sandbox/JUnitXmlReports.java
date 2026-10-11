package com.failureforge.sandbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import com.failureforge.run.TestCaseResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Reads the JUnit XML files that Maven (target/surefire-reports) and Gradle (build/test-results)
 * write after running tests. Both use the same {@code TEST-*.xml} format.
 */
public final class JUnitXmlReports {

	private static final int MAX_MESSAGE = 2000;

	private JUnitXmlReports() {
	}

	public static List<TestCaseResult> collect(Path repo) {
		try (Stream<Path> files = Files.walk(repo)) {
			List<Path> reports = files
				.filter(p -> p.getFileName().toString().startsWith("TEST-") && p.toString().endsWith(".xml"))
				.filter(p -> p.toString().contains("surefire-reports") || p.toString().contains("test-results"))
				.sorted()
				.toList();
			List<TestCaseResult> results = new ArrayList<>();
			for (Path report : reports) {
				results.addAll(parse(report));
			}
			return results;
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	static List<TestCaseResult> parse(Path report) {
		Document doc;
		try {
			doc = newBuilder().parse(report.toFile());
		}
		catch (Exception e) {
			throw new IllegalStateException("Unreadable test report " + report + ": " + e.getMessage(), e);
		}
		List<TestCaseResult> results = new ArrayList<>();
		NodeList cases = doc.getElementsByTagName("testcase");
		for (int i = 0; i < cases.getLength(); i++) {
			Element tc = (Element) cases.item(i);
			TestCaseResult.Status status = TestCaseResult.Status.PASSED;
			String message = null;
			Element problem;
			if ((problem = child(tc, "error")) != null) {
				status = TestCaseResult.Status.ERROR;
				message = message(problem);
			}
			else if ((problem = child(tc, "failure")) != null) {
				status = TestCaseResult.Status.FAILED;
				message = message(problem);
			}
			else if ((problem = child(tc, "skipped")) != null) {
				status = TestCaseResult.Status.SKIPPED;
				message = blankToNull(problem.getAttribute("message"));
			}
			results.add(new TestCaseResult(tc.getAttribute("classname"), tc.getAttribute("name"), status,
					seconds(tc.getAttribute("time")), message));
		}
		return results;
	}

	private static DocumentBuilder newBuilder() throws Exception {
		// Report files come from untrusted repos: switch off DTDs/external entities (XXE protection).
		DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
		f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
		f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
		f.setExpandEntityReferences(false);
		DocumentBuilder builder = f.newDocumentBuilder();
		builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler()); // report errors via exceptions, not stderr
		return builder;
	}

	private static Element child(Element parent, String name) {
		for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
			if (n instanceof Element e && e.getTagName().equals(name)) {
				return e;
			}
		}
		return null;
	}

	/** The message attribute, or else the first line of the stack trace. */
	private static String message(Element problem) {
		String msg = blankToNull(problem.getAttribute("message"));
		if (msg == null) {
			String text = problem.getTextContent().strip();
			msg = text.isEmpty() ? problem.getAttribute("type") : text.lines().findFirst().orElse("");
		}
		return msg.length() > MAX_MESSAGE ? msg.substring(0, MAX_MESSAGE) + "..." : msg;
	}

	private static long seconds(String time) {
		try {
			return Math.round(Double.parseDouble(time.replace(",", "")) * 1000);
		}
		catch (NumberFormatException e) {
			return 0;
		}
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s;
	}
}
