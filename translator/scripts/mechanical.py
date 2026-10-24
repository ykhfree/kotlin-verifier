"""MUTATION.md: the mechanical mutation measurement of the six req-shield modules.

Usage:
  python3 -I mechanical.py --kv KV --req-shield DIR --classpath CP --aspect-classpath CP --kotlinc ARGS --specs DIR
                           --normalizer VPR_NORMALIZE.py --work DIR --results DIR [--jobs N] [--timeout S]
                           [--modules NAME[,NAME...]]

For each module (MUTATION.md 2), the script:
  1. lists the mutation sites with `KV mutation-sites` (MUTATION.md 3; the translator finds them in the FIR),
  2. copies the module's sources (mutants.py copy_sources) per site and applies the site's edits at their offsets,
  3. runs `KV emit`; a compile error makes the mutant stillborn (MUTATION.md 2). A site in the support sources is a
     library trusted fun that `kv emit` does not compile, so `KV discover` compiles the mutated file first,
  4. hashes the emitted Viper program in the normalized form of vpr_normalize.py (tokens, rename) (4.2),
  5. runs `KV verify` (with its Silicon budget) once per hash: on the original, and on the first mutant of each other
     hash; the mutants of a hash share its verdict. A hash equal to the original's is "model identical" (4.2). A
     mutant without a model (kv emit reported a sidecar or W error) gets its own `KV verify`, which runs no Silicon,
  6. classifies mechanically (MUTATION.md 5 and 7) and writes RESULTS/results.jsonl (4.3), RESULTS/summary.md (8)
     and the judge bundle RESULTS/judge/ (6 and its change log). The judged categories stay "pending judge".
Work files (copies, Viper programs, reports) go to WORK. Exit code: 0 done; 2 a script error.
"""
import argparse
import collections
import concurrent.futures
import difflib
import hashlib
import importlib.util
import json
import os
import random
import re
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
# Loading mutants.py must not rewrite the __pycache__ file the repository tracks.
sys.dont_write_bytecode = True


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


mutants = load("mutants", os.path.join(HERE, "mutants.py"))
ScriptError = mutants.ScriptError

# The six modules of MUTATION.md 2: name (the sidecar file without .kv), suite and unit of mutants.py.
MODULES = [
    ("keylocallock.coroutine", "keylocallock", "coroutine"),
    ("keylocallock.core", "keylocallock", "core"),
    ("keylocallock.reactor", "keylocallock", "reactor"),
    ("aspect.core-spring", "aspect", "core-spring"),
    ("aspect.webflux", "aspect", "webflux"),
    ("aspect.coroutine", "aspect", "coroutine"),
]
OPERATORS = ["ROR", "COR", "NEG", "BOOL", "SDL", "RET", "AOR", "MOP", "RACY"]

# Fixed seeds of the judge samples (MUTATION.md 6 cap rule and change log item 2), recorded in the index.
FAILED_SAMPLE_SEED = 20261024
PROVEN_SAMPLE_SEED = 20261025
FAILED_SAMPLE_SIZE = 10
JUDGE_CAP = 30

# kv lower: an UNSUPPORTED diagnostic, or an unsupported ledger entry, each ending with its position.
LOWER_UNSUPPORTED = re.compile(r"(?m)^\s+(?:U\d+_\d+ UNSUPPORTED|\d+(?:-\d+)? unsupported) (.+?) @\d+:\d+$")
FREE_VALUE = re.compile(r"(\S+) <- (.+?) \d+:\d+ \(([^)]*)\)(?:, |$)")


class Module:
    def __init__(self, name, suite, unit, args):
        self.name, self.unit = name, unit
        self.suite = mutants.SUITES[suite]
        self.classpath = args.aspect_classpath if suite == "aspect" else args.classpath
        self.sidecar = os.path.join(args.specs, self.suite.sidecar(unit))
        self.work = os.path.join(args.work, name)
        self.sites = []
        self.properties = []
        self.original = None  # the original's Mutant


class Mutant:
    def __init__(self, module, mid, site):
        self.module, self.id, self.site = module, mid, site
        self.root = os.path.join(module.work, "roots", mid.split("/")[-1])
        self.vpr = os.path.join(module.work, "models", mid.split("/")[-1] + ".vpr")
        self.compiled = None
        self.emit_message = ""
        self.hash = None
        self.tokens = None
        self.report = None  # (exit code, stdout, stderr) of the kv verify run that gives the verdict
        self.verified = False  # this mutant's kv verify ran
        self.shared_from = None  # id of the mutant whose kv verify gives this one's verdict ("original" for the original)
        self.shared_from_mutant = None
        self.lower = None  # (exit code, stdout, stderr) of kv lower on a mutant whose verdict is shared


