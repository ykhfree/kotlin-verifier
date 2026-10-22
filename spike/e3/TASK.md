# Task: write the module invariant MI_INV

`skeleton.vpr` is a Viper model of `KeyLocalLock.kt` (also in this directory; read it for context).

- Each `lockMap.compute(k) { ... }` body in the Kotlin code is one Viper method (one atomic action):
  `tryLock`, `unLock`, and `sweep` (the monitor's per-key cleanup).
- Every action `requires MI(S)` and `ensures MI(S)`, plus the properties written in its contract.
  `MI(S)` is `MI_PERMS(S) && P2(S) && MI_INV(S)`.
- `init` builds the empty module, `lemma_P3c` derives a property from `MI`, and `probe` runs a short scenario.
- `MI_INV(S)` is currently `true`, so the file does not verify.

Your job: replace the body of `MI_INV` so that the whole file verifies.

## Rules

1. Edit only the text between `// @HOLE-BEGIN MI_INV` and `// @HOLE-END MI_INV`. You may add helper `define` macros there.
2. Do not use `assume`, `inhale`, `exhale`, `refute`, or new methods, functions, predicates, axioms, domains, or fields.
3. Verify only with `./verify.sh`. It checks rule 1 and 2, then runs the Silicon verifier. You have at most 20 calls.
   Do not run Java, Silicon, or Z3 directly.
4. Work only in this directory. Do not read, list, or search files outside it. Do not use the web,
   memory tools (such as cross-memory), or other agents.

## Report

When the file verifies, or when the budget is used up, report:
1. Verified or not, and the number of `./verify.sh` calls used.
2. The final `MI_INV` text.
3. For each call: what failed, and what you changed in response (one line each).
