# FailureForge demo script (Phase 2)

About 10 minutes. Shows FailureForge finding a planted boundary bug that the change's own tests miss.

## Before the meeting (15 minutes earlier)

1. Start PostgreSQL if needed (Windows Services → `postgresql-x64-16` → Start).
2. Open **Ubuntu** once and run `docker ps` (wakes Docker up).
3. Start the server (PowerShell): `cd D:\CloudNext\server` then `.\mvnw.cmd spring-boot:run` (wait for "Started").
   For a shorter live run (only the top rule, ~4 min), run `$env:FF_MAX_ATTACKS=1` first in the same window.
4. Start the portal in a second window: `cd D:\CloudNext\portal` then `npm run dev`, and open http://localhost:5173.
5. Warm up the model (the first answer loads it into memory, ~30 s):
   `ollama run hf.co/Qwen/Qwen3-8B-GGUF:Q4_K_M "hi /no_think"`
6. Keep run **#12** ready: it's the finished result (backup if anything is slow live).

## The story (talk track)

1. **The problem.** "CI and review check what developers expect. Bugs hide in what nobody expected: edge values."
2. **The change.** Open https://review.gerrithub.io/c/musheer1999/failureforge-sample/+/1254028 :
   "Members get an extra 5% off". Small, reasonable, **its 7 tests pass**. Would you approve it?
3. **The ticket.** Show `docs/demo/SHOP-42.md`, criterion 4: "The final price is never negative".
4. **Before FailureForge's AI (run #8).** Existing tests only: "NOTHING BROKEN". The bug is invisible.
5. **Start a live run.** Paste the Gerrit link + the ticket text into the portal → Start run.
   While it runs, narrate the live log:
   - Docker sandbox: tests run in a throwaway Linux container **with no network**.
   - Parent vs patch: every test runs on the code before and after the change.
   - Rules: the AI writes rules, each **quoting the ticket**; FailureForge checks the quote exists.
   - Boundary specialist: the AI writes a jqwik property test; the compiler checks it (repairs up to 3 times).
6. **The result (run #12, or the live one).**
   - R1 "never negative": **BROKEN BY CHANGE**. Passes before, fails after.
   - Smallest failing input, shrunk by jqwik: `pricePaise=20, discountPercent=100, member=true -> -1`.
   - "Show test" / "Copy test": a ready regression test the developer can add.
   - R2/R3: "already failing" on both sides, so they're **not** reported as bugs (the generated test was wrong).
7. **The key idea.** "The AI says what should be true; running code decides whether it is."
   The AI never declares a bug: the parent-vs-patch run does.
8. **Next.** State + Concurrency specialists (Phase 3), Gerrit comments + dispute loop (Phase 4), office LLM
   MuseGlimmer 30B (config only), benchmark on real past bugs.

## Numbers to quote (run #12, laptop, free local model Qwen3-8B)

- 6 rules from the ticket, all with verified quotes; 3 attacked; 1 counterexample; 0 false bugs reported.
- ~7.5 min per run (3 attacks), ~4.8k LLM tokens. The office GPU model should be much faster.

## If something goes wrong live

- Model slow or not answering → show run #12 (finished result).
- "Connection to localhost:5432 refused" → PostgreSQL isn't running (step 1).
- "docker ... failed. Is Docker running?" → open Ubuntu, run `docker ps`.