def run(args, command, timeout=None):
    """Runs COMMAND; a run over the timeout is an exit code None with a message."""
    try:
        p = subprocess.run(command, capture_output=True, text=True, timeout=timeout or args.timeout)
        return p.returncode, p.stdout, p.stderr
    except subprocess.TimeoutExpired:
        return None, "", "kv did not finish in %d s" % (timeout or args.timeout)


def stderr_text(err):
    """kv's stderr without the compiler's language version warning."""
    return "\n".join(l for l in err.splitlines() if "language version 2.0 is deprecated" not in l).strip()


def relative(mu, text):
    """TEXT with the paths of the mutant's copy and of the specs directory made relative."""
    return text.replace(mu.root + os.sep, "").replace(os.path.dirname(mu.module.sidecar) + os.sep, "")


# ---- 1. sites ----

def list_sites(args, m):
    code, out, err = run(args, [args.kv, "mutation-sites", "--root", args.req_shield, "--classpath", m.classpath,
                                "--kotlinc", args.kotlinc, m.sidecar])
    if code != 0:
        raise ScriptError("%s: kv mutation-sites failed (exit %s):\n%s" % (m.name, code, stderr_text(err)))
    os.makedirs(m.work, exist_ok=True)
    with open(os.path.join(m.work, "sites.json"), "w") as f:
        f.write(out)
    data = json.loads(out)
    m.properties = data["properties"]
    counts = collections.Counter()
    m.sites = []
    for site in data["sites"]:
        counts[site["operator"]] += 1
        m.sites.append(Mutant(m, "%s/%s-%02d" % (m.name, site["operator"], counts[site["operator"]]), site))
    m.original = Mutant(m, "%s/original" % m.name, None)


# ---- 2-4. copies, emit, hash ----

def apply_edits(text, edits, where):
    for e in sorted(edits, key=lambda e: e["start"], reverse=True):
        if text[e["start"]:e["end"]] != e["original"]:
            raise ScriptError("%s: the text at %d-%d is %r, the site says %r" % (where, e["start"], e["end"], text[e["start"]:e["end"]], e["original"]))
        text = text[:e["start"]] + e["replacement"] + text[e["end"]:]
    return text


def make_copy(args, mu):
    mutants.copy_sources(mu.module.suite, args.req_shield, mu.root, mu.module.unit)
    if mu.site is None:
        return
    path = os.path.join(mu.root, mu.site["file"])
    with open(path) as f:
        text = f.read()
    with open(path, "w") as f:
        f.write(apply_edits(text, mu.site["edits"], mu.id))


def model_tokens(norm, path):
    try:
        return norm.rename(norm.tokens(path))
    except SystemExit:
        raise ScriptError("%s: vpr_normalize.py cannot read the program" % path)


def emit(args, norm, mu):
    make_copy(args, mu)
    os.makedirs(os.path.dirname(mu.vpr), exist_ok=True)
    m = mu.module
    if mu.site is not None and mu.site["file"].startswith(mutants.SUPPORT_DIR + "/"):
        code, _, err = run(args, [args.kv, "discover", "--classpath", m.classpath, "--kotlinc", args.kotlinc, os.path.join(mu.root, mu.site["file"])])
        if code != 0:
            mu.compiled, mu.emit_message = False, stderr_text(err)
            return
    code, _, err = run(args, mutants.kv_command(args.kv, "emit", mu.root, m.classpath, args.kotlinc, mu.vpr, m.sidecar))
    if code == 1 and "kv: compilation failed" in err:
        mu.compiled, mu.emit_message = False, stderr_text(err)
        return
    mu.compiled = True
    if code == 0:
        mu.tokens = model_tokens(norm, mu.vpr)
        mu.hash = hashlib.sha256(" ".join(mu.tokens).encode()).hexdigest()
    else:
        mu.emit_message = stderr_text(err) or "kv emit exit %s" % code


# ---- 5. verify once per hash ----

