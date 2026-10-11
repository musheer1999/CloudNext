# FailureForge demo script

For the contest video (about 4 minutes) or a live demo. Shows FailureForge catching planted bugs that each
change's own tests miss, one per specialist, and staying quiet on a correct change.

## Before recording (15 minutes earlier)

1. Start PostgreSQL if needed (Windows Services → `postgresql-x64-16` → Start).
2. Open **Ubuntu** once and run `docker ps` (wakes Docker up).
3. Start the server (PowerShell): `cd D:\CloudNext\server` then `.\mvnw.cmd spring-boot:run` (wait for "Started").
   For a shorter live run (~4 min), run `$env:FF_MAX_ATTACKS=1` first in the same window.
4. Start the portal in a second window: `cd D:\CloudNext\portal` then `npm run dev`; open http://localhost:5173.
5. Warm up the model: `ollama run failureforge-qwen3 "hi /no_think"`.
6. Have the finished results ready: the **Benchmark** page and the runs it links to.
7. Recording: Win + Alt + R (Xbox Game Bar). Browser zoom ~110%. Close personal tabs. No internal URLs on screen.

## The changes (all on review.gerrithub.io, project musheer1999/failureforge-sample)

| Ticket | Gerrit change | Planted bug | Caught by |
| --- | --- | --- | --- |
| SHOP-42 member discount | 1254028 | member + coupon ≥ 96% → negative price | Boundary |
| SHOP-43 refund cancelled orders | 1254070 | a cancelled order can be refunded twice | State |
| SHOP-44 lock-free stock | 1254071 | two checkouts at once both get the last item | Concurrency |
| SHOP-45 rename pricing method | 1254072 | renamed public method breaks every caller | Compat |
| SHOP-46 free shipping | 1254073 | none (correct change) | nobody |

Ticket texts: `server/src/main/resources/benchmark.json` (and `docs/demo/SHOP-42.md`).

## Video storyboard

| Time | Show | Say |
| --- | --- | --- |
| 0:00 | Portal welcome page | "FailureForge tries to break a code change before production does." |
| 0:15 | Gerrit change 1254070 (SHOP-43) | "A small change: refund cancelled orders. Its tests pass. Would you approve it?" |
| 0:35 | Paste link + ticket → Start run; the pipeline steps light up (speed up the wait) | "It checks out the code before and after the change, runs everything in a sealed Docker box with no network, and the AI writes rules, each quoting the ticket." |
| 1:15 | The red finding card | "State specialist: jqwik tried random sequences and shrank it to the shortest one that breaks the rule: pay, cancel, refund, refund. Refunded twice." |
| 1:40 | "Show test" / "Copy test" | "The developer gets a ready regression test, not just a warning." |
| 1:55 | Accept / Dispute / False positive | "Developers answer. A dispute re-runs with their constraint as a fact; a false positive is never raised again on that repo." |
| 2:15 | Gerrit panel → Preview comment (or the posted comment on Gerrit) | "Findings go back to Gerrit as a review comment." |
| 2:30 | Benchmark page | "Four planted bugs, one per specialist, and one correct change." Read out the numbers. |
| 3:00 | Click the Concurrency and Compat runs | "Two checkouts at the same moment oversell the last item. A renamed method breaks every existing caller, found by japicmp with no AI at all." |
| 3:30 | "Every check, before vs after" table | "The AI never decides there's a bug. Passes before, fails after: that's what decides. Wrong AI tests are 'not trusted', never reported." |
| 3:50 | Closing | "Runs on our office GPU model by changing settings only. Next: benchmark on real past Samsung bugs." |

## If something goes wrong live

- Model slow → show the finished benchmark runs.
- "Connection to localhost:5432 refused" → PostgreSQL isn't running (step 1).
- "docker ... failed. Is Docker running?" → open Ubuntu, run `docker ps`.
- No rules / "no ticket text" warning → paste the ticket text into the Ticket box.
