import { useCallback, useEffect, useRef, useState } from 'react'
import {
  getRun,
  isFinished,
  postGerritReview,
  previewGerritReview,
  watchRun,
  type Comparison,
  type Feedback,
  type LogEvent,
  type Run,
  type RuleView,
  type TestCase,
  type TestStatus,
} from './api'
import { PipelineSteps } from './PipelineSteps'
import { RulesSection } from './RulesSection'
import { StatusBadge } from './StatusBadge'
import { VERDICT_LABEL } from './verdicts'

export function RunView({ id, onChange }: { id: number; onChange: () => void }) {
  const [run, setRun] = useState<Run | null>(null)
  const [tests, setTests] = useState<TestCase[]>([])
  const [comparisons, setComparisons] = useState<Comparison[]>([])
  const [rules, setRules] = useState<RuleView[]>([])
  const [feedback, setFeedback] = useState<Feedback[]>([])
  const [log, setLog] = useState<LogEvent[]>([])
  const [error, setError] = useState<string | null>(null)

  const reload = useCallback(
    () =>
      getRun(id).then((d) => {
        setRun(d.run)
        setTests(d.tests)
        setComparisons(d.comparisons)
        setRules(d.rules)
        setFeedback(d.feedback)
      }),
    [id],
  )

  useEffect(() => {
    let cancelled = false
    reload().catch((e: Error) => !cancelled && setError(e.message))
    const stop = watchRun(
      id,
      // The server may send a line twice around reconnects; keep each id once.
      (e) => setLog((prev) => (prev.length && prev[prev.length - 1].id >= e.id ? prev : [...prev, e])),
      (r) => {
        setRun(r)
        onChange()
        // Rules and attacks arrive while the run is still going: refresh on every status change.
        if (!cancelled) reload().catch(() => {})
      },
    )
    return () => {
      cancelled = true
      stop()
    }
  }, [id, onChange, reload])

  if (error) return <p className="error">Run #{id}: {error}</p>
  if (!run) return <p className="muted">Loading run #{id}…</p>

  return (
    <div className="run-view">
      <RunHeader run={run} />
      <PipelineSteps run={run} />
      {run.error && <p className="error banner">Pipeline error: {run.error}</p>}
      {run.disputedRunId && (
        <div className="info-banner">
          Re-check of <a href={`#/runs/${run.disputedRunId}`}>run #{run.disputedRunId}</a> after a dispute. Developer
          constraints this run treats as facts:
          <pre className="constraints">{run.constraints}</pre>
        </div>
      )}
      {rules.length > 0 && <RulesSection runId={run.id} rules={rules} feedback={feedback} onFeedback={reload} />}
      {run.status === 'SUCCEEDED' && <GerritPanel run={run} onPosted={reload} />}
      {run.status === 'SUCCEEDED' && comparisons.length > 0 && <VerdictSection run={run} comparisons={comparisons} />}
      {/* Older runs (before parent-vs-patch) only have patch results. */}
      {run.status === 'SUCCEEDED' && comparisons.length === 0 && tests.length > 0 && <TestTable tests={tests} />}
      <LogSection run={run} log={log} />
    </div>
  )
}

function RunHeader({ run }: { run: Run }) {
  return (
    <header className="run-header">
      <div className="run-title">
        <h2>Run #{run.id}</h2>
        <StatusBadge run={run} />
      </div>
      <p className="run-subject">{run.subject ?? 'Resolving change…'}</p>
      <dl className="facts">
        <dt>Gerrit</dt>
        <dd>
          <a href={run.gerritUrl} target="_blank" rel="noreferrer">
            {run.gerritProject ? `${run.gerritProject} · change ${run.gerritChange} · patchset ${run.patchset}` : run.gerritUrl}
          </a>
        </dd>
        {run.patchSha && (
          <>
            <dt>Commits</dt>
            <dd className="mono">
              patch {run.patchSha.slice(0, 10)} · parent {run.parentSha?.slice(0, 10) ?? '—'}
            </dd>
          </>
        )}
        {run.jiraUrl && (
          <>
            <dt>JIRA</dt>
            <dd>
              <a href={run.jiraUrl} target="_blank" rel="noreferrer">
                {run.jiraUrl}
              </a>
            </dd>
          </>
        )}
        {run.ticketText && (
          <>
            <dt>Ticket</dt>
            <dd>
              <details className="ticket">
                <summary>{run.ticketText.split('\n').find((l) => l.trim()) ?? ''}</summary>
                <pre>{run.ticketText}</pre>
              </details>
            </dd>
          </>
        )}
        <dt>Time</dt>
        <dd>
          {duration(run)}
          {run.tokensUsed > 0 && <span className="muted"> · {run.tokensUsed.toLocaleString()} LLM tokens</span>}
        </dd>
      </dl>
    </header>
  )
}