def verify(args, mu):
    m = mu.module
    out = os.path.join(m.work, "verify", mu.id.split("/")[-1] + ".vpr")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    mu.report = run(args, mutants.kv_command(args.kv, "verify", mu.root, m.classpath, args.kotlinc, out, m.sidecar))
    mu.verified = True
    with open(out[:-4] + ".report", "w") as f:
        f.write((mu.report[1] or "") + (mu.report[2] or ""))


def verify_jobs(modules):
    """The mutants whose kv verify runs: each original, the first mutant of each other hash, each mutant without a model."""
    jobs, first = [], {}
    for m in modules:
        jobs.append(m.original)
        first[(m.name, m.original.hash)] = m.original
        for mu in m.sites:
            if not mu.compiled:
                continue
            if mu.hash is None:
                jobs.append(mu)
            elif (m.name, mu.hash) not in first:
                first[(m.name, mu.hash)] = mu
                jobs.append(mu)
    return jobs, first


def lower(args, mu):
    """kv lower on a mutant that shares a verdict: its own completeness and unsupported constructs, without Silicon."""
    m = mu.module
    mu.lower = run(args, [args.kv, "lower", "--root", mu.root, "--classpath", m.classpath, "--kotlinc", args.kotlinc, m.sidecar])


def own_lowering(mu):
    """"INCOMPLETE" or "complete" from the mutant's own kv lower, and its unsupported constructs; None without one."""
    if mu.lower is None:
        return None, []
    out = mu.lower[1]
    state = "INCOMPLETE" if "\nmodule INCOMPLETE" in "\n" + out else "complete" if "\nmodule complete" in "\n" + out else "unknown"
    constructs = [m.group(1) for m in LOWER_UNSUPPORTED.finditer(out)]
    return state, constructs


def silicon_ran(mu):
    """True if the kv verify run reached Silicon: its report's first line gives Silicon's time."""
    return bool(mu.verified and mu.report[1] and "(Silicon " in mu.report[1].splitlines()[0])


# ---- 6. classification ----

def verdict_of(report):
    code = report[0]
    return mutants.VERDICTS.get(code, "ERROR") if code is not None else "ERROR"


def failing_obligations(stdout):
    """`action: obligation` per finding of a FAILED action row, and each init, lemma or probe row that FAILED."""
    try:
        rows, others = mutants.parse_report(stdout)
    except ScriptError as e:
        return ["(the report cannot be read: %s)" % e]
    out = ["%s: %s" % (name, i) for name, (v, ids) in sorted(rows.items()) if v == "FAILED" for i in sorted(ids)]
    out += [" ".join(o[:-1]) for o in others if o[-1] == "FAILED"]
    return out


def free_values(stdout):
    """Operation -> the Kotlin texts of its free values, from the report's 'Free values:' block."""
    out, inside = {}, False
    for line in stdout.splitlines():
        if line.strip() == "Free values:":
            inside = True
            continue
        if inside:
            if not line.startswith("    "):
                break
            op, _, rest = line.strip().partition(": ")
            out[op] = [t for _, t, _ in FREE_VALUE.findall(rest)]
    return out


def report_message(report):
    code, out, err = report
    lines = [l.strip() for l in out.splitlines() if l.strip().startswith(("ERROR:", "sidecar error:"))]
    return "; ".join(lines) or stderr_text(err) or "exit %s" % code


def unsupported(stdout):
    return next((l[len("Unsupported: "):] for l in stdout.splitlines() if l.startswith("Unsupported: ")), "")


def raises(tokens):
    return sum(1 for i in range(len(tokens) - 2) if tokens[i:i + 3] == ["exc", ":=", "true"])


def tags(mu):
    """The predictions of MUTATION.md 7 the mutant falls under, by rule."""
    s, facts, out = mu.site, mu.site["facts"], []
    op, repl = s["operator"], s["edits"][0]["replacement"]
    if op == "ROR" and facts.get("op") == ">" and repl == ">=" and "expiresAt" in facts.get("fields", "").split(","):
        out.append("7.1")
    if op == "RACY" and facts.get("setOnce") == "":
        out.append("7.2")
    if mu.tokens is not None and raises(mu.tokens) > raises(mu.module.original.tokens):
        out.append("7.3")
    report = (mu.shared_from_mutant or mu).report
    if report and report[1]:
        before, after = free_values(mu.module.original.report[1]), free_values(report[1])
        for name, texts in after.items():
            c, b = collections.Counter(texts), collections.Counter(before.get(name, []))
            if any(n >= 2 and n > b[t] for t, n in c.items()):
                out.append("7.4")
                break
    if s["scope"].startswith("trusted "):
        out.append("7.5")
    # 7.6 is about the slf4j model, so not about a trusted fun body, whose every mutant 7.5 predicts.
    if op == "SDL" and facts.get("callee", "").startswith("org/slf4j/Logger.") and not s["scope"].startswith("trusted "):
        out.append("7.6")
    if op == "SDL" and facts.get("removalFollows") == "true" and (
            facts.get("callee") == "java/util/concurrent/Semaphore.release" or (facts.get("field") == "token" and facts.get("value") == "null")):
        out.append("7.7")
    return out


