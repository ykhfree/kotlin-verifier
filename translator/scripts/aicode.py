"""AI-CODE.md stage 1: the harness of the AI-written-code measurement (req-shield coroutine KeyLocalLock).

Usage (ENV is build/kv/aicode-env.json, which `./gradlew aicodeEnv -PaicodeWork=DIR` writes):
  python3 -I aicode.py [--env ENV] [--results DIR] COMMAND ...

Commands, in the order of AI-CODE.md 9:
  sandbox ID --kind template|free [--force]
                                    AI-CODE.md 3: WORK/sandboxes/ID with exactly SPEC.md, KeyLock.kt, HELPERS.md,
                                    compile.sh and (template only) RULES.md. Prints the generation prompt of 4.1 or
                                    4.2 for this sandbox and saves it to WORK/state/ID/prompt.txt. compile.sh keeps its
                                    call count and log in WORK/state/ID. --force builds it again and resets ID's line.
  collect ID --model M --report F   Copies the sandbox's KeyLocalLock.kt, the generator's report F and the prompt to
                                    RESULTS/impl/ID/, the compile.sh log to RESULTS/logs/ID/, and compiles the file
                                    once more (not counted) to record whether it compiles.
  similarity ID...                  AI-CODE.md 4.3: token similarity to the original KeyLocalLock.kt (flag >= 0.9).
  similarity --pair A.kt B.kt       Prints the token similarity of two files.
  tests ID [--shim]                 AI-CODE.md 5.1: KeyLocalLockTest (coroutine) on the implementation, in a fresh
                                    extracted req-shield copy, offline (120 s per test method, see TEST_INIT).
                                    --shim: see TEST_SHIM.
  tool ID                           AI-CODE.md 6.1-6.2: `kv verify` with the coroutine sidecar on a copy root that
                                    holds the implementation. RESULTS/impl/ID/bindings.txt, if present, holds the
                                    binding-name adaptations (see read_bindings).
  hole-task ID [--force]            AI-CODE.md 6.3: WORK/holes/ID/task for a hole writer (template kind, tool FAILED).
                                    Its verify.sh keeps the count, the attempts and the outputs in WORK/holes/ID/harness.
  hole-outcome ID [--report F]      Reads the hole task's calls and records the outcome.
  judge ID VERDICT [--note TEXT]    Records a judge's verdict (AI-CODE.md 5.2): defect-property, defect-outside,
                                    correct or undecided.
  summary                           Writes RESULTS/summary.md: the tables of AI-CODE.md 7.1 and 7.2.

RESULTS (default: ENV "results", the repository's aicode/) holds impl/, logs/, results.jsonl and summary.md. Each
command fills its part of the implementation's line in results.jsonl. WORK (ENV "work") holds the sandboxes, their
compile state, the req-shield copies, the tool roots and the hole tasks.
Exit code: 0 done; 1 the command's precondition does not hold; 2 a script error.
"""
import argparse
import difflib
import importlib.util
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ElementTree

HERE = os.path.dirname(os.path.abspath(__file__))
# Loading mutants.py and mechanical.py must not rewrite the __pycache__ files.
sys.dont_write_bytecode = True


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


mechanical = load("mechanical", os.path.join(HERE, "mechanical.py"))
mutants = mechanical.mutants
ScriptError = mutants.ScriptError


class Precondition(Exception):
    """The command's precondition does not hold (exit 1), e.g. a hole task for a PROVEN implementation."""

# AI-CODE.md 2: the target is the coroutine variant at this req-shield commit.
REQ_SHIELD_COMMIT = "2e792d2"
COROUTINE_DIR = mutants.VARIANT_DIRS["coroutine"]
IMPL_REL = COROUTINE_DIR + "/KeyLocalLock.kt"
KEYLOCK_REL = COROUTINE_DIR + "/KeyLock.kt"
SIDECARS = ("keylocallock.coroutine.kv", "keylocallock.common.kv")
SIDECAR = SIDECARS[0]
HOLE_FILE = SIDECARS[1]
TEST_CLASS = "com.linecorp.cse.reqshield.kotlin.coroutine.KeyLocalLockTest"

COMPILE_BUDGET = 10   # AI-CODE.md 3 item 5
HOLE_BUDGET = 20      # AI-CODE.md 6.3
SIMILARITY_FLAG = 0.9  # AI-CODE.md 4.3
TOOL_TIMEOUT = 900    # seconds per kv verify run (kv's own Silicon budget is 300 s per run)
TEST_TIMEOUT = 1800   # seconds per Gradle test run
KINDS = ("template", "free")
JUDGE_VERDICTS = ("defect-property", "defect-outside", "correct", "undecided")

# The sandbox files (AI-CODE.md 3). RULES.md is given to the template kind only (4.1, 4.2).
SANDBOX_FILES = ("SPEC.md", "KeyLock.kt", "HELPERS.md", "compile.sh")
RULES_FILE = "RULES.md"

# SPEC.md (AI-CODE.md 3 item 1): (file, first line, last line, text the first line starts with). The README section
# "Local lock map size" ends at line 149: line 150 is blank and line 151 starts the next section.
SPEC_PARTS = [
    ("README.md", 104, 105, "- Every lock acquisition carries an ownership token."),
    ("README.md", 113, 149, "### Local lock map size"),
    ("CLAUDE.md", 24, 24, "2. **KeyLock**:"),
]
SECTION_END = ("README.md", 150, 151)

# HELPERS.md (AI-CODE.md 3 item 3): per helper, the lines copied from the sources: (file, [(first, last, starts)]).
TIME_UTILS = "support/src/main/kotlin/com/linecorp/cse/reqshield/support/utils/TimeUtils.kt"
LOCK_LIMIT = "support/src/main/kotlin/com/linecorp/cse/reqshield/support/config/LocalLockLimit.kt"
CONFIG_VALUES = "support/src/main/kotlin/com/linecorp/cse/reqshield/support/constant/ConfigValues.kt"
HELPER_PARTS = [
    ("nowToEpochTime", TIME_UTILS, [(17, 17, "package "), (19, 23, "/**")]),
    ("LocalLockLimit.rejectsNewEntry", LOCK_LIMIT, [(17, 17, "package "), (46, 46, "object LocalLockLimit {"), (76, 86, "    /**")]),
    ("LOCK_MONITOR_INTERVAL_MILLIS", CONFIG_VALUES,
     [(17, 17, "package "), (19, 19, "object ConfigValues {"), (24, 24, "    const val LOCK_MONITOR_INTERVAL_MILLIS")]),
    # The import lines for kotlinx.coroutines and slf4j are those of the original coroutine KeyLocalLock.kt.
    ("Import lines for kotlinx.coroutines and slf4j", IMPL_REL, [(22, 29, "import kotlinx.coroutines.")]),
]

# req-shield's core-kotlin-coroutine module does not depend on Reactor. The verifier's KeyLocalLock classpath also
# serves core-reactor, so the sandbox compile classpath leaves these jars out.
NOT_COROUTINE_JARS = ("reactor-core-", "reactive-streams-")

# The prompt sentence 4.2 removes, and the sentence it appends.
RULES_MENTION = " and RULES.md (rules every implementation must follow)"
FREE_SENTENCE = "Write production-quality code."

