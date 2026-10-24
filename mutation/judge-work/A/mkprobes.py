#!/usr/bin/env python3
"""Build judge-A probe models from the generated KeyLocalLock models.

Each probe is a copy of one generated model with extra postconditions on the action methods.
The generated models are only read. Run: python3 -I mkprobes.py

Probe sets (one output file per model and set):
  equiv/  E_tryLock, E_unLock, E_sweep: the exact observable effect of each action
          (the map seen through has(), the fields of every live entry, the counter, the result).
          A mutant model that proves them has the same observable behavior as the original.
  gap/    One natural property per file that no sidecar clause states:
          G_expired (tryLock takes over an expired entry at once),
          G_cap (at the cap a new key gets an unbacked token and no entry),
          G_sweep (the monitor drops an entry that it finds expired).
"""
import pathlib

ROOT = pathlib.Path('/Users/ykhfree/AI/kotlin-verifier')
MECH = ROOT / 'translator/build/kv/mechanical'
OUT = ROOT / 'mutation/judge-work/A'

ITEMS = {
    'core': ['RET-02', 'RET-05', 'RET-10', 'BOOL-09', 'BOOL-10', 'MOP-01', 'RACY-02', 'RACY-04',
             'SDL-02', 'SDL-14'],
    'coroutine': ['RET-02', 'RET-05', 'RET-10', 'BOOL-02', 'BOOL-10', 'MOP-01', 'MOP-05', 'NEG-10',
                  'RACY-04', 'RACY-09', 'RACY-10', 'SDL-08'],
    'reactor': ['RET-02', 'RET-04', 'RET-07', 'BOOL-07', 'RACY-04', 'RACY-08', 'SDL-03', 'SDL-17'],
}

# Macros for the probes. JA_SAME: the entry of qk is the same object with the same fields.
# JA_FRAME: every other key keeps its presence, its entry object and that entry's fields.
MACROS = """\
// ---- judge-A probe macros (added to a copy of the generated model) ----
define JA_SAME(S, qk) (S.m[qk] == old(S.m[qk]) && S.m[qk].permits == old(S.m[qk].permits) &&
    S.m[qk].isHeld == old(S.m[qk].isHeld) && S.m[qk].token == old(S.m[qk].token) &&
    S.m[qk].expiresAt == old(S.m[qk].expiresAt))
define JA_FRAME(S, k) (forall qk: Key :: { S.m[qk] } { qk in domain(S.m) } qk != k ==>
    (has(S, qk) <==> old(has(S, qk))) && (old(has(S, qk)) ==> JA_SAME(S, qk)))

"""

EQUIV = {
    'tryLock': """\
  // judge-A E_tryLock: exact observable effect
  ensures old(has(S, k)) && now <= old(S.m[k].expiresAt) ==>
      res == NULL && S.seq == old(S.seq) && has(S, k) && JA_SAME(S, k)
  ensures old(has(S, k)) && now > old(S.m[k].expiresAt) ==>
      res == old(S.seq) + 1 && S.seq == old(S.seq) + 1 && has(S, k) && S.m[k] == old(S.m[k]) &&
      S.m[k].permits == 0 && S.m[k].isHeld && S.m[k].token == res && S.m[k].expiresAt == now + timeout
  ensures !old(has(S, k)) && rej ==> res == old(S.seq) + 1 && S.seq == old(S.seq) + 1 && !has(S, k)
  ensures !old(has(S, k)) && !rej ==>
      res == old(S.seq) + 1 && S.seq == old(S.seq) + 1 && has(S, k) && !(S.m[k] in old(S.created)) &&
      S.m[k].permits == 0 && S.m[k].isHeld && S.m[k].token == res && S.m[k].expiresAt == now + timeout
  ensures JA_FRAME(S, k)
""",
    'unLock': """\
  // judge-A E_unLock: exact observable effect
  ensures old(has(S, k)) && old(S.m[k].token) == t ==> res && !has(S, k)
  ensures !(old(has(S, k)) && old(S.m[k].token) == t) ==> !res && (has(S, k) <==> old(has(S, k)))
  ensures old(has(S, k)) && old(S.m[k].token) != t ==> JA_SAME(S, k)
  ensures S.seq == old(S.seq)
  ensures JA_FRAME(S, k)
""",
    'sweep': """\
  // judge-A E_sweep: exact observable effect
  ensures old(has(S, k)) && now > old(S.m[k].expiresAt) ==> !has(S, k)
  ensures !(old(has(S, k)) && now > old(S.m[k].expiresAt)) ==> (has(S, k) <==> old(has(S, k)))
  ensures old(has(S, k)) && now <= old(S.m[k].expiresAt) ==> JA_SAME(S, k)
  ensures S.seq == old(S.seq)
  ensures JA_FRAME(S, k)
""",
}

GAP = {
    'G_expired': ('tryLock', """\
  // judge-A G_expired: tryLock takes over an expired entry at once
  ensures old(has(S, k)) && now > old(S.m[k].expiresAt) ==> res != NULL && tok(res).gBacked
"""),
    'G_cap': ('tryLock', """\
  // judge-A G_cap: at the cap a new key gets an unbacked token and no map entry
  ensures !old(has(S, k)) && rej ==> res != NULL && !tok(res).gBacked && !has(S, k)
"""),
    'G_sweep': ('sweep', """\
  // judge-A G_sweep: the monitor drops an entry that it finds expired
  ensures old(has(S, k)) && now > old(S.m[k].expiresAt) ==> !has(S, k)
"""),
}


def add_ensures(text: str, extra: dict) -> str:
    """Insert extra ensures lines before the body of each named method; insert MACROS before init."""
    lines = text.splitlines(keepends=True)
    out, method, done = [], None, set()
    for line in lines:
        if line.startswith('method init()'):
            out.append(MACROS)
        if line.startswith('method '):
            method = line[len('method '):].split('(')[0]
        if line.rstrip('\n') == '{' and method in extra and method not in done:
            out.append(extra[method])
            done.add(method)
        out.append(line)
    missing = set(extra) - done
    if missing:
        raise SystemExit(f'methods not found: {sorted(missing)}')
    return ''.join(out)


def models():
    for variant, ids in ITEMS.items():
        base = MECH / f'keylocallock.{variant}' / 'models'
        yield variant, 'original', base / 'original.vpr'
        for mid in ids:
            yield variant, mid, base / f'{mid}.vpr'


def main():
    for sub in ('equiv', 'gap'):
        (OUT / sub).mkdir(parents=True, exist_ok=True)
    n = 0
    for variant, mid, path in models():
        text = path.read_text()
        (OUT / 'equiv' / f'{variant}__{mid}.vpr').write_text(add_ensures(text, EQUIV))
        n += 1
        for gid, (method, clause) in GAP.items():
            (OUT / 'gap' / f'{variant}__{mid}__{gid}.vpr').write_text(add_ensures(text, {method: clause}))
            n += 1
    print(f'wrote {n} probe models')


if __name__ == '__main__':
    main()
