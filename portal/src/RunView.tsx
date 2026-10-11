import { useEffect, useRef, useState } from 'react'
import { getRun, isFinished, watchRun, type Comparison, type LogEvent, type Run, type TestCase, type TestStatus } from './api'
import { StatusBadge } from './StatusBadge'
import { VERDICT_LABEL } from './verdicts'

export function RunView({ id, onChange }: { id: number; onChange: () => void }) {
  const [run, setRun] = useState<Run | null>(null)
  const [tests, setTests] = useState<TestCase[]>([])
  const [comparisons, setComparisons] = useState<Comparison[]>([])
  const [log, setLog] = useState<LogEvent[]>([])
  const [error, setError] = useState<string | null>(null)
  const [followLog, setFollowLog] = useState(true)
  const logRef = useRef<HTMLPreElement>(null)

  useEffect(() => {
    let cancelled = false
    getRun(id)
      .then((d) => {
        if (!cancelled) {
          setRun(d.run)
          setTests(d.tests)
          setComparisons(d.comparisons)
        }
      })
      .catch((e: Error) => setError(e.message))

    const stop = watchRun(
      id,
      // The server may send a line twice around reconnects; keep each id once.
      (e) => setLog((prev) => (prev.length && prev[prev.length - 1].id >= e.id ? prev : [...prev, e])),
      (r) => {
        setRun(r)
        onChange()
        if (isFinished(r))
          getRun(id).then((d) => {
            if (!cancelled) {
              setTests(d.tests)
              setComparisons(d.comparisons)
            }
          })
      },
    )
    return () => {
      cancelled = true
      stop()
    }
  }, [id, onChange])

  useEffect(() => {
    if (followLog && logRef.current) logRef.current.scrollTop = logRef.current.scrollHeight
  }, [log, followLog])

  if (error) return <p className="error">Run #{id}: {error}</p>
  if (!run) return <p className="muted">Loading run #{id}…</p>

  const problems = tests.filter((t) => t.status === 'FAILED' || t.status === 'ERROR')

  return (
    <div className="run-view">
      <header className="run-header">
        <div>
          <h2>
            Run #{run.id} <StatusBadge run={run} />
          </h2>
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
                <dt>Commit</dt>
                <dd className="mono">
                  {run.patchSha.slice(0, 10)} (parent {run.parentSha?.slice(0, 10) ?? '—'})
                </dd>
              </>
            )}
            {run.jiraUrl && (
              <>
                <dt>JIRA</dt>
                <dd>
                  <a href={run.jiraUrl} target="_blank" rel="noreferrer">{run.jiraUrl}</a>
                </dd>
              </>
            )}
            {run.wikiUrls.length > 0 && (
              <>
                <dt>Wiki</dt>
                <dd>{run.wikiUrls.map((u) => <a key={u} href={u} target="_blank" rel="noreferrer" className="block">{u}</a>)}</dd>
              </>
            )}
            <dt>Time</dt>
            <dd>{duration(run)}</dd>
          </dl>
        </div>
      </header>

      {run.error && <p className="error banner">Pipeline error: {run.error}</p>}

      {run.status === 'SUCCEEDED' && comparisons.length > 0 && <VerdictSection run={run} comparisons={comparisons} />}

      {/* Older runs (before parent-vs-patch) only have patch results. */}
      {run.status === 'SUCCEEDED' && comparisons.length === 0 && (
        <section>
          <div className="stats">
            <Stat label="Tests" value={run.testsTotal} />
            <Stat label="Passed" value={(run.testsTotal ?? 0) - (run.testsFailed ?? 0) - (run.testsErrors ?? 0) - (run.testsSkipped ?? 0)} tone="good" />
            <Stat label="Failed" value={(run.testsFailed ?? 0) + (run.testsErrors ?? 0)} tone={problems.length ? 'bad' : undefined} />
            <Stat label="Skipped" value={run.testsSkipped} />
          </div>
          {tests.length > 0 && <TestTable tests={tests} />}
        </section>
      )}

      <section>
        <div className="log-head">
          <h3>Live log</h3>
          <label className="follow">
            <input type="checkbox" checked={followLog} onChange={(e) => setFollowLog(e.target.checked)} /> Follow
          </label>
        </div>
        <pre className="log" ref={logRef}>
          {log.length ? log.map((l) => l.line).join('\n') : run.status === 'QUEUED' ? 'Waiting for a worker…' : ''}
        </pre>
      </section>
    </div>
  )
}