# `tests --shim`: KeyLocalLockTest calls two members of the original class that the spec packet does not name
# (`KeyLocalLock.stopMonitoring()` in its @AfterEach and `keyLock.cancel()`). The shim declares them as no-op
# extensions in the test sources. A member with the same name wins over an extension, so for a class that has them
# the shim changes nothing.
TEST_SHIM_REL = "core-kotlin-coroutine/src/test/kotlin/com/linecorp/cse/reqshield/kotlin/coroutine/AicodeTestShim.kt"
TEST_SHIM = """package com.linecorp.cse.reqshield.kotlin.coroutine

// Added by the aicode test runner (--shim). KeyLocalLockTest calls these members of the original class.
// A member with the same name wins over these extensions.
internal fun KeyLocalLock.cancel() {}

internal fun KeyLocalLock.Companion.stopMonitoring() {}
"""

# Gradle init script of the test runner: a hanging implementation fails its test method after 120 s instead of
# blocking the run (JUnit 5.9 timeouts on a separate thread), and the whole test task stops after 20 minutes.
TEST_INIT = """allprojects {
    tasks.withType(Test).configureEach {
        timeout.set(java.time.Duration.ofMinutes(20))
        systemProperty "junit.jupiter.execution.timeout.default", "120 s"
        systemProperty "junit.jupiter.execution.timeout.thread.mode.default", "SEPARATE_THREAD"
    }
}
"""

COMPILE_SH = r"""#!/bin/bash
# Compiles KeyLocalLock.kt together with KeyLock.kt (both in this directory) against the project's compile classpath.
# It only compiles: it does not run tests or any other check. It prints the compiler's diagnostics.
# At most @BUDGET@ calls. The harness keeps the count outside this directory.
DIR="$(cd "$(dirname "$0")" && pwd)"
STATE=@STATE@
BUDGET=@BUDGET@
n=$(( $(cat "$STATE/compile.count" 2>/dev/null || echo 0) + 1 ))
if [ "$n" -gt "$BUDGET" ]; then
  echo "BUDGET EXHAUSTED: all $BUDGET compile.sh calls are used. Stop and report."
  echo "refused $(date +%T): budget exhausted" >> "$STATE/compile.log"
  exit 3
fi
echo "$n" > "$STATE/compile.count"
mkdir -p "$STATE/calls"
cp "$DIR/KeyLocalLock.kt" "$STATE/calls/$n.kt" 2>/dev/null
OUT="$(mktemp -d)"
@COMPILER@ -d "$OUT/classes" "$DIR/KeyLock.kt" "$DIR/KeyLocalLock.kt" > "$OUT/log" 2>&1
code=$?
grep -v '@NOISE@' "$OUT/log" > "$OUT/diagnostics"
cat "$OUT/diagnostics"
if [ "$code" -eq 0 ]; then result="compiles"; else result="does not compile"; fi
echo "compile.sh: KeyLocalLock.kt $result (call $n of $BUDGET used)"
{ echo "call $n $(date +%T) exit=$code"; cat "$OUT/diagnostics"; } >> "$STATE/compile.log"
rm -rf "$OUT"
exit "$code"
"""
# The compiler's warning about the -language-version 2.0 flag of req-shield's build: not about the file.
COMPILER_NOISE = "language version 2.0 is deprecated"

VERIFY_SH = r"""#!/bin/bash
# Checks your edit of the hole MI_INV in keylocallock.common.kv, then runs the verifier on the edited sidecar.
# At most @BUDGET@ calls. The harness keeps the count, a copy of each attempt and each output outside this directory.
TASK="$(cd "$(dirname "$0")" && pwd)"
H=@HARNESS@
KV=@KV@
BUDGET=@BUDGET@
n=$(( $(cat "$H/calls.count" 2>/dev/null || echo 0) + 1 ))
if [ "$n" -gt "$BUDGET" ]; then
  echo "BUDGET EXHAUSTED: all $BUDGET verify.sh calls are used. Stop and report."
  echo "refused $(date +%T): budget exhausted" >> "$H/calls.log"
  exit 5
fi
echo "$n" > "$H/calls.count"
A="$H/attempts/$n"
mkdir -p "$A"
cp "$TASK/@SIDECAR@" "$TASK/@HOLE_FILE@" "$TASK/KeyLocalLock.kt" "$A/" 2>/dev/null
run() {
  for f in @SIDECAR@ KeyLocalLock.kt; do
    if ! cmp -s "$H/original/$f" "$A/$f"; then
      echo "REJECTED: $f was changed. Only the hole text in @HOLE_FILE@ may change."
      return 6
    fi
  done
  if ! "$KV" hole check "$H/original/@HOLE_FILE@" "$A/@HOLE_FILE@"; then
    echo "REJECTED: the edit of @HOLE_FILE@ is not accepted (reasons above)."
    return 6
  fi
  perl -e 'alarm shift; exec @ARGV' @TIMEOUT@ "$KV" verify --root "$H/root" --classpath @CLASSPATH@ --kotlinc @KOTLINC@ \
    --out "$A/model.vpr" "$A/@SIDECAR@"
  code=$?
  [ "$code" = 142 ] && echo "TIME LIMIT: the verifier did not finish in @TIMEOUT@ s."
  return "$code"
}
run > "$A/output.txt" 2>&1
code=$?
echo "$code" > "$A/exit"
cat "$A/output.txt"
echo "call $n $(date +%T) exit=$code" >> "$H/calls.log"
echo "(call $n of $BUDGET used)"
exit "$code"
"""
HOLE_EXITS = {0: "PROVEN", 1: "FAILED", 2: "USAGE ERROR", 3: "INCOMPLETE", 4: "ERROR", 6: "REJECTED", 142: "TIME LIMIT"}

TASK_MD = """# Task: write the inductive invariant MI_INV

`KeyLocalLock.kt` is a Kotlin class. `keylocallock.coroutine.kv` and `keylocallock.common.kv` are its
specification for a verifier (the coroutine file includes the common file). The verifier turns each
`lockMap.compute(key) { ... }` call of the operations `tryLock`, `unLock` and the monitor's `sweep` into one atomic
action, and proves the `ensures`, `ends_only_if`, `unbacked_only_if`, `invariant` and `lemma` properties of the
specification for every reachable state.

`hole MI_INV \"\"\" ... \"\"\"` at the end of `keylocallock.common.kv` is the inductive invariant:
- `init` must establish it (it must hold for the empty map).
- Every action must preserve it, and every action must prove its properties from it.
The current text was written for a different implementation of this class, so it may not fit this one.

Your job: change the hole text so that `./verify.sh` reports `module: PROVEN`.

The hole is Viper text over the vocabulary the specification already uses, for example `S.m`, `has(S, k)`,
`S.m[k].token`, `S.seq`, `tok(t).gIssued` and `forall` with triggers. You may add comments (`//`).

## Rules

1. Edit only the hole string: the text between the triple quotes of `hole MI_INV` in `keylocallock.common.kv`.
   Do not change anything else in any file.
2. Do not use `assume`, `inhale`, `exhale` or `refute`, and do not declare methods, functions, predicates, axioms,
   domains, fields or imports.
3. Check your edit only with `./verify.sh`. It checks rules 1 and 2, then runs the verifier. You have at most
   {budget} calls. Do not run the verifier, Java, Silicon or Z3 any other way.
4. Work only in this directory. Do not read, list or search files outside it. Do not use the web, memory tools
   (such as cross-memory), or other agents.

## Reading the output of verify.sh

- `module: PROVEN`: done.
- An action `FAILED (property)`: a property of the specification fails in that action.
- An action `FAILED (invariant only)`: only the hole fails in that action; no property fails in one step from a
  state that satisfies the hole.

## Report

When `./verify.sh` reports `module: PROVEN`, or when the budget is used up, report:
1. PROVEN or not, and the number of `./verify.sh` calls used.
2. The final hole text.
3. For each call: what failed, and what you changed in response (one line each).
"""


