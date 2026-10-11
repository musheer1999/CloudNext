import type { Run } from './api'

export function StatusBadge({ run }: { run: Run }) {
  let label: string = run.status
  let tone = 'neutral'
  if (run.status === 'RUNNING') {
    label = (run.step ?? 'RUNNING').replace('_', ' ')
    tone = 'info'
  } else if (run.status === 'FAILED') {
    label = 'PIPELINE ERROR'
    tone = 'bad'
  } else if (run.status === 'SUCCEEDED') {
    const broken = run.counterexamples ?? 0
    const failedTests = (run.testsFailed ?? 0) + (run.testsErrors ?? 0)
    if (run.counterexamples === null) {
      // Runs from before parent-vs-patch existed: only patch results.
      label = failedTests > 0 ? `${failedTests} TEST${failedTests > 1 ? 'S' : ''} FAILED` : 'TESTS PASSED'
      tone = failedTests > 0 ? 'warn' : 'good'
    } else if (broken > 0) {
      label = `${broken} FINDING${broken > 1 ? "S" : ""}`
      tone = 'bad'
    } else {
      label = "NOTHING BROKEN"
      tone = 'good'
    }
  }
  return <span className={`badge badge-${tone}`}>{label}</span>
}
