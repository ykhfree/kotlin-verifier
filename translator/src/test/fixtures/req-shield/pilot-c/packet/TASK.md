# Task: draft the formal specification of the local lock from the team's text

You turn the team's text into a formal specification. A person on the team will review your draft and decide every
point that the text leaves open, so your draft must make each of those points visible.

Files in this directory:
- `SOURCES.md`: the team's text (README, CLAUDE.md and the KDoc of the `KeyLock` interface), split into blocks with
  anchors. This text is the only source of intent. The specification is about the local lock, `KeyLocalLock`: its
  `tryLock`, its `unLock` and its cleanup monitor (`sweep`). Most of the text is about other parts of the library.
- `LANGUAGE.md`: the specification language.
- `SKELETON.kv`: the fixed part of the specification (bindings to the code shape, ghost state, operations).

There is no implementation to look at, and no tool to run. Work from the text and the language description only.

## Write three files in this directory

### 1. `DRAFT.kv`

A copy of `SKELETON.kv` with your clauses added at the lines that say DRAFT. Do not change, remove or reorder any
other line. Do not edit the `hole`.

Before each clause (issue rule, ensures, ends_only_if, unbacked_only_if, invariant, lemma, probe), write one comment
line per source:

```text
  // source: [ANCHOR] "EXACT QUOTE"
```

`ANCHOR` is a block anchor from SOURCES.md. `EXACT QUOTE` is a part of that block, copied character for character
(no ellipsis, no joining of two blocks). If a clause comes from no sentence of the text (for example the probe, or a
consistency fact), write `// source: none (REASON)` instead.

### 2. `COVERAGE.md`

A table with one row per block of SOURCES.md that states required behavior of the local lock (its tryLock, unLock,
cleanup monitor, map size cap, tokens or keys). Columns: anchor; what the block requires (one line); the clause IDs
that encode it, or "not encoded" with the reason (out of the model, cannot be stated in the language, or a decision
you did not want to make).

### 3. `QUESTIONS.md`

Every decision that you had to make, or that a reader of the text could make in more than one way. For each one:
- the anchor and the exact quote that leaves it open,
- the options (at least two), stated as concrete behavior,
- the option your DRAFT.kv encodes, and the clause IDs that encode it,
- how sure you are that the text means this option (high, medium, low), and why.

Write all three files in English. When you are done, report the names of the files you wrote and the number of
questions.

## Rules

- Work only inside this directory. Do not read, list or search any file outside it.
- Do not use the web, memory tools, other agents, or the advisor tool.
