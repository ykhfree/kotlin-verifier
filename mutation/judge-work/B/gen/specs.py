#!/usr/bin/env python3
"""Scenario specs of judge B. Each scenario fixes every free value, so an assert that verifies holds on that run.

`viol` asserts state that a property is false on that run; `holds` asserts state that it is true.
Usage: specs.py OUTDIR   (writes one SPEC.json per scenario)
"""
import json
import os
import sys

KV = "/Users/ykhfree/AI/kotlin-verifier/translator/build/kv"
RACY = {  # name of the racy local in each mutant model
    ("coroutine", "RACY-01"): "racy_1__155", ("core", "RACY-01"): "racy_1__173", ("reactor", "RACY-01"): "racy_1__170",
    ("coroutine", "RACY-03"): "racy_3__155", ("core", "RACY-03"): "racy_3__173", ("reactor", "RACY-03"): "racy_3__170",
    ("coroutine", "RACY-06"): "racy_6__209", ("core", "RACY-06"): "racy_6__229", ("reactor", "RACY-06"): "racy_6__227",
}


def mutant(var, mid):
    return f"{KV}/mechanical/keylocallock.{var}/verify/{mid}.vpr"


def original(var):
    return f"{KV}/keylocallock.{var}.vpr"


def p3a_viol(L, T, now):  # tryLock/sweep ends_only_if P3a fails for token T
    return (f"old[{L}](tok({T}).gIssued) && old[{L}](tok({T}).gBacked) && old[{L}](tok({T}).gHeld) && "
            f"!tok({T}).gHeld && !({now} > old[{L}](tok({T}).gLease))")


def p3a_holds(L, now):
    return (f"(forall qt: Int :: {{ tok(qt).gHeld }} old[{L}](tok(qt).gIssued) && old[{L}](tok(qt).gBacked) && "
            f"old[{L}](tok(qt).gHeld) && !tok(qt).gHeld ==> ({now} > old[{L}](tok(qt).gLease)))")


def live_viol(L, now, r):  # tryLock progress_live fails
    return f"old[{L}](has(S, k)) && {now} <= old[{L}](S.m[k].expiresAt) && {r} != NULL"


def live_holds(L, now, r):
    return f"(old[{L}](has(S, k)) && {now} <= old[{L}](S.m[k].expiresAt) ==> {r} == NULL)"


def tl(L, out, now, timeout=100, rej="false", limit=0, racy=None, doc=None):
    s = {"op": "tryLock", "label": L, "out": out, "now": str(now), "timeout": str(timeout), "rej": rej,
         "limit": str(limit)}
    if racy:
        s["racy"] = racy
    if doc:
        s["doc"] = doc
    return s


def ul(L, out, t, racy=None, doc=None):
    s = {"op": "unLock", "label": L, "out": out, "t": str(t)}
    if racy:
        s["racy"] = racy
    if doc:
        s["doc"] = doc
    return s


def sw(L, now, doc=None):
    s = {"op": "sweep", "label": L, "now": str(now)}
    if doc:
        s["doc"] = doc
    return s


def a(text):
    return {"assert": text}


