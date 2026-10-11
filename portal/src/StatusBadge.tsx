import type { Run } from './api'

export function StatusBadge({ run }: { run: Run }) {
  const failedTests = (run.testsFailed ?? 0) + (run.testsErrors ?? 0)
  let label: string = run.status
  let tone = 'neutral'
  if (run.status === 'RUNNING') {
    label = run.step ?? 'RUNNING'
    tone = 'info'
  } else if (run.status === 'FAILED') {
    label = 'PIPELINE ERROR'
    tone = 'bad'
  } else if (run.status === 'SUCCEEDED') {
    label = failedTests > 0 ? `${failedTests} TEST${failedTests > 1 ? 'S' : ''} FAILED` : 'TESTS PASSED'
    tone = failedTests > 0 ? 'warn' : 'good'
  }
  return <span className={`badge badge-${tone}`}>{label}</span>
}