# ---- environment and results ----

class Env:
    """The paths of aicode-env.json, with WORK and RESULTS resolved."""

    def __init__(self, path, results=None):
        try:
            with open(path) as f:
                data = json.load(f)
        except OSError as e:
            raise ScriptError("cannot read %s (run ./gradlew aicodeEnv first): %s" % (path, e))
        for key in ("kv", "kvInstall", "reqShield", "classpath", "kotlinc", "compilerClasspath", "specs", "aiCode", "results", "work"):
            if key not in data:
                raise ScriptError("%s has no %r" % (path, key))
        self.__dict__.update(data)
        self.results = os.path.abspath(results or data["results"])
        self.work = os.path.abspath(data["work"])

    def results_file(self):
        return os.path.join(self.results, "results.jsonl")

    def impl_dir(self, id):
        return os.path.join(self.results, "impl", id)

    def logs_dir(self, id):
        return os.path.join(self.results, "logs", id)

    def sandbox(self, id):
        return os.path.join(self.work, "sandboxes", id)

    def state(self, id):
        return os.path.join(self.work, "state", id)

    def coroutine_classpath(self):
        return [p for p in self.classpath.split(os.pathsep) if p and not os.path.basename(p).startswith(NOT_COROUTINE_JARS)]


def load_results(env):
    out = {}
    try:
        with open(env.results_file()) as f:
            for line in f:
                if line.strip():
                    rec = json.loads(line)
                    out[rec["id"]] = rec
    except FileNotFoundError:
        pass
    return out


def update(env, id, reset=False, **fields):
    """Sets FIELDS of implementation ID in results.jsonl (RESET: drops its other fields first) and recomputes its
    final verdict; returns the record."""
    recs = load_results(env)
    rec = recs.setdefault(id, {"id": id})
    if reset:
        rec.clear()
        rec["id"] = id
    rec.update(fields)
    rec["final_verdict"] = final_verdict(rec)
    os.makedirs(env.results, exist_ok=True)
    with open(env.results_file(), "w") as f:
        for r in recs.values():
            f.write(json.dumps(r, ensure_ascii=False, sort_keys=True) + "\n")
    return rec


def record(env, id):
    rec = load_results(env).get(id)
    if rec is None:
        raise ScriptError("results.jsonl has no implementation %r (run sandbox first)" % id)
    return rec


def aggregate_sub(sub_status):
    """One sub-status for a FAILED run: property if an action fails a property, else the invariant-only kind of its
    actions. A FAILED run without a FAILED action failed at init, a lemma or the probe."""
    subs = set(sub_status.values())
    for s in ("property", "invariant only", "invariant only; property check ERROR"):
        if s in subs:
            return s
    return "init/lemma/probe"


def final_verdict(rec):
    """AI-CODE.md 6.5: the final verdict from the compile result, the tool run and the hole outcome; None = pending."""
    if rec.get("compiled") is False:
        return "stillborn"
    tool = rec.get("tool")
    if not tool:
        return None
    if tool.get("binding_failure"):
        return "binding failure"
    verdict = tool["verdict"]
    if verdict == "PROVEN":
        return "PROVEN"
    if verdict == "INCOMPLETE":
        return "out of scope"
    if verdict != "FAILED":
        return verdict
    if rec.get("kind") == "free":
        return "FAILED (%s)" % aggregate_sub(tool["sub_status"])
    hole = rec.get("hole")
    if not hole:
        return "FAILED (hole pending)"
    if hole["outcome"] == "PROVEN":
        return "PROVEN (new hole)"
    return "FAILED (%s)" % hole["last_sub_status"]


# ---- small helpers ----

def git_show(env, rel):
    """The text of REL in req-shield at REQ_SHIELD_COMMIT (not the working tree)."""
    p = subprocess.run(["git", "-C", env.reqShield, "show", "%s:%s" % (REQ_SHIELD_COMMIT, rel)], capture_output=True, text=True)
    if p.returncode != 0:
        raise ScriptError("git show %s:%s failed: %s" % (REQ_SHIELD_COMMIT, rel, p.stderr.strip()))
    return p.stdout


def lines_of(text, first, last, starts, where):
    lines = text.split("\n")
    if not lines[first - 1].startswith(starts):
        raise ScriptError("%s:%d does not start with %r: %r" % (where, first, starts, lines[first - 1]))
    return lines[first - 1:last]


def write(path, text, executable=False):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)
    if executable:
        os.chmod(path, 0o755)


def read(path):
    with open(path) as f:
        return f.read()


def run(command, timeout, cwd=None):
    """Runs COMMAND; (exit code, stdout, stderr), with exit code None after TIMEOUT seconds."""
    try:
        p = subprocess.run(command, capture_output=True, text=True, timeout=timeout, cwd=cwd)
        return p.returncode, p.stdout, p.stderr
    except subprocess.TimeoutExpired as e:
        # TimeoutExpired holds bytes even with text=True.
        out = e.stdout.decode(errors="replace") if isinstance(e.stdout, bytes) else (e.stdout or "")
        return None, out, "did not finish in %d s" % timeout


def copy_files(sources, dst):
    """Copies the jar files SOURCES into DST (kept if the same size); returns the copies in order."""
    os.makedirs(dst, exist_ok=True)
    out = []
    for src in sources:
        target = os.path.join(dst, os.path.basename(src))
        if not os.path.exists(target) or os.path.getsize(target) != os.path.getsize(src):
            shutil.copy(src, target)
        out.append(target)
    return out


def copy_tree(src, dst):
    shutil.rmtree(dst, ignore_errors=True)
    shutil.copytree(src, dst)


# ---- 1. sandbox ----

def ai_code_texts(env):
    """The generation prompt and the RULES.md text of AI-CODE.md 4.1, verbatim: its two `text` blocks."""
    text = read(env.aiCode)
    section = text[text.index("### 4.1"):text.index("### 4.2")]
    blocks = re.findall(r"```text\n(.*?)```", section, re.S)
    if len(blocks) != 2 or "<SANDBOX>" not in blocks[0] or RULES_MENTION not in blocks[0] or not blocks[1].startswith("The team checks"):
        raise ScriptError("AI-CODE.md 4.1 does not hold the prompt and RULES.md blocks this script expects")
    return blocks[0], blocks[1]


def prompt_for(env, kind, sandbox):
    prompt, _ = ai_code_texts(env)
    if kind == "free":
        # AI-CODE.md 4.2: without the RULES.md mention, and one sentence more at the end.
        prompt = prompt.replace(RULES_MENTION, "").rstrip("\n") + " " + FREE_SENTENCE + "\n"
    return prompt.replace("<SANDBOX>", sandbox)


def spec_md(env):
    texts = {}
    header = ["<!-- SPEC.md: copied verbatim from req-shield at commit %s:" % REQ_SHIELD_COMMIT]
    body = []
    for name, first, last, starts in SPEC_PARTS:
        text = texts.setdefault(name, git_show(env, name))
        span = "line %d" % first if first == last else "lines %d-%d" % (first, last)
        header.append("     %s %s" % (name, span))
        body += ["<!-- %s %s -->" % (name, span)] + lines_of(text, first, last, starts, name) + [""]
    name, blank, heading = SECTION_END
    lines = texts[name].split("\n")
    if lines[blank - 1].strip() or not lines[heading - 1].startswith("### "):
        raise ScriptError("%s: the section that ends at line %d does not end there" % (name, blank - 1))
    return "\n".join([header[0]] + header[1:-1] + [header[-1] + " -->", ""] + body)