/** Shows the review text, posts it on request (design doc section 7). */
function GerritPanel({ run, onPosted }: { run: Run; onPosted: () => void }) {
  const [preview, setPreview] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function show() {
    setError(null)
    try {
      setPreview((await previewGerritReview(run.id)).message)
    } catch (e) {
      setError((e as Error).message)
    }
  }

  async function post() {
    setBusy(true)
    setError(null)
    try {
      await postGerritReview(run.id)
      onPosted()
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="section gerrit-panel">
      <div className="section-head">
        <h3>Gerrit review</h3>
        {run.gerritPostedAt ? (
          <span className="badge badge-good">POSTED {new Date(run.gerritPostedAt).toLocaleString()}</span>
        ) : (
          <div className="row">
            <button className="ghost small" onClick={show}>
              Preview comment
            </button>
            <button className="primary small" disabled={busy} onClick={post}>
              {busy ? 'Posting…' : 'Post to Gerrit'}
            </button>
          </div>
        )}
      </div>
      {preview && <pre className="code review-preview">{preview}</pre>}
      {error && <p className="error">{error}</p>}
    </section>
  )
}

function LogSection({ run, log }: { run: Run; log: LogEvent[] }) {
  const [follow, setFollow] = useState(true)
  const ref = useRef<HTMLPreElement>(null)
  useEffect(() => {
    if (follow && ref.current) ref.current.scrollTop = ref.current.scrollHeight
  }, [log, follow])
  // Open while running, folded away once finished; the person can toggle it either way.
  const finished = isFinished(run)
  const [open, setOpen] = useState(!finished)
  const [wasFinished, setWasFinished] = useState(finished)
  if (finished !== wasFinished) {
    setWasFinished(finished)
    if (finished) setOpen(false)
  }
  return (
    <details
      className="section log-section"
      open={open}
      onToggle={(e) => setOpen((e.currentTarget as HTMLDetailsElement).open)}
    >
      <summary>
        <h3>Live log</h3>
        <span className="muted">{log.length} lines</span>
      </summary>
      <label className="follow">
        <input type="checkbox" checked={follow} onChange={(e) => setFollow(e.target.checked)} /> Follow
      </label>
      <pre className="log" ref={ref}>
        {log.length ? log.map((l) => l.line).join('\n') : run.status === 'QUEUED' ? 'Waiting for a worker…' : ''}
      </pre>
    </details>
  )
}

function VerdictSection({ run, comparisons }: { run: Run; comparisons: Comparison[] }) {
  const [showAll, setShowAll] = useState(false)
  const count = (v: Comparison['verdict']) => comparisons.filter((c) => c.verdict === v).length
  // Anything other than "holds" is worth a look; show those first, hide the rest until asked.
  const interesting = comparisons.filter((c) => c.verdict !== 'NOTHING_FOUND')
  const visible = showAll ? comparisons : interesting
  return (
    <section className="section">
      <div className="section-head">
        <h3>Every check, before vs after the change</h3>
        <span className="muted">existing tests + generated checks</span>
      </div>
      <div className="stats">
        <Stat label="Broken by change" value={run.counterexamples} tone={run.counterexamples ? 'bad' : undefined} />
        <Stat label="Fixed by change" value={run.fixed} tone={run.fixed ? 'good' : undefined} />
        <Stat label="Not trusted" value={(run.preExisting ?? 0) + count('FLAKY') + count('COULDNT_CHECK')} />
        <Stat label="New tests" value={count('NEW_TEST')} />
        <Stat label="Hold" value={count('NOTHING_FOUND')} tone="good" />
      </div>
      {visible.length > 0 ? (
        <div className="table-wrap">
          <table className="tests">
            <thead>
              <tr>
                <th>Verdict</th>
                <th>Check</th>
                <th>Before</th>
                <th>After</th>
              </tr>
            </thead>
            <tbody>
              {visible.map((c, i) => {
                const v = VERDICT_LABEL[c.verdict]
                return (
                  <tr key={i}>
                    <td>
                      <span className={`badge badge-${v.tone}`} title={v.help}>
                        {v.label}
                      </span>
                    </td>
                    <td>
                      <div className="test-name">{c.name}</div>
                      <div className="test-class">{c.className}</div>
                      {c.message && c.verdict !== 'NOTHING_FOUND' && <div className="test-message">{c.message}</div>}
                    </td>
                    <td>
                      <StatusCell status={c.parentStatus} />
                    </td>
                    <td>
                      <StatusCell status={c.patchStatus} />
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      ) : (
        <p className="muted">Every check passes before and after the change.</p>
      )}
      {interesting.length !== comparisons.length && (
        <button className="link" onClick={() => setShowAll(!showAll)}>
          {showAll ? 'Hide the checks that hold' : `Show all ${comparisons.length} checks`}
        </button>
      )}
    </section>
  )
}

function StatusCell({ status }: { status: TestStatus | null }) {
  if (!status) return <span className="muted">—</span>
  const tone = status === 'PASSED' ? 'good' : status === 'SKIPPED' ? 'neutral' : 'bad'
  return <span className={`status status-${tone}`}>{status === 'PASSED' ? '✓ pass' : status.toLowerCase()}</span>
}

function Stat({ label, value, tone }: { label: string; value: number | null; tone?: string }) {
  return (
    <div className={`stat ${tone ? `stat-${tone}` : ''}`}>
      <span className="stat-value">{value ?? 0}</span>
      <span className="stat-label">{label}</span>
    </div>
  )
}

function TestTable({ tests }: { tests: TestCase[] }) {
  return (
    <section className="section">
      <h3>Test results (patch)</h3>
      <div className="table-wrap">
        <table className="tests">
          <tbody>
            {tests.slice(0, 100).map((t, i) => (
              <tr key={i}>
                <td>
                  <StatusCell status={t.status} />
                </td>
                <td>
                  <div className="test-name">{t.name}</div>
                  <div className="test-class">{t.className}</div>
                  {t.message && <div className="test-message">{t.message}</div>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}

function duration(run: Run): string {
  if (!run.startedAt) return 'queued at ' + new Date(run.createdAt).toLocaleString()
  const end = run.finishedAt ? new Date(run.finishedAt) : new Date()
  const s = Math.round((end.getTime() - new Date(run.startedAt).getTime()) / 1000)
  return `${Math.floor(s / 60)}m ${s % 60}s${run.finishedAt ? '' : ' so far'}`
}