def classify(record):
    v, t = record["verdict"], record["tags"]
    if not record["compiled"]:
        return "stillborn"
    if v == "PROVEN":
        return "abstracted by rule" if record["model_identical"] else "pending judge: PROVEN, model changed"
    if v == "FAILED":
        if "7.1" in t:
            return "detected (prediction 7.1)"
        hit = [p for p in ("7.2", "7.3", "7.4") if p in t]
        return "pending judge: FAILED, prediction %s" % "/".join(hit) if hit else "detected"
    if v == "ERROR":
        return "blocked by review" if "stale-review" in (record["message"] or "") else "tool defect candidate"
    return "unsupported"


def records(modules):
    out = []
    for m in modules:
        for mu in m.sites:
            s = mu.site
            r = {
                "id": mu.id, "module": m.name, "rule": s["operator"], "scope": s["scope"],
                "location": "%s:%d:%d-%d:%d" % (s["file"], s["line"], s["col"], s["endLine"], s["endCol"]),
                "edits": s["edits"], "compiled": mu.compiled, "verdict": None, "failing": [],
                "model_hash": mu.hash, "model_identical": None if mu.hash is None else mu.hash == m.original.hash,
                "ledger": s["ledger"], "facts": s["facts"], "tags": [], "class": None, "message": None,
                "silicon_run": silicon_ran(mu), "shared_from": mu.shared_from, "own_lowering": None, "dedupe_conflict": False,
            }
            if mu.compiled:
                report = (mu.shared_from_mutant or mu).report
                r["verdict"] = verdict_of(report)
                if r["verdict"] == "FAILED":
                    r["failing"] = failing_obligations(report[1])
                elif r["verdict"] == "ERROR":
                    r["message"] = relative(mu.shared_from_mutant or mu, report_message(report))
                elif r["verdict"] == "INCOMPLETE":
                    r["message"] = relative(mu.shared_from_mutant or mu, unsupported(report[1]))
                # A shared verdict comes from another copy's lowering; this copy's own lowering may differ (an
                # unsupported part is not emitted, so two lowerings can make one model). The verdict stays shared.
                state, constructs = own_lowering(mu)
                if state is not None:
                    r["own_lowering"] = state
                    r["dedupe_conflict"] = (r["verdict"] == "PROVEN" and state != "complete") or (r["verdict"] == "INCOMPLETE" and state != "INCOMPLETE")
                    if r["verdict"] == "INCOMPLETE" and constructs:
                        r["message"] = relative(mu, "; ".join(constructs))
            else:
                r["message"] = relative(mu, mu.emit_message)[-2000:]
            r["tags"] = tags(mu)
            r["class"] = classify(r)
            out.append(r)
    return out


# ---- judge bundle ----