def scenarios():
    out = []
    for var in ["coroutine", "core", "reactor"]:
        # RACY-01: A's containsKey ran before B created the entry (stale false); A's compute then sees B's live entry.
        r = RACY[(var, "RACY-01")]
        steps = [tl("L1", "rB", 1000, racy={r: "false"}, doc="B: no entry at B's containsKey, B creates the entry"),
                 tl("L2", "rA", 1000, racy={r: "false"}, doc="A: containsKey ran before B's compute (stale false)"),
                 a("rB == 1 && rA == 2"),
                 a(p3a_viol("L2", "rB", 1000)),
                 a(live_viol("L2", 1000, "rA"))]
        out.append((f"{var}__RACY-01__live", mutant(var, "RACY-01"), steps,
                    "B holds a live lock; A's stale tryLock replaces B's entry"))
        steps = [tl("L1", "rB", 1000, racy={r: "false"}),
                 tl("L2", "rA", 1000, rej="true", limit=5, racy={r: "false"}, doc="A at the cap, stale read"),
                 a("rB == 1 && rA == 2"),
                 a("rA != NULL && !tok(rA).gBacked && old[L2](has(S, k))"),  # P3b fails
                 a(p3a_viol("L2", "rB", 1000)),
                 a("!has(S, k)")]
        out.append((f"{var}__RACY-01__cap", mutant(var, "RACY-01"), steps,
                    "at the cap A's stale tryLock removes B's live entry and returns an unbacked token"))
        steps = [tl("L1", "rB", 1000), tl("L2", "rA", 1000), a("rB == 1 && rA == NULL"),
                 a(p3a_holds("L2", 1000)), a(live_holds("L2", 1000, "rA"))]
        out.append((f"{var}__RACY-01__orig", original(var), steps, "same inputs on the original code"))

        # RACY-03: B stalled after its clock read; A's stale tryLock frees B's expired entry; C (old clock) acquires.
        r = RACY[(var, "RACY-03")]
        steps = [tl("L1", "rB", 0, racy={r: "false"}, doc="B read its clock at 0 and stalled"),
                 tl("L2", "rA", 1000, racy={r: "false"}, doc="A: stale containsKey"),
                 a("rB == 1 && rA == NULL"),
                 a(p3a_holds("L2", 1000)),
                 a(live_holds("L2", 1000, "rA")),
                 a("has(S, k) && !S.m[k].isHeld && S.m[k].token == NULL && S.m[k].permits == 1"),
                 tl("L3", "rC", 50, racy={r: "true"}, doc="C read its clock at 50, containsKey after B's compute"),
                 a("rC == 2"),
                 a(live_viol("L3", 50, "rC"))]
        out.append((f"{var}__RACY-03__mut", mutant(var, "RACY-03"), steps,
                    "A's stale tryLock frees an expired entry without taking it; C with an older clock takes it"))
        steps = [tl("L1", "rB", 0), tl("L2", "rA", 1000), a("rB == 1 && rA == 2"),
                 tl("L3", "rC", 50), a("rC == NULL"), a(live_holds("L3", 50, "rC"))]
        out.append((f"{var}__RACY-03__orig", original(var), steps, "same inputs on the original code"))

        # RACY-06: X calls unLock with "local-1" before Y's tryLock issues "local-1".
        r = RACY[(var, "RACY-06")]
        steps = [tl("L1", "rY", 0, doc="Y acquires after X's containsKey"),
                 ul("L2", "rX", 1, racy={r: "false"}, doc="X: containsKey ran before Y's compute"),
                 a("rY == 1 && !rX"),
                 a("old[L2](has(S, k)) && old[L2](S.m[k].token) == 1"),  # progress_owner antecedent; consequent rX fails
                 ul("L3", "rY2", "rY", racy={r: "true"}, doc="Y releases its own token"),
                 a("rY2")]
        out.append((f"{var}__RACY-06__forged", mutant(var, "RACY-06"), steps,
                    "a caller presents a token that is issued after its containsKey"))
    # AOR-03: a new entry expires at `now`.
    for var in ["core", "reactor"]:
        steps = [tl("L1", "rA", 0), a("rA == 1 && S.m[k].expiresAt == 0 && tok(rA).gLease == 100"),
                 tl("L2", "rB", 1), a("rB == 2"), a(p3a_viol("L2", "rA", 1)), a(live_holds("L2", 1, "rB"))]
        out.append((f"{var}__AOR-03__take", mutant(var, "AOR-03"), steps, "B takes A's lock 1 ms after A got it"))
        steps = [tl("L1", "rA", 0), sw("L2", 1), a(p3a_viol("L2", "rA", 1)), a("!has(S, k)")]
        out.append((f"{var}__AOR-03__sweep", mutant(var, "AOR-03"), steps, "the monitor drops A's entry at now = 1"))
        steps = [tl("L1", "rA", 0), tl("L2", "rB", 1), a("rA == 1 && rB == NULL"), a(p3a_holds("L2", 1))]
        out.append((f"{var}__AOR-03__orig", original(var), steps, "same inputs on the original code"))
    # COR-01: || instead of && before the CAS.
    steps = [tl("L1", "rA", 0), tl("L2", "rB", 1), a("rA == 1 && rB == 2"),
             a(p3a_viol("L2", "rA", 1)), a(live_viol("L2", 1, "rB"))]
    out.append(("core__COR-01__take", mutant("core", "COR-01"), steps, "B takes A's live lock"))
    # RET-06: unLock keeps the entry.
    steps = [tl("L1", "rA", 0), ul("L2", "ok", "rA"), a("rA == 1 && ok"),
             a("has(S, k)"),  # P1 conjunct !has(S, k) fails
             a("tok(rA).gHeld"),  # P1 conjunct !tok(t).gHeld fails
             a("!S.m[k].isHeld && S.m[k].permits == 1 && S.m[k].token == rA")]
    out.append(("core__RET-06__keep", mutant("core", "RET-06"), steps, "the entry stays after a successful unLock"))
    # RET-08: unLock always returns true.
    steps = [ul("L1", "ok", 99), a("ok && !old[L1](has(S, k))")]
    out.append(("core__RET-08__empty", mutant("core", "RET-08"), steps, "unLock on an empty map returns true"))
    # SDL-12: tryLock on a new key returns null but creates a held entry.
    steps = [tl("L1", "rA", 0), a("rA == NULL && !old[L1](has(S, k))"),  # progress_free fails (rej = false)
             a("has(S, k) && S.m[k].isHeld && S.m[k].token == 1 && !tok(1).gIssued"),
             tl("L2", "rB", 1), a("rB == NULL")]
    out.append(("core__SDL-12__new", mutant("core", "SDL-12"), steps, "first tryLock of a key gets null"))
    # RET-11: the sweep keeps an expired entry; C with an older clock acquires it.
    steps = [tl("L1", "rA", 0), sw("L2", 101), a(p3a_holds("L2", 101)),
             a("has(S, k) && !S.m[k].isHeld && S.m[k].token == NULL && S.m[k].permits == 1"),
             tl("L3", "rC", 100, doc="C read its clock at 100, before the sweep pass started"),
             a("rC == 2"), a(live_viol("L3", 100, "rC"))]
    out.append(("coroutine__RET-11__sweep", mutant("coroutine", "RET-11"), steps,
                "the sweep frees but keeps an expired entry"))
    steps = [tl("L1", "rA", 0), sw("L2", 101), sw("L3", 5000), sw("L4", 1000000),
             a("has(S, k) && !S.m[k].isHeld && S.m[k].token == NULL && S.m[k].permits == 1")]
    out.append(("coroutine__RET-11__leak", mutant("coroutine", "RET-11"), steps, "the freed entry is never dropped"))
    # SDL-06: a takeover does not renew expiresAt.
    steps = [tl("L1", "rA", 0), tl("L2", "rC", 200), a("rC == 2 && S.m[k].expiresAt == 100 && tok(rC).gLease == 300"),
             tl("L3", "rD", 201), a("rD == 3"), a(p3a_viol("L3", "rC", 201))]
    out.append(("coroutine__SDL-06__take", mutant("coroutine", "SDL-06"), steps,
                "D takes C's fresh lock 1 ms after C took over"))
    steps = [tl("L1", "rA", 0), tl("L2", "rC", 200), a("rC == 2 && S.m[k].expiresAt == 100"),
             sw("L3", 201), a(p3a_viol("L3", "rC", 201)), a("!has(S, k)")]
    out.append(("coroutine__SDL-06__sweep", mutant("coroutine", "SDL-06"), steps,
                "the monitor drops C's fresh lock 1 ms after C took over"))
    # MOP-02: tryAcquire replaced by true.
    steps = [tl("L1", "rA", 0), tl("L2", "rB", 1), a("rB == 2"), a(p3a_viol("L2", "rA", 1)),
             a(live_viol("L2", 1, "rB")),
             tl("L3", "rC", 500), tl("L4", "rD", 1000), a("S.m[k] in S.created && S.m[k].permits == 2")]  # P2 fails
    out.append(("reactor__MOP-02__take", mutant("reactor", "MOP-02"), steps, "every tryLock on an entry takes it"))
    # SDL-02: no release on takeover; the entry becomes dead and stays until the sweep.
    steps = [tl("L1", "rA", 0), tl("L2", "rB", 200), a("rB == NULL"), a(p3a_holds("L2", 200)),
             a("has(S, k) && !S.m[k].isHeld && S.m[k].token == NULL && S.m[k].permits == 0"),
             tl("L3", "rC", 300), a("rC == NULL"), a(live_holds("L3", 300, "rC")),
             ul("L4", "ok", "rA"), a("!ok"),
             sw("L5", 400), a("!has(S, k)"),
             tl("L6", "rD", 401), a("rD == 2 && tok(rD).gBacked")]
    out.append(("reactor__SDL-02__dead", mutant("reactor", "SDL-02"), steps,
                "a takeover leaves a dead entry; the sweep drops it; the next tryLock works"))
    return out


def main():
    os.makedirs(sys.argv[1], exist_ok=True)
    for name, model, steps, doc in scenarios():
        json.dump({"name": name, "model": model, "steps": steps, "doc": doc},
                  open(os.path.join(sys.argv[1], name + ".json"), "w"), indent=1)
        print(name)


if __name__ == "__main__":
    main()
