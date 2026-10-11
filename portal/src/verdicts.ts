import type { Verdict } from './api'

/** How each parent-vs-patch verdict is shown in the portal. */
export const VERDICT_LABEL: Record<Verdict, { label: string; tone: string; help: string }> = {
  COUNTEREXAMPLE: { label: 'BROKEN BY CHANGE', tone: 'bad', help: 'Passed before the change, fails after it' },
  PRE_EXISTING: { label: 'ALREADY FAILING', tone: 'warn', help: 'Fails before and after: older bug or wrong test' },
  FIXED: { label: 'FIXED BY CHANGE', tone: 'good', help: 'Failed before the change, passes after it' },
  NEW_TEST: { label: 'NEW TEST', tone: 'info', help: 'Added by this change; only the patch result is known' },
  REMOVED: { label: 'REMOVED', tone: 'neutral', help: 'Existed before the change, gone after it' },
  COULDNT_CHECK: { label: "COULDN'T CHECK", tone: 'neutral', help: 'Skipped, or one side did not build' },
  NOTHING_FOUND: { label: 'OK', tone: 'good', help: 'Passes before and after' },
}
