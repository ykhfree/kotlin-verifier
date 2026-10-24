"""Writes compare.md: the first mechanical run (mutation/results.jsonl) against the re-run (mutation/rerun/).

Usage: python3 -I mutation/rerun/compare.py FIRST.jsonl RERUN.jsonl OUT.md TOOL_COMMIT

The first run has no sub-status. For it, the script derives one per FAILED action from its pass-1 obligations:
"property" if any obligation is not a hole conjunct (MI_INV_i), else "hole only (no property check)".
"""
import collections
import json
import re
import sys

HOLE = re.compile(r"^MI_INV_\d+$")
HOLE_ONLY = "hole only (no property check)"


def load(path):
    return {r["id"]: r for r in map(json.loads, open(path))}


def by_action(failing):
    """`action: obligation` entries grouped by action; init, lemma and probe entries (no `: `) are left out."""
    out = collections.defaultdict(list)
    for f in failing:
        if ": " in f:
            action, obligation = f.split(": ", 1)
            out[action].append(obligation)
    return out


def derived_sub(r):
    if r["verdict"] != "FAILED":
        return {}
    return {a: "property" if any(not HOLE.match(o) for o in obs) else HOLE_ONLY for a, obs in by_action(r["failing"]).items()}


def sub_text(sub):
    return "; ".join("%s: %s" % kv for kv in sorted(sub.items())) or "-"


def cell(items):
    return "<br>".join(items) or "-"


def short(mid):
    return mid.replace("keylocallock.", "")


def obligations(r, name):
    """Where obligation NAME fails in R: `pass 1`, `pass 2`, both, or None."""
    where = [p for p, fs in (("pass 1", r["failing"]), ("pass 2", r.get("pass2_failing", []))) if any(f.split(": ", 1)[-1] == name for f in fs)]
    return " + ".join(where) or None


def describe(r):
    if not r["compiled"]:
        return "사산"
    if r["verdict"] != "FAILED":
        return r["verdict"]
    return "FAILED (%s; pass 1 %s%s)" % (sub_text(r["sub_status"]), ", ".join(r["failing"]),
                                       "; pass 2 " + ", ".join(r["pass2_failing"]) if r["pass2_failing"] else "")


def check_prediction(n, rerun):
    """(text of the prediction, ids, per-id (matched, outcome))."""
    K = "keylocallock."
    if n == 1:
        ids = [K + "core/MOP-01", K + "coroutine/MOP-01", K + "core/RACY-02"]
        rule = lambda r: (r["verdict"] == "FAILED" and obligations(r, "expired_takeover") is not None)
        text = "MOP-01 ×2 (core, coroutine: 판정자 A의 D1)와 core RACY-02는 `expired_takeover`로 FAILED가 된다"
    elif n == 2:
        ids = [K + "core/RACY-04", K + "coroutine/RACY-04", K + "reactor/RACY-04", K + "coroutine/SDL-08"]
        rule = lambda r: (r["verdict"] == "FAILED" and obligations(r, "cap_unbacked") is not None)
        text = "RACY-04 ×3과 coroutine SDL-08은 `cap_unbacked`로 FAILED가 된다"
    elif n == 3:
        ids = [K + "coroutine/RACY-09", K + "reactor/RACY-08"]
        rule = lambda r: r["verdict"] == "PROVEN"
        text = "coroutine RACY-09와 reactor RACY-08은 PROVEN으로 남는다"
    elif n == 4:
        ids = [K + "coroutine/RACY-03", K + "core/RACY-03", K + "reactor/RACY-03", K + "coroutine/RET-11", K + "reactor/SDL-02"]
        rule = lambda r: r["verdict"] == "FAILED" and set(r["sub_status"].values()) == {"invariant only"}
        text = "RACY-03 ×3, coroutine RET-11, reactor SDL-02는 \"불변식만 불일치\"(invariant only)로 FAILED다"
    elif n == 5:
        ids = [K + "coroutine/RACY-06", K + "core/RACY-06", K + "reactor/RACY-06"]
        rule = lambda r: r["verdict"] == "FAILED" and "property" in r["sub_status"].values() and obligations(r, "progress_owner") is not None
        text = "RACY-06 ×3은 성질 위반(`progress_owner`)으로 FAILED다"
    else:
        ids = [K + "core/AOR-03", K + "reactor/AOR-03", K + "coroutine/SDL-06"]
        rule = lambda r: r["verdict"] == "FAILED" and set(r["sub_status"].values()) == {"invariant only"}
        text = ("\"불변식만 불일치\"는 무해하다는 뜻이 아니다: 판정자 B의 core·reactor AOR-03과 coroutine SDL-06(다음 단계에서 P3a를 깨는 결함)은 "
                "\"불변식만 불일치\"로 보고될 수 있다. 아래 \"맞음\"은 그 경우가 실제로 나타났다는 뜻이다")
    return text, [(i, rule(rerun[i]), describe(rerun[i])) for i in ids]