def judge_items(recs):
    """The mutants the judge sees (MUTATION.md 6 and its change log), with why, and the notes for the index."""
    proven = [r for r in recs if r["class"] == "pending judge: PROVEN, model changed"]
    notes = []
    if len(proven) > JUDGE_CAP:
        first = [r for r in proven if r["rule"] == "RET" or (r["rule"] == "ROR" and r["facts"].get("boundary") == "true")]
        rest = sorted((r for r in proven if r not in first), key=lambda r: r["id"])
        k = max(0, JUDGE_CAP - len(first))
        sample = random.Random(PROVEN_SAMPLE_SEED).sample(rest, min(k, len(rest)))
        notes.append("\"PROVEN, 모델 바뀜\" %d개가 상한 %d을 넘는다: ROR 경계와 RET %d개를 모두 넣고, 나머지 %d개에서 무작위 %d개를 뽑았다 (seed %d). 이 범주의 판정은 표본이다."
                     % (len(proven), JUDGE_CAP, len(first), len(rest), len(sample), PROVEN_SAMPLE_SEED))
        proven = first + sorted(sample, key=lambda r: r["id"])
    items = [(r, "PROVEN, 모델 바뀜") for r in proven]
    predicted = [r for r in recs if r["class"].startswith("pending judge: FAILED")]
    items += [(r, "FAILED, 예측 " + r["class"].rsplit(" ", 1)[-1]) for r in predicted]
    others = sorted((r for r in recs if r["verdict"] == "FAILED" and r not in predicted), key=lambda r: r["id"])
    sample = sorted(random.Random(FAILED_SAMPLE_SEED).sample(others, min(FAILED_SAMPLE_SIZE, len(others))), key=lambda r: r["id"])
    notes.append("그 밖의 FAILED %d개에서 무작위 %d개 (seed %d)." % (len(others), len(sample), FAILED_SAMPLE_SEED))
    items += [(r, "FAILED, 무작위 표본") for r in sample]
    return items, notes


def snippet(text, first, last):
    lines = text.splitlines()
    lo, hi = max(1, first - 3), min(len(lines), last + 3)
    return "\n".join("%4d | %s" % (n, lines[n - 1]) for n in range(lo, hi + 1))


def judge_file(args, norm, modules, r, why):
    m = next(x for x in modules if x.name == r["module"])
    mu = next(x for x in m.sites if x.id == r["id"])
    with open(os.path.join(args.req_shield, mu.site["file"])) as f:
        original = f.read()
    mutated = apply_edits(original, mu.site["edits"], mu.id)
    first = min(original.count("\n", 0, e["start"]) + 1 for e in mu.site["edits"])
    last = max(original.count("\n", 0, e["end"]) + 1 for e in mu.site["edits"])
    grown = mutated.count("\n") - original.count("\n")
    diff = "".join(difflib.unified_diff([l + "\n" for l in norm.lines(m.original.tokens)], [l + "\n" for l in norm.lines(mu.tokens)],
                                        "original.vpr", "mutant.vpr", n=2)) if mu.tokens is not None else "(모델 없음)\n"
    props = "\n".join(
        "- %s %s%s (%s): `%s`%s" % (p["kind"], p["id"], " / " + p["operation"] if p["operation"] else "", p["at"], p["text"],
                                    "\n  - 검토 메모(출처 인용): \"%s\"" % p["reviewed"] if p["reviewed"] else "")
        for p in m.properties)
    return """# %s

- 모듈: %s (sidecar `%s`)
- 규칙: %s (%s)
- 위치: `%s`
- ledger 종류: %s
- 판정: %s
- 실패 의무: %s
- 판정자가 보는 이유: %s

## Kotlin 원본
```kotlin
%s
```

## Kotlin 변이
```kotlin
%s
```

## 정규화한 Viper diff (원본 → 변이)
```diff
%s```

## sidecar 성질 (원문과 출처 인용)
%s
""" % (r["id"], r["module"], os.path.basename(m.sidecar), r["rule"], r["scope"], r["location"], r["ledger"], r["verdict"],
       "; ".join(r["failing"]) or "없음", why, snippet(original, first, last), snippet(mutated, first, last + grown), diff, props)


def write_judge(args, norm, modules, recs):
    out = os.path.join(args.results, "judge")
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    items, notes = judge_items(recs)
    index = ["# 판정자 묶음", "", "MUTATION.md 6절과 변경 기록에 따라 판정자가 볼 변이다. 파일 하나에 변이 하나다.", ""]
    index += ["- " + n for n in notes]
    counts = collections.Counter(why for _, why in items)
    index += ["", "| 범주 | 개수 |", "|---|---|"] + ["| %s | %d |" % (k, v) for k, v in sorted(counts.items())]
    index += ["", "| 파일 | 모듈 | 규칙 | 판정 | 범주 |", "|---|---|---|---|---|"]
    for r, why in items:
        name = r["id"].replace("/", "__") + ".md"
        with open(os.path.join(out, name), "w") as f:
            f.write(judge_file(args, norm, modules, r, why))
        index.append("| [%s](%s) | %s | %s | %s | %s |" % (r["id"], name, r["module"], r["rule"], r["verdict"], why))
    with open(os.path.join(out, "index.md"), "w") as f:
        f.write("\n".join(index) + "\n")
    return items, notes


