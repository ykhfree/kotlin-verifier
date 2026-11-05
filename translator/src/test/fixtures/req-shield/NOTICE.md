# req-shield test fixtures

The files in this directory are test inputs for kotlin-verifier. They are not part of the tool.

They come from experiments that ran kotlin-verifier on req-shield:
https://github.com/line/req-shield (Copyright 2024 LY Corporation, Apache License 2.0, a copy is in `LICENSE`).
The checkout the tests use is commit `2e792d2` of a fork of that repository.

Files that contain or change req-shield code:

| Path | What it is |
|---|---|
| `spike/protocol2/fixed-core.patch` | A change to req-shield core `ReqShield.kt` |
| `spike/protocol2c/fixed-coroutine.patch` | A change to req-shield core-kotlin-coroutine `ReqShield.kt` |
| `spike/protocol2c/fix/a/.../ReqShield.kt`, `spike/protocol2c/fix/b/.../ReqShield.kt` | Changed copies of req-shield core-kotlin-coroutine `ReqShield.kt` |
| `aicode/round2/impl/*/KeyLocalLock.kt` | AI-written implementations of the req-shield core-kotlin-coroutine `KeyLock` interface, in its package |

The other files (Viper models, sidecar drafts, spec snapshots, scripts) quote or model req-shield code and documents.
