import { useState } from 'react'
import { sendFeedback, type Feedback, type RuleView, type TestStatus } from './api'
import { SpecialistChip } from './SpecialistChip'
import { specialistForType, VERDICT_LABEL } from './verdicts'

/**
 * The specialists' work. Findings (counterexamples) come first with Accept / Dispute / False Positive;
 * then everything else, grouped, so nothing the AI did is hidden.
 */
export function RulesSection({
  runId,
  rules,
  feedback,
  onFeedback,
}: {
  runId: number
  rules: RuleView[]
  feedback: Feedback[]
  onFeedback: () => void
}) {
  const findings = rules.filter((r) => r.verdict === 'COUNTEREXAMPLE')
  const holds = rules.filter((r) => r.verdict === 'NOTHING_FOUND')
  const untrusted = rules.filter(
    (r) => r.verdict && !['COUNTEREXAMPLE', 'NOTHING_FOUND'].includes(r.verdict),
  )
  const notAttacked = rules.filter((r) => r.status === 'KEPT' && !r.verdict)
  const dropped = rules.filter((r) => r.status === 'DROPPED')

  return (
    <section className="section">
      <div className="section-head">
        <h3>Findings</h3>
        <span className="muted">
          {rules.filter((r) => r.status === 'KEPT').length} rules · {rules.filter((r) => r.verdict).length} checked
        </span>
      </div>
      {findings.length > 0 ? (
        <div className="finding-banner">
          <strong>
            {findings.length} counterexample{findings.length > 1 ? 's' : ''} found.
          </strong>{' '}
          Each check passes on the code before the change and fails after it, so the change broke the rule. Plain
          code decided this, not the AI.
        </div>
      ) : (
        rules.some((r) => r.verdict) && (
          <div className="ok-banner">
            <strong>No counterexample found.</strong> Every rule that was checked still holds after the change.
          </div>
        )
      )}

      <div className="cards">
        {findings.map((r) => (
          <FindingCard
            key={r.key}
            runId={runId}
            rule={r}
            feedback={feedback.filter((f) => f.ruleKey === r.key)}
            onFeedback={onFeedback}
          />
        ))}
      </div>

      <RuleGroup title="Checked: the rule holds" hint="Passes before and after the change." rules={holds} />
      <RuleGroup
        title="Not trusted"
        hint="Fails on both sides (the generated test is probably wrong), flaky on re-runs, or couldn't be compiled. Never reported as a bug."
        rules={untrusted}
      />
      <RuleGroup title="Not attacked" hint="Kept, but outside this run's attack budget." rules={notAttacked} />
      <RuleGroup
        title="Dropped"
        hint="The quote couldn't be found in the ticket or the diff, or developers marked it a false positive before."
        rules={dropped}
      />
    </section>
  )
}

function FindingCard({
  runId,
  rule,
  feedback,
  onFeedback,
}: {
  runId: number
  rule: RuleView
  feedback: Feedback[]
  onFeedback: () => void
}) {
  const [showCode, setShowCode] = useState(false)
  const [copied, setCopied] = useState(false)
  const specialist = rule.specialist ?? specialistForType(rule.type)

  async function copy() {
    if (!rule.testCode) return
    try {
      await navigator.clipboard.writeText(rule.testCode)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      setCopied(false)
    }
  }

  return (
    <article className="finding">
      <header className="finding-top">
        <span className="badge badge-bad">BROKEN BY CHANGE</span>
        <SpecialistChip specialist={specialist} />
        <span className="rule-key">{rule.key}</span>
      </header>
      <h4 className="finding-rule">{rule.text}</h4>
      {rule.sourceQuote && (
        <blockquote className="rule-quote">
          “{rule.sourceQuote}” <span className="muted">— {rule.source || 'source'}</span>
        </blockquote>
      )}
      {rule.minimalInput && (
        <div className="counterexample">
          <span className="counterexample-label">
            {specialist === 'COMPAT' ? 'Incompatible API changes (japicmp)' : 'Smallest failing input (shrunk by jqwik)'}
          </span>
          <code>{rule.minimalInput}</code>
        </div>
      )}
      <div className="attack-facts">
        <span>
          Before the change: <StatusText status={rule.parentStatus} />
        </span>
        <span>
          After: <StatusText status={rule.patchStatus} />
        </span>
        {rule.testClass && (
          <>
            <span className="muted">
              {rule.compileAttempts} compile attempt{rule.compileAttempts === 1 ? '' : 's'}
            </span>
            <button className="link" onClick={() => setShowCode(!showCode)}>
              {showCode ? 'Hide test' : 'Show test'}
            </button>
            <button className="link" onClick={copy}>
              {copied ? 'Copied ✓' : 'Copy test'}
            </button>
          </>
        )}
      </div>
      {showCode && rule.testCode && <pre className="code">{rule.testCode}</pre>}
      <FeedbackBar runId={runId} rule={rule} feedback={feedback} onFeedback={onFeedback} />
    </article>
  )
}