def helpers_md(env):
    header = ["<!-- HELPERS.md: signatures and KDoc copied verbatim from req-shield at commit %s; bodies are left out." % REQ_SHIELD_COMMIT]
    body = ["# Functions and constants you may call", "",
            "Besides these, the compile classpath holds kotlin-stdlib 2.0.21, kotlinx-coroutines-core 1.8.1 and slf4j-api 1.7.36.", ""]
    for title, rel, parts in HELPER_PARTS:
        text = git_show(env, rel)
        header.append("     %s lines %s" % (rel, ", ".join(str(a) if a == b else "%d-%d" % (a, b) for a, b, _ in parts)))
        code = []
        for first, last, starts in parts:
            chunk = lines_of(text, first, last, starts, rel)
            if chunk[-1].lstrip().startswith("fun ") and chunk[-1].endswith(" {"):
                chunk[-1] = chunk[-1][:-2]  # a function signature without its block body
            code += chunk + ([""] if starts == "package " else [])
        # Close the objects the signature parts opened.
        code += ["}"] * sum(1 for _, _, s in parts if s.startswith("object "))
        body += ["## " + title, "", "```kotlin"] + code + ["```", ""]
    header[-1] += " -->"
    return "\n".join(header + [""] + body)


def compiler_prefix(env):
    """The command that compiles Kotlin files against the coroutine module's classpath (to append -d DIR FILES)."""
    lib = os.path.join(env.work, "lib")
    compiler = copy_files([p for p in env.compilerClasspath.split(os.pathsep) if p], os.path.join(lib, "compiler"))
    classpath = copy_files(env.coroutine_classpath(), os.path.join(lib, "classpath"))
    return (["java", "-cp", os.pathsep.join(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
             "-classpath", os.pathsep.join(classpath)] + env.kotlinc.split())


def compile_files(env, files):
    """Compiles FILES (not counted against any budget); (compiles, diagnostics)."""
    out = os.path.join(env.work, "compile-tmp", str(os.getpid()))
    shutil.rmtree(out, ignore_errors=True)
    code, stdout, stderr = run(compiler_prefix(env) + ["-d", out] + files, 600)
    shutil.rmtree(out, ignore_errors=True)
    diagnostics = "\n".join(l for l in (stdout + stderr).splitlines() if COMPILER_NOISE not in l)
    return code == 0, diagnostics


def cmd_sandbox(env, args):
    box, state = env.sandbox(args.id), env.state(args.id)
    if os.path.exists(box) and not args.force:
        raise Precondition("%s exists; pass --force to build it again (its state and count are reset)" % box)
    shutil.rmtree(box, ignore_errors=True)
    shutil.rmtree(state, ignore_errors=True)
    os.makedirs(state)
    compile_sh = (COMPILE_SH.replace("@STATE@", shlex.quote(state)).replace("@BUDGET@", str(COMPILE_BUDGET))
                  .replace("@COMPILER@", shlex.join(compiler_prefix(env))).replace("@NOISE@", COMPILER_NOISE))
    write(os.path.join(box, "SPEC.md"), spec_md(env))
    write(os.path.join(box, "KeyLock.kt"), git_show(env, KEYLOCK_REL))
    write(os.path.join(box, "HELPERS.md"), helpers_md(env))
    write(os.path.join(box, "compile.sh"), compile_sh, executable=True)
    if args.kind == "template":
        write(os.path.join(box, RULES_FILE), ai_code_texts(env)[1])
    prompt = prompt_for(env, args.kind, box)
    write(os.path.join(state, "prompt.txt"), prompt)
    # A new sandbox is a new generation: the results of an earlier one with this ID no longer hold.
    update(env, args.id, reset=True, kind=args.kind)
    print("sandbox %s (%s): %s" % (args.id, args.kind, box))
    print("prompt (also in %s):" % os.path.join(state, "prompt.txt"))
    print(prompt, end="")
    return 0


# ---- 2. collect ----

def cmd_collect(env, args):
    rec = record(env, args.id)
    box, state = env.sandbox(args.id), env.state(args.id)
    impl = os.path.join(box, "KeyLocalLock.kt")
    out, logs = env.impl_dir(args.id), env.logs_dir(args.id)
    os.makedirs(out, exist_ok=True)
    write(os.path.join(out, "report.md"), read(args.report))
    shutil.copy(os.path.join(state, "prompt.txt"), os.path.join(out, "prompt.txt"))
    calls = int(read(os.path.join(state, "compile.count")).strip()) if os.path.exists(os.path.join(state, "compile.count")) else 0
    shutil.rmtree(os.path.join(logs, "compile"), ignore_errors=True)
    if os.path.isdir(os.path.join(state, "calls")):
        shutil.copytree(os.path.join(state, "calls"), os.path.join(logs, "compile", "calls"))
    if os.path.exists(os.path.join(state, "compile.log")):
        write(os.path.join(logs, "compile", "compile.log"), read(os.path.join(state, "compile.log")).replace(box + os.sep, ""))
    expected = set(SANDBOX_FILES) | ({RULES_FILE} if rec["kind"] == "template" else set()) | {"KeyLocalLock.kt"}
    extra = sorted(set(os.listdir(box)) - expected)
    keylock_changed = read(os.path.join(box, "KeyLock.kt")) != git_show(env, KEYLOCK_REL)
    if not os.path.exists(impl):
        update(env, args.id, model=args.model, compiled=False, compile_calls=calls, compile_message="no KeyLocalLock.kt in the sandbox",
               keylock_changed=keylock_changed, sandbox_extra_files=extra)
        print("%s: no KeyLocalLock.kt: stillborn" % args.id)
        return 0
    shutil.copy(impl, os.path.join(out, "KeyLocalLock.kt"))
    # The final compile uses the original KeyLock.kt, as the test and tool runners do.
    keylock = os.path.join(env.work, "compile-tmp", "%s-KeyLock.kt" % args.id)
    write(keylock, git_show(env, KEYLOCK_REL))
    compiled, diagnostics = compile_files(env, [keylock, os.path.join(out, "KeyLocalLock.kt")])
    write(os.path.join(logs, "final-compile.txt"), diagnostics.replace(out + os.sep, "").replace(os.path.dirname(keylock) + os.sep, "") + "\n")
    update(env, args.id, model=args.model, compiled=compiled, compile_calls=calls, keylock_changed=keylock_changed, sandbox_extra_files=extra)
    print("%s: model %s, %s, %d compile.sh call(s)%s%s" % (args.id, args.model, "compiles" if compiled else "does not compile", calls,
                                                         ", KeyLock.kt changed" if keylock_changed else "", ", extra files %s" % extra if extra else ""))
    return 0


def impl_file(env, id):
    path = os.path.join(env.impl_dir(id), "KeyLocalLock.kt")
    if not os.path.exists(path):
        raise ScriptError("no %s (run collect first)" % path)
    return path


# ---- 3. similarity ----

WORD = re.compile(r"`[^`\n]+`|[A-Za-z_][A-Za-z0-9_]*")
NUMBER = re.compile(r"0[xX][0-9a-fA-F_]+[uUL]*|0[bB][01_]+[uUL]*|(?:\d[\d_]*)?\.?\d[\d_]*(?:[eE][+-]?\d+)?[fFdDuUL]*")
OPERATOR = re.compile(r"\?\.|\?:|::|->|\.\.<|\.\.|===|!==|==|!=|<=|>=|&&|\|\||\+\+|--|\+=|-=|\*=|/=|%=|!!|[^\sA-Za-z0-9_]")


def string_end(text, i):
    """The offset after the string literal that starts at I (a quoted or raw string, with templates)."""
    raw = text.startswith('"""', i)
    j = i + (3 if raw else 1)
    while j < len(text):
        if raw and text.startswith('"""', j):
            j += 3
            while j < len(text) and text[j] == '"':
                j += 1
            return j
        c = text[j]
        if not raw and c == "\\":
            j += 2
        elif not raw and c == '"':
            return j + 1
        elif text.startswith("${", j):
            depth, j = 1, j + 2
            while j < len(text) and depth:
                if text[j] == '"':
                    j = string_end(text, j)
                    continue
                depth += {"{": 1, "}": -1}.get(text[j], 0)
                j += 1
        else:
            j += 1
    return j


def kotlin_tokens(text):
    """The tokens of Kotlin source TEXT without comments and whitespace; a string literal is one token."""
    out, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        if c.isspace():
            i += 1
        elif text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif text.startswith("/*", i):
            depth, i = 1, i + 2  # Kotlin block comments nest
            while i < n and depth:
                if text.startswith("/*", i):
                    depth, i = depth + 1, i + 2
                elif text.startswith("*/", i):
                    depth, i = depth - 1, i + 2
                else:
                    i += 1
        elif c == '"':
            j = string_end(text, i)
            out.append(text[i:j])
            i = j
        elif c == "'":
            j = i + 1
            while j < n and text[j] != "'":
                j += 2 if text[j] == "\\" else 1
            out.append(text[i:j + 1])
            i = j + 1
        else:
            m = WORD.match(text, i) or (NUMBER.match(text, i) if c.isdigit() or c == "." and i + 1 < n and text[i + 1].isdigit() else None) or OPERATOR.match(text, i)
            out.append(m.group(0))
            i = m.end()
    return out


def similarity(a, b):
    """difflib's ratio over the token lists of two Kotlin texts (AI-CODE.md 4.3). autojunk would drop frequent
    tokens such as `.` and `(` from the matching, so it is off."""
    return difflib.SequenceMatcher(None, kotlin_tokens(a), kotlin_tokens(b), autojunk=False).ratio()


def cmd_similarity(env, args):
    if args.pair:
        print("%.4f" % similarity(read(args.pair[0]), read(args.pair[1])))
        return 0
    if not args.ids:
        raise ScriptError("similarity needs IDs or --pair A B")
    original = git_show(env, IMPL_REL)
    for id in args.ids:
        record(env, id)
        value = round(similarity(read(impl_file(env, id)), original), 4)
        update(env, id, similarity=value, similarity_flag=value >= SIMILARITY_FLAG)
        print("%s: similarity %.4f%s" % (id, value, "  FLAG (>= %.1f)" % SIMILARITY_FLAG if value >= SIMILARITY_FLAG else ""))
    return 0


# ---- 4. tests ----

def extract_req_shield(env, dst):
    shutil.rmtree(dst, ignore_errors=True)
    os.makedirs(dst)
    archive = subprocess.Popen(["git", "-C", env.reqShield, "archive", REQ_SHIELD_COMMIT], stdout=subprocess.PIPE)
    tar = subprocess.run(["tar", "-x", "-C", dst], stdin=archive.stdout, capture_output=True, text=True)
    archive.stdout.close()
    if archive.wait() != 0 or tar.returncode != 0:
        raise ScriptError("cannot extract req-shield %s into %s: %s" % (REQ_SHIELD_COMMIT, dst, tar.stderr))


def parse_junit(path):
    """Test method -> {result: passed|failed|skipped, seconds, message (failed only)} from a JUnit XML report."""
    out = {}
    for case in ElementTree.parse(path).getroot().iter("testcase"):
        name = case.get("name").removesuffix("()")
        problem = case.find("failure")
        if problem is None:
            problem = case.find("error")
        entry = {"result": "passed", "seconds": float(case.get("time") or 0)}
        if problem is not None:
            first = (problem.get("message") or problem.text or "").strip().splitlines()
            entry["result"] = "failed"
            entry["message"] = first[0][:300] if first else ""
        elif case.find("skipped") is not None:
            entry["result"] = "skipped"
        out[name] = entry
    return out


def gradle_failure(log):
    """Why a Gradle run produced no test report, and the compiler errors it printed."""
    errors = [l[3:].strip() for l in log.splitlines() if l.startswith("e: ")]
    if ":core-kotlin-coroutine:compileKotlin FAILED" in log:
        return "main compile failed", errors
    if ":core-kotlin-coroutine:compileTestKotlin FAILED" in log:
        return "test compile failed", errors
    return "gradle failed", errors


def cmd_tests(env, args):
    rec = record(env, args.id)
    if rec.get("compiled") is False:
        raise Precondition("%s does not compile; there is nothing to test" % args.id)
    impl = impl_file(env, args.id)
    base = os.path.join(env.work, "tests", args.id)
    copy = os.path.join(base, "req-shield")
    extract_req_shield(env, copy)
    shutil.copy(impl, os.path.join(copy, IMPL_REL))
    if args.shim:
        write(os.path.join(copy, TEST_SHIM_REL), TEST_SHIM)
    init = os.path.join(base, "timeouts.gradle")
    write(init, TEST_INIT)
    command = ["./gradlew", "--offline", "--console=plain", "-I", init, ":core-kotlin-coroutine:test", "--tests", TEST_CLASS]
    start = time.time()
    code, out, err = run(command, TEST_TIMEOUT, cwd=copy)
    seconds = round(time.time() - start, 1)
    log = (out + err).replace(copy + os.sep, "")
    logs = env.logs_dir(args.id)
    write(os.path.join(logs, "tests.log"), log)
    xml = os.path.join(copy, "core-kotlin-coroutine/build/test-results/test", "TEST-%s.xml" % TEST_CLASS)
    result = {"shim": args.shim, "seconds": seconds, "gradle_exit": code}
    if os.path.exists(xml):
        shutil.copy(xml, os.path.join(logs, "tests.xml"))
        methods = parse_junit(xml)
        result.update(status="ran", methods=methods, total=len(methods),
                      passed=sum(1 for m in methods.values() if m["result"] == "passed"),
                      failed=sum(1 for m in methods.values() if m["result"] == "failed"))
    else:
        status, errors = ("timeout", []) if code is None else gradle_failure(log)
        result.update(status=status, errors=errors)
    update(env, args.id, tests=result)
    if result["status"] == "ran":
        print("%s: %d/%d tests passed in %.1f s%s" % (args.id, result["passed"], result["total"], seconds, " (shim)" if args.shim else ""))
        for name, m in result["methods"].items():
            if m["result"] != "passed":
                print("  %s %s: %s" % (m["result"].upper(), name, m.get("message", "")))
    else:
        print("%s: %s after %.1f s%s" % (args.id, result["status"], seconds, "".join("\n  " + e for e in result["errors"])))
    return 0


# ---- 5. tool ----

LOCAL = r"(free\s+%s(?:\s*:\s*\w+)?\s*=\s*local\s+)(\w+)"
SOURCE = re.compile(r'^source\s+"([^"]+)"', re.M)


def read_bindings(path):
    """The binding-name adaptations of AI-CODE.md 6.1, one per line (`#` starts a comment):
        local OPERATION FREE NAME   in operation OPERATION, `free FREE = local X` becomes `free FREE = local NAME`
        source OLD NEW              `source "OLD"` becomes `source "NEW"`
    Nothing else of the sidecar may change (properties, permission clauses, issue rules, the hole)."""
    out = []
    for n, line in enumerate(read(path).splitlines(), 1):
        words = line.split("#", 1)[0].split()
        if not words:
            continue
        if (words[0] == "local" and len(words) == 4) or (words[0] == "source" and len(words) == 3):
            out.append(words)
        else:
            raise ScriptError("%s:%d: an adaptation is 'local OPERATION FREE NAME' or 'source OLD NEW': %r" % (path, n, line))
    return out


def operation_span(text, name):
    """The offsets of the block of `operation NAME = ... { ... }` in TEXT (it ends at a `}` that starts a line)."""
    m = re.search(r"(?m)^operation\s+%s\s*=" % re.escape(name), text)
    if not m:
        return None
    end = text.find("\n}", m.start())
    return m.start(), (len(text) if end < 0 else end + 2)


def adapt(texts, bindings):
    """TEXTS (sidecar file name -> text) with BINDINGS applied; each must change exactly one place."""
    texts = dict(texts)
    for b in bindings:
        hits = []
        for name, text in texts.items():
            if b[0] == "local":
                span = operation_span(text, b[1])
                if span:
                    block = text[span[0]:span[1]]
                    found = list(re.finditer(LOCAL % re.escape(b[2]), block))
                    hits += [(name, span[0] + f.start(2), span[0] + f.end(2)) for f in found]
            else:
                hits += [(name, f.start(1), f.end(1)) for f in SOURCE.finditer(text) if f.group(1) == b[1]]
        if len(hits) != 1:
            raise ScriptError("adaptation %r matches %d places in the sidecar (must be 1)" % (" ".join(b), len(hits)))
        name, start, end = hits[0]
        texts[name] = texts[name][:start] + (b[3] if b[0] == "local" else b[2]) + texts[name][end:]
    return texts


def parse_tool(code, out, err, base):
    """The tool record of one kv verify run (AI-CODE.md 6.1-6.2)."""
    def rel(text):
        return text.replace(base + os.sep, "")
    rec = {"exit": code, "verdict": "ERROR (timeout)" if code is None else mutants.VERDICTS.get(code, "exit %s" % code),
           "pass1": {}, "pass2": {}, "sub_status": {}, "failed_other": [], "incomplete": [], "binding_failure": None,
           "compile_failure": None, "error": None}
    if not out:
        text = rel(mechanical.stderr_text(err))
        if "kv: compilation failed" in err:
            rec["compile_failure"] = text
        elif code == 4:
            rec["binding_failure"] = [l for l in text.splitlines() if "error[" in l] or [text]
        else:
            rec["error"] = text or "exit %s" % code
        return rec
    try:
        rows, others, checks = mutants.parse_report(out)
    except ScriptError as e:
        rec["error"] = "the report cannot be read: %s" % e
        return rec
    rec["pass1"] = {a: sorted(ids) for a, (v, ids) in rows.items() if v == "FAILED"}
    rec["sub_status"] = {a: s for a, (s, _) in checks.items() if rows[a][0] == "FAILED"}
    rec["pass2"] = {a: sorted(ids) for a, (_, ids) in checks.items() if ids}
    rec["failed_other"] = [" ".join(o[:-1]) for o in others if o[-1] == "FAILED"]
    lines = out.splitlines()
    for i, line in enumerate(lines):
        m = mutants.ROW.match(line)
        if m and m.group(3) == "UNSUPPORTED":
            note = lines[i + 1].strip() if i + 1 < len(lines) and lines[i + 1].startswith("      ") else ""
            rec["incomplete"].append("%s: %s" % (m.group(1) or m.group(2), note))
    unsupported = mechanical.unsupported(out)
    if unsupported and unsupported != "none":
        rec["incomplete"] += [u for u in unsupported.split("; ") if u not in rec["incomplete"]]
    if code == 4:
        rec["error"] = rel(mechanical.report_message((code, out, err)))
    return rec


def cmd_tool(env, args):
    rec = record(env, args.id)
    if rec.get("compiled") is False:
        raise Precondition("%s does not compile; there is nothing to verify" % args.id)
    impl = impl_file(env, args.id)
    base = os.path.join(env.work, "tool", args.id)
    shutil.rmtree(base, ignore_errors=True)
    bindings_path = os.path.join(env.impl_dir(args.id), "bindings.txt")
    bindings = read_bindings(bindings_path) if os.path.exists(bindings_path) else []
    texts = adapt({n: read(os.path.join(env.specs, n)) for n in SIDECARS}, bindings)
    spec = os.path.join(base, "spec")
    for name, text in texts.items():
        write(os.path.join(spec, name), text)
    # The implementation goes where the (adapted) first `source` names it, KeyLock.kt where the second does.
    original_sources = SOURCE.findall(read(os.path.join(env.specs, SIDECAR)))
    sources = SOURCE.findall(texts[SIDECAR])
    if original_sources[:2] != [IMPL_REL, KEYLOCK_REL] or len(sources) != len(original_sources):
        raise ScriptError("the coroutine sidecar's source lines are not the expected KeyLocalLock.kt and KeyLock.kt")
    root = os.path.join(base, "root")
    mutants.copy_sources(mutants.SUITES["keylocallock"], env.reqShield, root, "coroutine")
    os.remove(os.path.join(root, IMPL_REL))
    if sources[1] != KEYLOCK_REL:
        os.makedirs(os.path.dirname(os.path.join(root, sources[1])), exist_ok=True)
        shutil.move(os.path.join(root, KEYLOCK_REL), os.path.join(root, sources[1]))
    os.makedirs(os.path.dirname(os.path.join(root, sources[0])), exist_ok=True)
    shutil.copy(impl, os.path.join(root, sources[0]))
    command = mutants.kv_command(env.kv, "verify", root, env.classpath, env.kotlinc, os.path.join(base, "out", "model.vpr"), os.path.join(spec, SIDECAR))
    start = time.time()
    code, out, err = run(command, TOOL_TIMEOUT)
    result = parse_tool(code, out, err, base)
    result["seconds"] = round(time.time() - start, 1)
    result["adaptations"] = [" ".join(b) for b in bindings]
    logs = env.logs_dir(args.id)
    write(os.path.join(logs, "tool.report"), (out + ("\n--- stderr ---\n" + err if err.strip() else "")).replace(base + os.sep, ""))
    shutil.rmtree(os.path.join(logs, "tool-spec"), ignore_errors=True)
    if bindings:
        shutil.copytree(spec, os.path.join(logs, "tool-spec"))
    fields = {"tool": result, "binding_adaptations": len(bindings)}
    previous = rec.get("tool")
    if bindings and previous and not previous.get("adaptations"):
        fields["tool_unadapted"] = previous  # the binding failure that the adaptations answer
    update(env, args.id, **fields)
    what = result["verdict"]
    if result["binding_failure"]:
        what = "binding failure: " + "; ".join(result["binding_failure"])
    elif result["compile_failure"]:
        what = "compile failure under kv: " + result["compile_failure"]
    elif result["sub_status"]:
        what += " " + ", ".join("%s (%s)" % kv for kv in sorted(result["sub_status"].items()))
    print("%s: %s in %.1f s, %d adaptation(s)%s" % (args.id, what, result["seconds"], len(bindings),
                                                    "; INCOMPLETE: " + "; ".join(result["incomplete"]) if result["incomplete"] else ""))
    return 0


# ---- 6. hole task ----

def kv_copy(env):
    """A copy of the kv install under WORK, so that a hole task names no path in this repository."""
    dst = os.path.join(env.work, "kv")
    jar = os.path.join("lib", "translator.jar")
    if not (os.path.exists(os.path.join(dst, jar)) and read_bytes(os.path.join(dst, jar)) == read_bytes(os.path.join(env.kvInstall, jar))):
        copy_tree(env.kvInstall, dst)
    return os.path.join(dst, "bin", "kv")


def read_bytes(path):
    with open(path, "rb") as f:
        return f.read()


def cmd_hole_task(env, args):
    rec = record(env, args.id)
    if rec.get("kind") == "free":
        raise Precondition("%s is a free implementation: AI-CODE.md 4.2 gives it no hole stage" % args.id)
    tool = rec.get("tool")
    if not tool or tool["verdict"] != "FAILED":
        raise Precondition("%s: the tool verdict is %s, not FAILED: no hole task (AI-CODE.md 6.3)" % (args.id, tool and tool["verdict"]))
    base = os.path.join(env.work, "holes", args.id)
    harness, task = os.path.join(base, "harness"), os.path.join(base, "task")
    if os.path.exists(os.path.join(harness, "calls.count")) and not args.force:
        raise Precondition("%s has calls already; pass --force to build it again (its calls are dropped)" % base)
    shutil.rmtree(base, ignore_errors=True)
    tool_base = os.path.join(env.work, "tool", args.id)
    copy_tree(os.path.join(tool_base, "root"), os.path.join(harness, "root"))
    for name in SIDECARS:
        for d in (os.path.join(harness, "original"), task):
            write(os.path.join(d, name), read(os.path.join(tool_base, "spec", name)))
    for d in (os.path.join(harness, "original"), task):
        shutil.copy(impl_file(env, args.id), os.path.join(d, "KeyLocalLock.kt"))
    q = shlex.quote
    # The verifier's classpath, copied under WORK like kv itself.
    classpath = os.pathsep.join(copy_files([p for p in env.classpath.split(os.pathsep) if p], os.path.join(env.work, "lib", "verify-classpath")))
    verify_sh = (VERIFY_SH.replace("@HARNESS@", q(harness)).replace("@KV@", q(kv_copy(env))).replace("@BUDGET@", str(HOLE_BUDGET))
                 .replace("@CLASSPATH@", q(classpath)).replace("@KOTLINC@", q(env.kotlinc)).replace("@TIMEOUT@", str(TOOL_TIMEOUT))
                 .replace("@SIDECAR@", SIDECAR).replace("@HOLE_FILE@", HOLE_FILE))
    write(os.path.join(task, "verify.sh"), verify_sh, executable=True)
    write(os.path.join(task, "TASK.md"), TASK_MD.replace("{budget}", str(HOLE_BUDGET)))
    update(env, args.id, hole=None)
    print("hole task %s: %s" % (args.id, task))
    return 0


HOLE_TEXT = re.compile(r'hole\s+MI_INV\s+"""(.*?)"""', re.S)


def cmd_hole_outcome(env, args):
    rec = record(env, args.id)
    harness = os.path.join(env.work, "holes", args.id, "harness")
    if not os.path.isdir(harness):
        raise ScriptError("no hole task for %s (run hole-task first)" % args.id)
    count_file = os.path.join(harness, "calls.count")
    used = int(read(count_file).strip()) if os.path.exists(count_file) else 0
    calls = []
    for n in range(1, used + 1):
        a = os.path.join(harness, "attempts", str(n))
        code = int(read(os.path.join(a, "exit")).strip()) if os.path.exists(os.path.join(a, "exit")) else None
        output = read(os.path.join(a, "output.txt")) if os.path.exists(os.path.join(a, "output.txt")) else ""
        status = HOLE_EXITS.get(code, "exit %s" % code)
        sub = parse_tool(code, output, "", harness)["sub_status"] if status == "FAILED" else {}
        calls.append({"call": n, "status": status, "sub_status": sub})
    proven = [c for c in calls if c["status"] == "PROVEN"]
    hole = {"calls": used, "budget": HOLE_BUDGET, "statuses": [c["status"] for c in calls]}
    if proven:
        n = proven[-1]["call"]
        m = HOLE_TEXT.search(read(os.path.join(harness, "attempts", str(n), HOLE_FILE)))
        hole.update(outcome="PROVEN", proven_call=n, hole_text=m.group(1) if m else None)
    else:
        # The last call that ran the verifier to FAILED; without one, the tool's own run.
        failed = [c for c in calls if c["status"] == "FAILED"] or [rec["tool"]]
        hole.update(outcome="FAILED", last_status=calls[-1]["status"] if calls else None, last_sub_status_by_action=failed[-1]["sub_status"],
                    last_sub_status=aggregate_sub(failed[-1]["sub_status"]))
    logs = os.path.join(env.logs_dir(args.id), "hole")
    shutil.rmtree(logs, ignore_errors=True)
    os.makedirs(logs)
    if os.path.exists(os.path.join(harness, "calls.log")):
        shutil.copy(os.path.join(harness, "calls.log"), logs)
    for c in calls:
        a, dst = os.path.join(harness, "attempts", str(c["call"])), os.path.join(logs, "attempts", str(c["call"]))
        os.makedirs(dst)
        for name in ("output.txt", "exit", HOLE_FILE):
            if os.path.exists(os.path.join(a, name)):
                write(os.path.join(dst, name), read(os.path.join(a, name)).replace(harness + os.sep, "<harness>/"))
    if args.report:
        write(os.path.join(env.impl_dir(args.id), "hole-report.md"), read(args.report))
    update(env, args.id, hole=hole)
    if hole["outcome"] == "PROVEN":
        print("%s: hole PROVEN at call %d of %d used" % (args.id, hole["proven_call"], used))
    else:
        print("%s: hole FAILED after %d call(s); last call %s; last sub-status %s" % (args.id, used, hole["last_status"], hole["last_sub_status"]))
    return 0


# ---- judges and summary ----

def cmd_judge(env, args):
    record(env, args.id)
    update(env, args.id, judge={"verdict": args.verdict, "note": args.note})
    print("%s: judge %s" % (args.id, args.verdict))
    return 0


def tests_text(r):
    t = r.get("tests")
    if not t:
        return "-"
    if t["status"] != "ran":
        return t["status"]
    return "%d/%d%s" % (t["passed"], t["total"], " (shim)" if t.get("shim") else "")


def tool_text(r):
    t = r.get("tool")
    if not t:
        return "-"
    if t["binding_failure"]:
        return "binding failure"
    subs = sorted(set(t["sub_status"].values()))
    return t["verdict"] + (" (%s)" % ", ".join(subs) if subs else "")


def hole_text(r):
    h = r.get("hole")
    return "-" if not h else "%s, %d call(s)" % (h["outcome"], h["calls"])


def column(final):
    """The AI-CODE.md 7.2 column of a final verdict, or None outside the table."""
    if final in ("PROVEN", "PROVEN (new hole)"):
        return "PROVEN"
    if final == "FAILED (property)":
        return "FAILED (property)"
    if final and final.startswith("FAILED (invariant only"):
        return "FAILED (invariant only)"
    if final and final.startswith("FAILED"):
        return "FAILED (pending)"
    return None


def render_summary(recs):
    template = [r for r in recs if r.get("kind") == "template"]
    free = [r for r in recs if r.get("kind") == "free"]
    out = ["# AI가 쓴 코드: 집계 (AI-CODE.md 7)", "", "`translator/scripts/aicode.py summary`가 `results.jsonl`에서 만든다. 손으로 고치지 않는다.", "",
           "## 구현별", "",
           "| ID | 모델 | 종류 | 컴파일 | compile.sh 호출 | 유사도 | 테스트 | 도구 (처음) | 묶기 이름 바꿈 | hole | 최종 | 판정자 |",
           "|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for r in recs:
        sim = r.get("similarity")
        out.append("| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |" % (
            r["id"], r.get("model") or "-", r.get("kind") or "-", {True: "예", False: "아니오"}.get(r.get("compiled"), "-"),
            r.get("compile_calls", "-"), "-" if sim is None else "%.3f%s" % (sim, " ⚑" if r.get("similarity_flag") else ""),
            tests_text(r), tool_text(r), r.get("binding_adaptations", "-"), hole_text(r), r.get("final_verdict") or "대기",
            (r.get("judge") or {}).get("verdict") or "-"))
    sims = sorted(r["similarity"] for r in recs if r.get("similarity") is not None)
    out += ["", "## 오염 검사 (4.3)", "",
            "- 유사도 분포: " + (", ".join("%.3f" % s for s in sims) if sims else "없음"),
            "- 0.9 이상: %d개 (%s)" % (sum(1 for s in sims if s >= SIMILARITY_FLAG), ", ".join(r["id"] for r in recs if r.get("similarity_flag")) or "없음"),
            "", "## 7.1 범위", "", "| 항목 | 본 실험 (template) | 범위 실험 (free) |", "|---|---|---|"]

    def stillborn(rs):
        return "%d개 (%s)" % (len([r for r in rs if r.get("compiled") is False]), ", ".join(r["id"] for r in rs if r.get("compiled") is False) or "-")

    def binding(rs):
        items = []
        for r in rs:
            first = r.get("tool_unadapted") or r.get("tool") or {}
            if first.get("binding_failure"):
                items.append("%s: %s; 이름 바꿈 %d개" % (r["id"], " / ".join(first["binding_failure"]), r.get("binding_adaptations", 0)))
        return "<br>".join(items) or "없음"

    def incomplete(rs):
        items = ["%s: %s" % (r["id"], "; ".join(r["tool"]["incomplete"])) for r in rs if r.get("tool") and r["tool"]["verdict"] == "INCOMPLETE"]
        return "<br>".join(items) or "없음"

    out += ["| 사산 (컴파일 실패) | %s | %s |" % (stillborn(template), stillborn(free)),
            "| 묶기 실패 | %s | %s |" % (binding(template), binding(free)),
            "| 범위 밖 (INCOMPLETE) | %s | %s |" % (incomplete(template), incomplete(free)),
            "", "### 자유 구현", "", "| ID | 컴파일 | 묶기에서 깨지는 것 | 이름만 바꾸면 돌아가는가 |", "|---|---|---|---|"]
    for r in free:
        first = r.get("tool_unadapted") or r.get("tool") or {}
        breaks = "; ".join(first.get("binding_failure") or first.get("incomplete") or []) or ("-" if not first else "없음")
        if not r.get("tool"):
            runs = "-"
        elif r["tool"].get("binding_failure"):
            runs = "아니오 (이름 바꿈 %d개 뒤에도 묶기 실패)" % r.get("binding_adaptations", 0)
        else:
            runs = "예, %s (이름 바꿈 %d개)" % (tool_text(r), r.get("binding_adaptations", 0))
        out.append("| %s | %s | %s | %s |" % (r["id"], {True: "예", False: "아니오"}.get(r.get("compiled"), "-"), breaks, runs))
    cols = ["PROVEN", "FAILED (property)", "FAILED (invariant only)", "FAILED (pending)"]
    rows = [("defect-property", "결함 (sidecar 성질)"), ("defect-outside", "결함 (sidecar 밖)"), ("correct", "정상"), ("undecided", "미결"), (None, "판정 대기")]
    out += ["", "## 7.2 정확성 (본 실험 중 범위 안)", "",
            "칸의 ID는 그 구현이다. FAILED (invariant only)는 hole을 다시 써도 증명되지 않은 것이다. FAILED (pending)는 hole 단계가 남은 것이다.", "",
            "| 정답 \\ 도구 최종 | " + " | ".join(cols) + " |", "|---" * (len(cols) + 1) + "|"]
    in_scope = [r for r in template if column(r.get("final_verdict"))]
    for key, label in rows:
        cells = []
        for c in cols:
            ids = [r["id"] for r in in_scope if (r.get("judge") or {}).get("verdict") == key and column(r["final_verdict"]) == c]
            cells.append(", ".join(ids) or "-")
        out.append("| %s | %s |" % (label, " | ".join(cells)))
    out += ["", "범위 밖이거나 대기 중인 본 실험 구현: %s" % (", ".join("%s (%s)" % (r["id"], r.get("final_verdict") or "대기") for r in template if not column(r.get("final_verdict"))) or "없음"), ""]
    return "\n".join(out)


def cmd_summary(env, args):
    text = render_summary(list(load_results(env).values()))
    write(os.path.join(env.results, "summary.md"), text)
    print(text, end="")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--env", default=os.path.join(HERE, "..", "build", "kv", "aicode-env.json"))
    ap.add_argument("--results", help="results directory (default: the env's, the repository's aicode/)")
    sub = ap.add_subparsers(dest="command", required=True)
    p = sub.add_parser("sandbox")
    p.add_argument("id")
    p.add_argument("--kind", choices=KINDS, required=True)
    p.add_argument("--force", action="store_true")
    p = sub.add_parser("collect")
    p.add_argument("id")
    p.add_argument("--model", required=True)
    p.add_argument("--report", required=True, help="a file with the generator's final report")
    p = sub.add_parser("similarity")
    p.add_argument("ids", nargs="*")
    p.add_argument("--pair", nargs=2, metavar=("A", "B"))
    p = sub.add_parser("tests")
    p.add_argument("id")
    p.add_argument("--shim", action="store_true", help="no-op cancel() and stopMonitoring() extensions in the test sources")
    p = sub.add_parser("tool")
    p.add_argument("id")
    p = sub.add_parser("hole-task")
    p.add_argument("id")
    p.add_argument("--force", action="store_true")
    p = sub.add_parser("hole-outcome")
    p.add_argument("id")
    p.add_argument("--report", help="a file with the hole writer's final report")
    p = sub.add_parser("judge")
    p.add_argument("id")
    p.add_argument("verdict", choices=JUDGE_VERDICTS)
    p.add_argument("--note")
    sub.add_parser("summary")
    args = ap.parse_args()
    commands = {"sandbox": cmd_sandbox, "collect": cmd_collect, "similarity": cmd_similarity, "tests": cmd_tests, "tool": cmd_tool,
                "hole-task": cmd_hole_task, "hole-outcome": cmd_hole_outcome, "judge": cmd_judge, "summary": cmd_summary}
    try:
        if args.command == "similarity" and args.pair:
            return cmd_similarity(None, args)
        return commands[args.command](Env(args.env, args.results), args)
    except Precondition as e:
        print("aicode.py: %s" % e, file=sys.stderr)
        return 1
    except ScriptError as e:
        print("aicode.py: script error: %s" % e, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
