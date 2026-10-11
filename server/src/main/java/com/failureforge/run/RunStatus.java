package com.failureforge.run;

public enum RunStatus {
	/** Waiting in the queue for a worker. */
	QUEUED,
	/** A worker has claimed it and is working on it. */
	RUNNING,
	/** The pipeline finished; the tests ran (they may still have failed). */
	SUCCEEDED,
	/** The pipeline itself broke (bad link, clone failed, build tool missing, timeout...). */
	FAILED
}