def main():
    first, rerun = load(sys.argv[1]), load(sys.argv[2])
    if set(first) != set(rerun):
        raise SystemExit("the two runs have different mutant ids: %s" % sorted(set(first) ^ set(rerun)))
    L = ["# 1차 측정과 재측정의 비교", "",
         "- 1차: `mutation/results.jsonl` (sidecar를 57c36eb에서 바꾸기 전, 성질 확인(pass 2) 없음).",
         "- 재측정: `mutation/rerun/results.jsonl` (sidecar 57c36eb, 도구 %s: 성질 확인(pass 2) 포함)." % sys.argv[4],
         "- 1차 측정에는 하위 상태가 없다. 이 표에서 1차의 하위 상태는 pass 1 실패 의무에서 유도한 값이다: hole 조각(`MI_INV_i`) 아닌 의무가 있으면 `property`, 모두 hole 조각이면 `%s`." % HOLE_ONLY,
         "- 실패 의무는 두 측정 모두 pass 1의 오류다. 재측정의 pass 2 오류는 따로 적는다.",
         "- 이 문서는 기계적 대조다. 동등·놓침 판정은 하지 않는다.",
         "- 생성: `python3 -I mutation/rerun/compare.py mutation/results.jsonl mutation/rerun/results.jsonl mutation/rerun/compare.md %s`." % sys.argv[4], ""]
    changed = []
    for i in sorted(first):
        a, b = first[i], rerun[i]
        sa, sb = derived_sub(a), b["sub_status"]
        if a["verdict"] != b["verdict"] or set(a["failing"]) != set(b["failing"]) or sa != sb:
            changed.append((i, a, b, sa, sb))
    L += ["## 1. 판정, 하위 상태, 실패 의무가 바뀐 변이 (%d개)" % len(changed), "",
          "| 변이 | 판정 1차 → 재측정 | 실패 의무 1차 | 실패 의무 재측정 (pass 1) | 하위 상태 1차 (유도) → 재측정 | pass 2 오류 (재측정) |", "|---|---|---|---|---|---|"]
    for i, a, b, sa, sb in changed:
        L.append("| %s | %s → %s | %s | %s | %s → %s | %s |" % (short(i), a["verdict"] or "사산", b["verdict"] or "사산", cell(a["failing"]), cell(b["failing"]),
                                                            sub_text(sa), sub_text(sb), cell(b["pass2_failing"])))
    L += ["", "## 2. 범주별 개수", "", "| 범주 | 1차 | 재측정 |", "|---|---|---|"]
    ca, cb = collections.Counter(r["class"] for r in first.values()), collections.Counter(r["class"] for r in rerun.values())
    for k in sorted(set(ca) | set(cb)):
        L.append("| %s | %d | %d |" % (k, ca[k], cb[k]))
    L += ["", "| 판정 | 1차 | 재측정 |", "|---|---|---|"]
    va, vb = collections.Counter(r["verdict"] or "사산" for r in first.values()), collections.Counter(r["verdict"] or "사산" for r in rerun.values())
    for k in sorted(set(va) | set(vb)):
        L.append("| %s | %d | %d |" % (k, va[k], vb[k]))
    L += ["", "FAILED action의 하위 상태 (변이×action 단위):", "", "| 하위 상태 | 1차 (유도) | 재측정 |", "|---|---|---|"]
    sa = collections.Counter(s for r in first.values() for s in derived_sub(r).values())
    sb = collections.Counter(s for r in rerun.values() for s in r["sub_status"].values())
    for k in sorted(set(sa) | set(sb)):
        L.append("| %s | %d | %d |" % (k, sa[k], sb[k]))
    hole_only = [r for r in first.values() if HOLE_ONLY in derived_sub(r).values()]
    moved = collections.Counter(rerun[r["id"]]["verdict"] if rerun[r["id"]]["verdict"] != "FAILED" else
                                " + ".join(sorted(set(s for a, s in rerun[r["id"]]["sub_status"].items() if derived_sub(r).get(a) == HOLE_ONLY))) or "FAILED (다른 action)"
                                for r in hole_only)
    L += ["", "1차에서 hole 조각만 보고된 변이 %d개의 재측정 결과: %s." % (len(hole_only), ", ".join("%s %d" % kv for kv in sorted(moved.items())))]
    L += ["", "## 3. 미리 등록한 예측 6개 (MUTATION.md 변경 기록)", "", "| # | 예측 | 대조 | 변이별 재측정 결과 |", "|---|---|---|---|"]
    for n in range(1, 7):
        text, rows = check_prediction(n, rerun)
        bad = [short(i) for i, ok, _ in rows if not ok]
        verdict = "맞음" if not bad else "안 맞음 (%s)" % ", ".join(bad)
        L.append("| %d | %s | %s | %s |" % (n, text, verdict, "<br>".join("%s: %s — %s" % (short(i), "맞음" if ok else "안 맞음", out) for i, ok, out in rows)))
    extra = rerun["keylocallock.reactor/MOP-01"]
    L += ["", "참고: 예측 1의 \"MOP-01 ×2\"는 판정자 A가 본 core와 coroutine이다. 판정 표본에 없던 reactor MOP-01의 재측정 결과: %s." % describe(extra), ""]
    with open(sys.argv[3], "w") as f:
        f.write("\n".join(L) + "\n")


if __name__ == "__main__":
    main()
