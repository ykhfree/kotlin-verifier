#!/usr/bin/env python3
"""RACY-06 probe: add a rely for callers that pass a token they got from their own earlier tryLock.

Usage: holder_rely.py   (writes probes/<variant>__RACY-06__holder-rely.vpr)
"""
M = "/Users/ykhfree/AI/kotlin-verifier/translator/build/kv/mechanical"
NAMES = {"core": ("racy_6__229", "existing__230"), "coroutine": ("racy_6__209", "existing__210"),
         "reactor": ("racy_6__227", "existing__228")}
for v, (r, e) in NAMES.items():
    s = open(f"{M}/keylocallock.{v}/verify/RACY-06.vpr").read()
    old = f"  var {r}: Bool  // unnamed free value: arbitrary\n"
    new = (old +
           "  // judge B rely: the caller got t from its own tryLock before its containsKey. An entry that carries t\n"
           "  // at the compute existed since that tryLock, so the read saw it (a token is never carried again).\n"
           f"  inhale {e} != null && {e}.token == t ==> {r}\n")
    assert s.count(old) == 1
    open(f"probes/{v}__RACY-06__holder-rely.vpr", "w").write(s.replace(old, new))
    print(v)
