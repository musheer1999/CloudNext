package com.failureforge.ticket;

/** Lets tests in other packages reach TicketSource's package-private text helpers. */
public final class TicketSourceTestAccess {

	private TicketSourceTestAccess() {
	}

	public static String jira(String markup) {
		return TicketSource.jiraMarkupToText(markup);
	}

	public static String html(String html) {
		return TicketSource.htmlToText(html);
	}
}
