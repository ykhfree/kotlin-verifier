# Real end-to-end run, 2026-10-26 (DESIGN-C.md 10.6)

One LLM drafter (opus) and one alignment agent (opus), each in its own box; the main session ran the spec.py commands.
Run folder: a scratch folder outside the repo (it goes away). This folder keeps the drafts, answers, questions,
decisions, the aligned drafts and the command outputs (01-08), with the scratch path removed.

- DRAFT.kv, COVERAGE.md, QUESTIONS.md: phase 1 (passed the grammar check at once; 25 clauses, 30 quotes).
- answers.json: phase 2 on the 65 generated cells (settled 50, assumed 2, open 13).
- groups.json, decisions.json: written by the main session (grouping by hand, DESIGN-C.md 10.5; decisions from
  the user's earlier decisions, so the user was not asked again).
- DRAFT-aligned.kv: the alignment agent's draft. Its invariant and lemma lack `tok(x).gIssued`, so `kv verify`
  fails at init (align-verify.report).
- DRAFT-aligned-fixed.kv: the main session added `tok(x).gIssued` to the invariant and the lemma (two edits).
  This draft is PROVEN on the reference code (08-outcomes.txt, option A).
- DRAFT-aligned-loopback.kv: the alignment agent's own fix after it got only the init error line (loop-back test).
  It adds the same guards as the hand fix (line breaks and conjunct order differ); 09-align-check-loopback.txt: PROVEN on the reference code.