# ---- summary (MUTATION.md 8) ----

def write_summary(args, modules, recs, items, notes, stats):
    by = collections.defaultdict(list)
    for r in recs:
        by[(r["module"], r["rule"])].append(r)
    L = ["# 기계적 변이 측정 결과", "", "MUTATION.md 8절 형식이다. 판정자가 정할 범주는 \"판정 대기\"로 둔다.", ""]
    L += ["- 측정: %s, wall clock %.0f s, 병렬 %d." % (time.strftime("%Y-%m-%d"), stats["seconds"], args.jobs),
          "- 원본 판정: " + ", ".join("%s %s" % (m.name, verdict_of(m.original.report)) for m in modules) + ".",
          "- 변이 %d개, 사산 %d개, 모델이 있는 변이 %d개. Silicon 실행 %d회 (원본 %d회, 새 해시 %d회). 중복 제거로 아낀 Silicon 실행: %d회."
          % (len(recs), sum(not r["compiled"] for r in recs), stats["with_model"], stats["silicon"], len(modules), stats["new_hashes"], stats["saved"])]
    shared = [r for r in recs if r["own_lowering"] is not None]
    conflicts = [r for r in shared if r["dedupe_conflict"]]
    L += ["- 중복 제거 검사: 판정을 함께 쓴 변이 %d개를 각자 `kv lower`로 다시 낮췄다 (Silicon 없음). 완결성이 함께 쓴 판정과 어긋나는 변이: %d개%s."
          % (len(shared), len(conflicts), "".join("\n  - %s: 함께 쓴 판정 %s (%s에서), 자기 lowering %s" % (r["id"], r["verdict"], r["shared_from"], r["own_lowering"]) for r in conflicts)), ""]
    L += ["## 1. 모듈별·규칙별 변이 수, 사산 수, 판정 분포", "",
          "| 모듈 | 규칙 | 변이 | 사산 | PROVEN (모델 동일) | PROVEN (모델 바뀜) | FAILED | ERROR | INCOMPLETE |", "|---|---|---|---|---|---|---|---|---|"]
    for m in modules:
        for op in OPERATORS:
            rs = by.get((m.name, op), [])
            if rs:
                L.append("| %s | %s | %d | %d | %d | %d | %d | %d | %d |" % (
                    m.name, op, len(rs), sum(not r["compiled"] for r in rs),
                    sum(r["verdict"] == "PROVEN" and r["model_identical"] for r in rs), sum(r["verdict"] == "PROVEN" and not r["model_identical"] for r in rs),
                    sum(r["verdict"] == "FAILED" for r in rs), sum(r["verdict"] == "ERROR" for r in rs), sum(r["verdict"] == "INCOMPLETE" for r in rs)))
    L += ["", "규칙별 사산 수: " + ", ".join("%s %d" % (op, sum(not r["compiled"] for r in recs if r["rule"] == op)) for op in OPERATORS) + ".", ""]
    detected = [r for r in recs if r["class"].startswith("detected")]
    L += ["## 2. 변이 점수", "", "점수 = 검출 / (검출 + 놓침). 놓침은 판정자가 정하므로 점수는 판정 대기다. 아래는 기계적으로 정한 검출 수다 (판정자의 FAILED 표본 결과에 따라 줄 수 있다).", "",
          "| 모듈 | 검출 | 놓침 | 점수 |", "|---|---|---|---|"]
    for m in modules:
        L.append("| %s | %d | 판정 대기 | 판정 대기 |" % (m.name, sum(r["module"] == m.name for r in detected)))
    L += ["", "| 규칙 | 검출 | 놓침 | 점수 |", "|---|---|---|---|"]
    for op in OPERATORS:
        L.append("| %s | %d | 판정 대기 | 판정 대기 |" % (op, sum(r["rule"] == op for r in detected)))
    abstracted = [r for r in recs if r["class"] == "abstracted by rule"]
    kinds = collections.Counter(r["ledger"] for r in abstracted)
    pending_proven = sum(r["class"] == "pending judge: PROVEN, model changed" for r in recs)
    L += ["", "## 3. 생존 변이", "",
          "- 규칙으로 추상화됨 (PROVEN, 모델 동일): %d개. ledger 종류별: %s." % (len(abstracted), ", ".join("%s %d" % kv for kv in sorted(kinds.items())) or "없음"),
          "- 동등 / 놓침 / 미결: 판정 대기 (\"PROVEN, 모델 바뀜\" %d개)." % pending_proven, ""]
    if abstracted:
        L += ["| 변이 | ledger 종류 | 위치 | 바꾼 텍스트 |", "|---|---|---|---|"]
        for r in sorted(abstracted, key=lambda r: (r["ledger"], r["id"])):
            L.append("| %s | %s | %s | %s |" % (r["id"], r["ledger"], r["location"].rsplit("/", 1)[-1], edit_text(r)))
        L.append("")
    failed = [r for r in recs if r["verdict"] == "FAILED"]
    L += ["## 4. FAILED", "",
          "- 검출: %d개 (예측 7.1: %d개, 그 밖: %d개; 그 밖 중 %d개는 판정자 표본)." % (
              len(detected), sum("7.1" in r["tags"] and r["verdict"] == "FAILED" for r in recs), sum(r["class"] == "detected" for r in recs),
              sum(1 for _, why in items if why == "FAILED, 무작위 표본")),
          "- 예측된 오경보 / 설명 안 된 오경보: 판정 대기 (예측 7.2-7.4에 맞는 FAILED %d개, 무작위 표본 %d개)." % (
              sum(r["class"].startswith("pending judge: FAILED") for r in recs), sum(1 for _, why in items if why == "FAILED, 무작위 표본")),
          "- FAILED 합계: %d개." % len(failed), ""]
    errors = [r for r in recs if r["verdict"] == "ERROR"]
    defects = [r for r in errors if r["class"] == "tool defect candidate"]
    incomplete = [r for r in recs if r["verdict"] == "INCOMPLETE"]
    L += ["## 5. ERROR와 INCOMPLETE", "",
          "- 서명이 막음 (stale-review): %d개." % sum(r["class"] == "blocked by review" for r in recs),
          "- 도구 결함 후보: %d개." % len(defects)]
    L += ["  - %s: %s" % (r["id"], r["message"]) for r in defects]
    L += ["- INCOMPLETE: %d개." % len(incomplete)]
    L += ["  - %s: %s" % (r["id"], r["message"]) for r in incomplete]
    L += ["", "## 6. 7절 예측과 실제", "", "| 예측 | 규칙으로 고른 변이 | 판정 분포 | 대조 |", "|---|---|---|---|"]
    for p, rule, expect in PREDICTIONS:
        rs = [r for r in recs if p in r["tags"]]
        dist = collections.Counter(r["verdict"] or "사산" for r in rs)
        outcome = expect(rs) if rs else NONE.get(p, "해당 변이 없음")
        L.append("| %s | %s | %s | %s |" % (p, rule, ", ".join("%s %d" % kv for kv in sorted(dist.items())) or "없음", outcome))
    L += ["", "## 판정자 묶음", "", "`judge/index.md`: %d개." % len(items)] + ["- " + n for n in notes]
    with open(os.path.join(args.results, "summary.md"), "w") as f:
        f.write("\n".join(L) + "\n")


