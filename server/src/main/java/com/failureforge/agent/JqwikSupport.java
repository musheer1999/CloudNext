package com.failureforge.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Makes jqwik available to generated tests in FailureForge's own checkout (never the developer's repo).
 * jqwik goes FIRST among the project's dependencies: with Maven's "first declaration wins", that pulls in
 * jqwik's newer JUnit Platform, which older JUnit 5 tests run on fine (checked on the sample: JUnit 5.12).
 */
public final class JqwikSupport {

	private JqwikSupport() {
	}

	/** @return true if the pom was changed */
	public static boolean addToMaven(Path repo, String version) {
		Path pom = repo.resolve("pom.xml");
		try {
			String xml = Files.readString(pom);
			String updated = withJqwik(xml, version);
			if (updated.equals(xml)) {
				return false;
			}
			Files.writeString(pom, updated);
			return true;
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	static String withJqwik(String xml, String version) {
		if (xml.contains("<artifactId>jqwik</artifactId>")) {
			return xml; // the project already uses jqwik
		}
		String dependency = "\n\t\t<dependency><groupId>net.jqwik</groupId><artifactId>jqwik</artifactId>"
				+ "<version>" + version + "</version><scope>test</scope></dependency>";
		int at = projectDependencies(xml);
		if (at >= 0) {
			int insert = at + "<dependencies>".length();
			return xml.substring(0, insert) + dependency + xml.substring(insert);
		}
		int end = xml.lastIndexOf("</project>");
		if (end < 0) {
			throw new IllegalStateException("pom.xml has no </project>");
		}
		return xml.substring(0, end) + "\t<dependencies>" + dependency + "\n\t</dependencies>\n" + xml.substring(end);
	}

	/** Index of the project's own {@code <dependencies>}: not inside dependencyManagement, a plugin or a profile. */
	private static int projectDependencies(String xml) {
		int from = 0;
		while (true) {
			int i = xml.indexOf("<dependencies>", from);
			if (i < 0) {
				return -1;
			}
			String before = xml.substring(0, i);
			if (open(before, "dependencyManagement") && open(before, "plugin") && open(before, "profile")) {
				return i;
			}
			from = i + 1;
		}
	}

	/** True if every {@code <tag>} before this point is closed again. */
	private static boolean open(String before, String tag) {
		return count(before, "<" + tag + ">") == count(before, "</" + tag + ">");
	}

	private static int count(String s, String part) {
		int n = 0;
		for (int i = s.indexOf(part); i >= 0; i = s.indexOf(part, i + 1)) {
			n++;
		}
		return n;
	}
}
