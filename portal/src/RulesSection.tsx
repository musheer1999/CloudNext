import { useState } from 'react'
import type { RuleView, TestStatus } from './api'
import { VERDICT_LABEL } from './verdicts'

/** The agent's work: rules (each with the quote it came from) and the attacks on them. */
export function RulesSection({ rules }: { rules: RuleView[] }) {
  const findings = rules.filter((r) => r.verdict === 'COUNTEREXAMPLE')
  return (
    <section>
      <h3 className="section-heading">Rules the change must guarantee, and the attacks on them</h3>
      {findings.length > 0 && (
        <div className="finding-banner">
          <strong>
            {findings.length} counterexample{findings.length > 1 ? 's' : ''} found.
          </strong>{' '}
          A generated test passes on the code before the change and fails after it, so the change broke the rule.
        </div>
      )}
      <div className="rules">
        {rules.map((r) => (
          <RuleCard key={r.key} rule={r} />
        ))}
      </div>
    </section>
  )
}

function RuleCard({ rule }: { rule: RuleView }) {
  const [showCode, setShowCode] = useState(rule.verdict === 'COUNTEREXAMPLE')
  const [copied, setCopied] = useState(false)
  const v = rule.verdict ? VERDICT_LABEL[rule.verdict] : null
  const dropped = rule.status === 'DROPPED'

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
    <div className={`rule-card ${rule.verdict === 'COUNTEREXAMPLE' ? 'rule-card-bad' : ''} ${dropped ? 'rule-card-dropped' : ''}`}>
      <div className="rule-top">
        <span className="rule-key">{rule.key}</span>
        <span className="badge badge-neutral">{rule.type}</span>
        {dropped && (
          <span className="badge badge-neutral" title={rule.dropReason ?? ''}>
            DROPPED
          </span>
        )}
        {v && (
          <span className={`badge badge-${v.tone}`} title={v.help}>
            {v.label}
          </span>
        )}
        {rule.attackStatus === 'COULDNT_COMPILE' && <span className="badge badge-neutral">TEST DIDN'T COMPILE</span>}
      </div>
      <div className="rule-text">{rule.text}</div>
      {rule.sourceQuote && (
        <blockquote className="rule-quote">
          “{rule.sourceQuote}” <span className="muted">— {rule.source || 'source'}</span>
        </blockquote>
      )}
      {dropped && <p className="muted">Dropped: {rule.dropReason}</p>}
      {rule.minimalInput && (
        <div className="counterexample">
          <span className="counterexample-label">Smallest failing input (shrunk by jqwik)</span>
          <code>{rule.minimalInput}</code>
        </div>
      )}
      {rule.testClass && (
        <div className="attack-facts">
          <span>
            Before: <StatusText status={rule.parentStatus} />
          </span>
          <span>
            After: <StatusText status={rule.patchStatus} />
          </span>
          <span className="muted">
            {rule.compileAttempts} compile attempt{rule.compileAttempts === 1 ? '' : 's'}
          </span>
          <button className="link" onClick={() => setShowCode(!showCode)}>
            {showCode ? 'Hide test' : 'Show test'}
          </button>
          <button className="link" onClick={copy}>
            {copied ? 'Copied ✓' : 'Copy test'}
          </button>
        </div>
      )}
      {showCode && rule.testCode && <pre className="code">{rule.testCode}</pre>}
    </div>
  )
}

function StatusText({ status }: { status: TestStatus | null }) {
  if (!status) return <span className="muted">—</span>
  const tone = status === 'PASSED' ? 'good' : status === 'SKIPPED' ? 'neutral' : 'bad'
  return <span className={`status status-${tone}`}>{status === 'PASSED' ? '✓ pass' : status.toLowerCase()}</span>
}