/** Accept / Dispute / False Positive (design doc section 8). */
function FeedbackBar({
  runId,
  rule,
  feedback,
  onFeedback,
}: {
  runId: number
  rule: RuleView
  feedback: Feedback[]
  onFeedback: () => void
}) {
  const [mode, setMode] = useState<'none' | 'dispute'>('none')
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [rerun, setRerun] = useState<number | null>(null)

  async function send(type: Feedback['type']) {
    setBusy(true)
    setError(null)
    try {
      const id = await sendFeedback(runId, rule.key, type, text)
      setRerun(id)
      setMode('none')
      setText('')
      onFeedback()
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="feedback">
      {feedback.map((f) => (
        <div key={f.id} className={`feedback-done feedback-${f.type.toLowerCase()}`}>
          {f.type === 'ACCEPT' && '✓ Accepted: the developer confirms the bug.'}
          {f.type === 'FALSE_POSITIVE' && '✗ Marked false positive: rules like this are skipped on this repo from now on.'}
          {f.type === 'DISPUTE' && `↻ Disputed: “${f.text}”`}
        </div>
      ))}
      {rerun && (
        <div className="feedback-done">
          Re-checking with your constraint: <a href={`#/runs/${rerun}`}>run #{rerun}</a>
        </div>
      )}
      {mode === 'dispute' ? (
        <div className="dispute-form">
          <label>
            What does FailureForge not know? It will re-derive the rules and attack again with this as a fact.
            <textarea
              rows={2}
              value={text}
              placeholder="e.g. the discount is validated upstream and is never above 90%"
              onChange={(e) => setText(e.target.value)}
            />
          </label>
          <div className="row">
            <button className="primary small" disabled={busy || !text.trim()} onClick={() => send('DISPUTE')}>
              Dispute and re-check
            </button>
            <button className="ghost small" onClick={() => setMode('none')}>
              Cancel
            </button>
          </div>
        </div>
      ) : (
        <div className="row">
          <button className="ghost small good" disabled={busy} onClick={() => send('ACCEPT')}>
            Accept
          </button>
          <button className="ghost small" disabled={busy} onClick={() => setMode('dispute')}>
            Dispute…
          </button>
          <button className="ghost small" disabled={busy} onClick={() => send('FALSE_POSITIVE')}>
            False positive
          </button>
        </div>
      )}
      {error && <p className="error">{error}</p>}
    </div>
  )
}

function RuleGroup({ title, hint, rules }: { title: string; hint: string; rules: RuleView[] }) {
  if (rules.length === 0) return null
  return (
    <details className="rule-group">
      <summary>
        <span className="rule-group-title">{title}</span> <span className="count">{rules.length}</span>
        <span className="muted rule-group-hint">{hint}</span>
      </summary>
      <ul className="rule-list">
        {rules.map((r) => (
          <RuleRow key={r.key} rule={r} />
        ))}
      </ul>
    </details>
  )
}

function RuleRow({ rule }: { rule: RuleView }) {
  const [showCode, setShowCode] = useState(false)
  const v = rule.verdict ? VERDICT_LABEL[rule.verdict] : null
  const specialist = rule.specialist ?? specialistForType(rule.type)
  return (
    <li className="rule-row">
      <div className="rule-row-top">
        <span className="rule-key">{rule.key}</span>
        <SpecialistChip specialist={specialist} />
        {v && (
          <span className={`badge badge-${v.tone}`} title={v.help}>
            {v.label}
          </span>
        )}
        {rule.attackStatus === 'COULDNT_COMPILE' && <span className="badge badge-neutral">TEST DIDN'T COMPILE</span>}
        <span className="rule-row-text">{rule.text}</span>
      </div>
      {rule.sourceQuote && <div className="rule-row-quote">“{rule.sourceQuote}”</div>}
      {rule.dropReason && <div className="muted">Dropped: {rule.dropReason}</div>}
      {rule.minimalInput && rule.verdict !== 'NOTHING_FOUND' && <div className="test-message">{rule.minimalInput}</div>}
      {rule.testCode && (
        <button className="link" onClick={() => setShowCode(!showCode)}>
          {showCode ? 'Hide test' : 'Show generated test'}
        </button>
      )}
      {showCode && rule.testCode && <pre className="code">{rule.testCode}</pre>}
    </li>
  )
}

function StatusText({ status }: { status: TestStatus | null }) {
  if (!status) return <span className="muted">—</span>
  const tone = status === 'PASSED' ? 'good' : status === 'SKIPPED' ? 'neutral' : 'bad'
  return <span className={`status status-${tone}`}>{status === 'PASSED' ? '✓ pass' : status.toLowerCase()}</span>
}