def edit_text(r):
    return " / ".join("`%s` → `%s`" % (e["original"].replace("\n", "⏎").replace("|", "\\|")[:60], e["replacement"].replace("\n", "⏎").replace("|", "\\|")[:60]) for e in r["edits"])


def compiled(rs):
    return [r for r in rs if r["compiled"]]


PREDICTIONS = [
    ("7.1", "ROR `>`→`>=`, 피연산자가 `expiresAt`",
     lambda rs: "모두 FAILED (예측과 같음)" if all(r["verdict"] == "FAILED" for r in compiled(rs)) else "예측과 다름: FAILED 아닌 것 있음"),
    ("7.2", "RACY, `set-once` 없는 map",
     lambda rs: "FAILED %d개는 판정 대기" % sum(r["verdict"] == "FAILED" for r in rs)),
    ("7.3", "모델의 `exc := true`가 원본보다 많음",
     lambda rs: "%d개 생김 (예측과 다름)" % len(rs)),
    ("7.4", "같은 Kotlin 식의 자유 값이 둘 이상",
     lambda rs: "%d개 생김 (예측과 다름)" % len(rs)),
    ("7.5", "trusted fun 본문의 변이",
     lambda rs: "컴파일되는 것 모두 stale-review (예측과 같음)" if all(r["class"] == "blocked by review" for r in compiled(rs)) else "예측과 다름: stale-review 아닌 것 있음"),
    ("7.6", "SDL, `log.*` 호출",
     lambda rs: "모두 PROVEN, 모델 동일 (예측과 같음)" if all(r["class"] == "abstracted by rule" for r in compiled(rs)) else "예측과 다름: 모델이 바뀌거나 PROVEN이 아닌 것 있음"),
    ("7.7", "SDL, 바로 지워지는 entry의 `release()`나 `token = null`",
     lambda rs: "PROVEN 모델 바뀜 %d개는 판정 대기; 그 밖 %d개" % (sum(r["class"] == "pending judge: PROVEN, model changed" for r in rs),
                                                     sum(r["class"] != "pending judge: PROVEN, model changed" for r in rs))),
]
# 7.3 and 7.4 are predicted not to occur; for every prediction, no mutant under its rule is its own outcome.
NONE = {"7.3": "생기지 않음 (예측과 같음)", "7.4": "생기지 않음 (예측과 같음)"}


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    for name in ("--kv", "--req-shield", "--classpath", "--aspect-classpath", "--kotlinc", "--specs", "--normalizer", "--work", "--results"):
        ap.add_argument(name, required=True)
    ap.add_argument("--jobs", type=int, default=3)
    ap.add_argument("--timeout", type=int, default=900, help="seconds per kv run")
    ap.add_argument("--modules", help="comma-separated module names (default: all six)")
    args = ap.parse_args()
    start = time.time()
    try:
        norm = load("vpr_normalize", args.normalizer)
        wanted = args.modules.split(",") if args.modules else [n for n, _, _ in MODULES]
        unknown = set(wanted) - {n for n, _, _ in MODULES}
        if unknown:
            raise ScriptError("unknown module(s): %s" % sorted(unknown))
        modules = [Module(n, s, u, args) for n, s, u in MODULES if n in wanted]
        for m in modules:
            shutil.rmtree(m.work, ignore_errors=True)
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
            list(pool.map(lambda m: list_sites(args, m), modules))
            everyone = [m.original for m in modules] + [mu for m in modules for mu in m.sites]
            list(pool.map(lambda mu: emit(args, norm, mu), everyone))
            for m in modules:
                if m.original.hash is None:
                    raise ScriptError("%s: kv emit failed on the original:\n%s" % (m.name, m.original.emit_message))
            jobs, first = verify_jobs(modules)
            list(pool.map(lambda mu: verify(args, mu), jobs))
        for m in modules:
            if verdict_of(m.original.report) != "PROVEN":
                raise ScriptError("%s: the original is %s, not PROVEN:\n%s" % (m.name, verdict_of(m.original.report), m.original.report[1]))
            for mu in m.sites:
                if mu.hash is not None:
                    rep = first[(m.name, mu.hash)]
                    if rep is not mu:
                        mu.shared_from_mutant = rep
                        mu.shared_from = "original" if rep is m.original else rep.id
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
            list(pool.map(lambda mu: lower(args, mu), [mu for m in modules for mu in m.sites if mu.shared_from_mutant]))
        recs = records(modules)
        os.makedirs(args.results, exist_ok=True)
        with open(os.path.join(args.results, "results.jsonl"), "w") as f:
            for r in recs:
                f.write(json.dumps(r, ensure_ascii=False, sort_keys=True) + "\n")
        items, notes = write_judge(args, norm, modules, recs)
        with_model = sum(r["model_hash"] is not None for r in recs)
        new_hashes = len({(r["module"], r["model_hash"]) for r in recs if r["model_hash"] is not None and not r["model_identical"]})
        silicon = sum(silicon_ran(m.original) for m in modules) + sum(silicon_ran(mu) for m in modules for mu in m.sites)
        stats = {"seconds": time.time() - start, "with_model": with_model, "new_hashes": new_hashes, "silicon": silicon, "saved": with_model - new_hashes}
        write_summary(args, modules, recs, items, notes, stats)
    except ScriptError as e:
        print("mechanical.py: script error: %s" % e, file=sys.stderr)
        return 2
    counts = collections.Counter(r["class"] for r in recs)
    print("%d mutant(s), %d Silicon run(s), %.0f s wall clock" % (len(recs), stats["silicon"], stats["seconds"]))
    for k, v in sorted(counts.items()):
        print("  %-45s %d" % (k, v))
    print("results in %s; work files in %s" % (args.results, args.work))
    return 0


if __name__ == "__main__":
    sys.exit(main())
