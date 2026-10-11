package com.failureforge.agent;

import java.util.Locale;

/**
 * The four specialists (design doc section 3). Boundary, State and Concurrency get the LLM to write a
 * jqwik test; Compat needs no LLM at all: japicmp compares the parent and patch APIs ({@link CompatCheck}).
 * Each LLM specialist is the same test writer with a different job description and example.
 */
public enum Specialist {

	BOUNDARY("""
			You are the Boundary specialist of FailureForge. You write ONE jqwik property-based test class
			that tries to break a rule by calling the code under test with limits and edge values.
			""", """
			Write @Property(tries = 1000) methods. EVERY parameter has @ForAll, plus a range that keeps it to
			   VALID inputs the method accepts (read its checks and comments): @IntRange(min = .., max = ..),
			   @LongRange(min = .., max = ..). Ranges include the limits themselves (e.g. 0 and 100).
			   Keep amounts realistic: at most 10_000_000 unless the code says otherwise. Never Long.MAX_VALUE.""", """
			Example (for a different class, Stock.remove(int quantity) which returns the units left):
			```java
			package com.acme.store;

			import net.jqwik.api.*;
			import net.jqwik.api.constraints.*;

			class FailureForgeR1Test {

				@Property(tries = 1000)
				void stockNeverGoesBelowZero(@ForAll @IntRange(min = 0, max = 1000) int initial,
						@ForAll @IntRange(min = 0, max = 1000) int quantity) {
					Assume.that(quantity <= initial);
					Stock stock = new Stock(initial);
					int left = stock.remove(quantity);
					if (left < 0) {
						throw new AssertionError("initial=" + initial + ", quantity=" + quantity + " -> left=" + left);
					}
				}
			}
			```"""),

	STATE("""
			You are the State specialist of FailureForge. You write ONE jqwik property-based test class that
			tries to break a rule by calling the code's operations in unexpected ORDERS: repeated calls, calls
			after cancel or close, double submit, refund before pay.
			""", """
			Define an enum Op inside the test class with one value per operation of the code under test.
			   Write one @Property(tries = 500) method whose parameter is
			   @ForAll @Size(min = 1, max = 12) List<Op> ops (jqwik generates and shrinks the sequence).
			   Apply the ops in order to ONE fresh object. If the code rejects an op by throwing
			   IllegalStateException or IllegalArgumentException, that is allowed: catch it and continue.
			   After EVERY op, check the rule. The message must contain the ops list and the state.""", """
			Example (for a different class, Ticket with book(), cancel(), checkIn(); a cancelled ticket must
			never be checked in):
			```java
			package com.acme.travel;

			import java.util.*;
			import net.jqwik.api.*;
			import net.jqwik.api.constraints.*;

			class FailureForgeR1Test {

				enum Op { BOOK, CANCEL, CHECK_IN }

				@Property(tries = 500)
				void cancelledTicketIsNeverCheckedIn(@ForAll @Size(min = 1, max = 12) List<Op> ops) {
					Ticket ticket = new Ticket();
					boolean cancelled = false;
					for (Op op : ops) {
						try {
							switch (op) {
								case BOOK -> ticket.book();
								case CANCEL -> { ticket.cancel(); cancelled = true; }
								case CHECK_IN -> ticket.checkIn();
							}
						} catch (IllegalStateException | IllegalArgumentException rejected) {
							// the code refused this op: allowed
						}
						if (cancelled && ticket.isCheckedIn()) {
							throw new AssertionError("ops=" + ops + " -> checked in after cancel");
						}
					}
				}
			}
			```"""),

	CONCURRENCY("""
			You are the Concurrency specialist of FailureForge. You write ONE test class that tries to break a
			rule by calling the code under test from several threads AT THE SAME MOMENT (races, lost updates).
			""", """
			Write one @Example method (from net.jqwik.api) that repeats an experiment 200 times. Each experiment:
			   create ONE fresh shared object, start 2 to 8 threads that all wait on a CountDownLatch start gate,
			   release the gate so they call the code at the same moment, wait for all threads (at most 10 s),
			   then check the rule. Also allowed: java.util.concurrent.* and java.util.concurrent.atomic.*.
			   The message must contain the experiment number, the thread count and the values seen.""", """
			Example (for a different class, Seats with reserve() that returns true if a seat was reserved; with
			N seats, at most N reservations may succeed):
			```java
			package com.acme.cinema;

			import java.util.*;
			import java.util.concurrent.*;
			import java.util.concurrent.atomic.*;
			import net.jqwik.api.*;

			class FailureForgeR1Test {

				@Example
				void neverMoreReservationsThanSeats() throws Exception {
					for (int experiment = 1; experiment <= 200; experiment++) {
						int seats = 1;
						int threads = 8;
						Seats shared = new Seats(seats);
						CountDownLatch gate = new CountDownLatch(1);
						AtomicInteger successes = new AtomicInteger();
						ExecutorService pool = Executors.newFixedThreadPool(threads);
						for (int t = 0; t < threads; t++) {
							pool.submit(() -> {
								gate.await();
								if (shared.reserve()) {
									successes.incrementAndGet();
								}
								return null;
							});
						}
						gate.countDown();
						pool.shutdown();
						pool.awaitTermination(10, TimeUnit.SECONDS);
						if (successes.get() > seats) {
							throw new AssertionError("experiment=" + experiment + ", threads=" + threads
									+ ", seats=" + seats + " -> reservations=" + successes.get());
						}
					}
				}
			}
			```"""),

	/** No LLM test: japicmp compares the compiled parent and patch (see {@link CompatCheck}). */
	COMPAT("", "", "");

	final String role;
	final String technique;
	final String example;

	Specialist(String role, String technique, String example) {
		this.role = role;
		this.technique = technique;
		this.example = example;
	}

	/** Which specialist attacks a rule of this type; unknown types go to Boundary. */
	public static Specialist forRuleType(String type) {
		String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
		if (t.contains("concurren") || t.contains("thread") || t.contains("race")) {
			return CONCURRENCY;
		}
		if (t.contains("state") || t.contains("sequence") || t.contains("order")) {
			return STATE;
		}
		if (t.contains("compat") || t.contains("api")) {
			return COMPAT;
		}
		return BOUNDARY;
	}

	/** Display name, e.g. "Boundary". */
	public String label() {
		return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
	}
}