function VerdictSection({ run, comparisons }: { run: Run; comparisons: Comparison[] }) {
  const [showAll, setShowAll] = useState(false)
  const count = (v: Comparison['verdict']) => comparisons.filter((c) => c.verdict === v).length
  // Anything other than "OK" is worth a look; show those first, hide the OK ones until asked.
  const interesting = comparisons.filter((c) => c.verdict !== 'NOTHING_FOUND')
  const visible = showAll ? comparisons : interesting
  return (
    <section>
      <h3 className="section-heading">Before vs after the change</h3>
      <div className="stats">
        <Stat label="Broken by change" value={run.counterexamples} tone={run.counterexamples ? 'bad' : undefined} />
        <Stat label="Fixed by change" value={run.fixed} tone={run.fixed ? 'good' : undefined} />
        <Stat label="Already failing" value={run.preExisting} />
        <Stat label="New tests" value={count('NEW_TEST')} />
        <Stat label="OK" value={count('NOTHING_FOUND')} tone="good" />
      </div>
      {run.patchBuildExit !== 0 && run.testsTotal === 0 && <p className="error banner">The patch doesn't build.</p>}
      {visible.length > 0 ? (
        <div className="table-wrap">
          <table className="tests">
            <thead>
              <tr>
                <th>Verdict</th>
                <th>Test</th>
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
                    <td><StatusCell status={c.parentStatus} /></td>
                    <td><StatusCell status={c.patchStatus} /></td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      ) : (
        <p className="muted">Every test passes before and after the change.</p>
      )}
      {interesting.length !== comparisons.length && (
        <button className="link" onClick={() => setShowAll(!showAll)}>
          {showAll ? 'Hide the OK tests' : `Show all ${comparisons.length} tests`}
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
  const [showAll, setShowAll] = useState(false)
  const visible = showAll ? tests : tests.slice(0, 50)
  return (
    <div className="table-wrap">
      <table className="tests">
        <thead>
          <tr>
            <th>Result</th>
            <th>Test</th>
            <th className="num">Time</th>
          </tr>
        </thead>
        <tbody>
          {visible.map((t, i) => (
            <tr key={i}>
              <td>
                <span className={`badge badge-${t.status === 'PASSED' ? 'good' : t.status === 'SKIPPED' ? 'neutral' : 'bad'}`}>
                  {t.status}
                </span>
              </td>
              <td>
                <div className="test-name">{t.name}</div>
                <div className="test-class">{t.className}</div>
                {t.message && <div className="test-message">{t.message}</div>}
              </td>
              <td className="num">{t.durationMs} ms</td>
            </tr>
          ))}
        </tbody>
      </table>
      {tests.length > 50 && (
        <button className="link" onClick={() => setShowAll(!showAll)}>
          {showAll ? 'Show first 50' : `Show all ${tests.length} tests`}
        </button>
      )}
    </div>
  )
}

function duration(run: Run): string {
  if (!run.startedAt) return 'queued at ' + new Date(run.createdAt).toLocaleString()
  const end = run.finishedAt ? new Date(run.finishedAt) : new Date()
  const s = Math.round((end.getTime() - new Date(run.startedAt).getTime()) / 1000)
  return `${Math.floor(s / 60)}m ${s % 60}s${run.finishedAt ? '' : ' so far'}`
}
