#!/usr/bin/env python3
"""The spec drafting tool of DESIGN-C.md 7.1: task boxes, collection, scenario cells, questions, alignment and the
results per option. The LLM steps (drafting, answering, grouping, aligning) are Claude Code agents. This tool builds
their boxes and checks what they write (DESIGN-C.md 2.2, 2.3); it never decides or signs.

Commands, in the order of DESIGN-C.md 2.2 (RUN is a run directory):
  task --run RUN --drafter NAME [--config FILE --env FILE] [--force]
      Step 3, phase 1. The first call creates RUN from the run config FILE (kv-spec-run/1, below) and the env FILE
      (`./gradlew aicodeEnv` writes build/kv/aicode-env.json: kvInstall, reqShield, classpath, kotlinc). It builds the
      drafting box RUN/boxes/NAME/phase1: TASK.md, SOURCES.md, LANGUAGE.md, SKELETON.kv and check.sh, and prints the
      prompt for the drafter agent. --force builds the box again and drops the drafter's earlier work.
  collect --run RUN --drafter NAME
      Steps 4 and 5. Phase 1: reads DRAFT.kv, COVERAGE.md and QUESTIONS.md from the phase-1 box and runs the grammar
      check. If it finds errors, it writes the box file CORRECTION-<n>.md, which holds the error lines and nothing
      else, at most 2 rounds (DESIGN-C.md 4.2 step 1); a draft that still has errors is dropped. A passing draft is
      frozen in RUN/drafts/NAME with its assembled sidecar. Phase 2 (after `cells`): reads ANSWERS.json from the
      phase-2 box and checks its form, with the same rounds; the answers go to RUN/drafts/NAME/answers.json.
  cells --run RUN [--cells FILE] [--notes FILE]
      Steps 5 and 6. The scenario cells: `kv spec cells` on the first draft that passed collection (DESIGN-C.md
      10.4), or the cell file FILE (a protocol's hand-written cells). Runs `kv spec check --cells` on every frozen
      draft and on the reference sidecar, then builds the phase-2 box RUN/boxes/NAME/phase2 of each drafter: TASK.md,
      SOURCES.md, LANGUAGE.md, SCENARIOS.md (the cells as a table, after the text of NOTES), ANSWERS.json (the form)
      and check.sh. The files of the phase-1 box become read-only: phase 2 does not edit the draft. The size of the
      phase-2 box goes to RUN/cells/cost.json.
  questions --run RUN [--groups FILE] [--mode a|b] [--decisions-ref FILE...]
      Step 7: the question rules on this run's checks and answers (RUN/questions.json). With groups (FILE, or the
      RUN/groups.json of an earlier call), the review bundle RUN/bundle.md, with the results per option that
      `outcomes` measured. Rule 6 also takes the run config's earlier decisions. The C4 form, with --check and
      --answers instead of --run, is below.
  outcomes --run RUN --group G --option L=DRAFT.kv... [--mutants FILE] [--impl NAME=DIR...] [--jobs N]
      DESIGN-C.md 3.6 and 4.2 step 4. Each DRAFT.kv is a draft fixed to option L of group G (a box DRAFT.kv: source
      comments, hole `true`). The tool checks that it decides the group's cells as option L (mode b), then runs
      `kv verify --draft` with it on the reference implementation, on the decision mutants of FILE
      (kv-decision-mutants/1) and on each given implementation (DIR holds the implementation file and, if needed, the
      bindings.txt of AI-CODE.md 6.1). Writes RUN/outcomes/G.json and, if the run has groups, the bundle again.
  align --run RUN --decisions FILE... [--draft NAME] [--force]
  align --run RUN --check
      Step 8. The first form builds the alignment box RUN/align/box from the person's decisions (kv-decisions/1):
      TASK.md, DRAFT.kv (the chosen draft; default: the first that passed collection), DECISIONS.md, SOURCES.md,
      LANGUAGE.md, SKELETON.kv and check.sh. It also writes RUN/align/decisions.json: the decisions with the outcome
      of every cell (kv-decisions/1, the file that later runs take as earlier decisions). The second form checks the
      box's DRAFT.kv: the grammar check, then `kv spec check --cells` (with the code if the run has it): every decided
      cell must be DETERMINED as decided, and every cell decided "open" must be OPEN, in every mode; with the code,
      `kv verify --draft` on the reference implementation must be PROVEN. It also lists the other cells whose verdict
      changed, the failing clauses and the rows of init, the lemmas and the probes that `kv spec check` reports on the
      code, and the missing decision probes. If a cell or the verification fails, the loop-back (DESIGN-C.md 10.6 finding 5) writes
      the box file FEEDBACK-<n>.md and prints the prompt for the alignment agent, at most 2 rounds. FEEDBACK holds
      only these lines: each failing decided cell as its scenario and the decided outcome, or as "must stay
      undecided", and the error lines of `kv verify --draft` with their positions in DRAFT.kv. It holds no verdict,
      no clause isolation table and no cell table.
  check-box --run RUN --kind draft|answers --file FILE --work DIR
      What a box's check.sh runs: the grammar check of a draft, or the form check of an answers file. It prints error
      lines only, never a verification result.

The grammar check of a draft: the fixed lines of SKELETON.kv are all there, in order (comments and spacing do not
count); the hole is the skeleton's; every `// source:` comment has its form; and `kv spec check --draft` without code
and without cells finds no sidecar error and no Silicon type error in the contract-only model. That model holds no
probe, so a type error inside a probe shows only in the runs on code (`cells` with code, `outcomes`, `align --check`).

Exit codes: 0 done; 1 a check found a problem (an error line, a group, an undecided cell, an option draft) or the
command's precondition does not hold; 2 a usage, input or tool error.

Run config (kv-spec-run/1; paths relative to the config file):
  {"format": "kv-spec-run/1",
   "snapshot": DIR,         the `kv spec snapshot` output (sources.json, SOURCES.md)
   "skeleton": FILE,        the binding, trust and vocabulary layers (DESIGN-C.md 2.1), with neutral names and comments
   "language": FILE,        LANGUAGE.md of the protocol
   "subject": TEXT,         what the specification is about (TASK.md: "The specification is about TEXT.")
   "coverage": TEXT,        whose required behavior COVERAGE.md lists
   "variant": FILE,         the variant sidecar: module, source lines and `include` of the drafted file
   "hole": FILE,            optional: a sidecar whose `hole MI_INV` the runs on code use
   "reference": FILE,       optional: a signed variant sidecar (rule 4 proxy, the bundle's reference option)
   "decisions": [FILE],     optional: decisions of earlier runs (kv-decisions/1); rule 6 asks again about each cell
                            they leave "open" that a new draft decides
   "code": true}            optional: run on the env's reqShield checkout (reference implementation)

Answers (kv-answers/1), one file per author:
  {"format": "kv-answers/1", "author": "haiku",
   "answers": [{"cell": "C04", "text": "null", "outcome": "null", "status": "open",
                "sources": [{"anchor": "README.md#local-lock-map-size/2", "quote": "..."}]},
               {"cell": "C09", "formal_cell": "C08", ...}]}
  `cell` is the scenario ID the author answered; `formal_cell` names the cell of the cell file whose steps it has, if
  that is another cell (the pilot's C09 has the steps of C08). `outcome` is one outcome name of that cell, or null if
  the author picked none. `status` is settled, assumed or open. Every cell of the cell file needs one answer.

Groups (kv-groups/1), written by an LLM:
  {"format": "kv-groups/1",
   "groups": [{"id": "D1", "title": "Expiry boundary", "question": "...?", "cells": ["C04", "C15"]}]}

Decisions (kv-decisions/1), one file or one file per group, written from the person's answers:
  {"format": "kv-decisions/1",
   "decisions": [{"group": "D1", "option": "A", "note": "strict: ...", "cells": {"C04": "null"}},
                 {"group": "D9", "option": "open", "note": "no monitor duty"}]}
  `option` takes the outcomes of an option of the bundle; `cells` gives or overrides the outcome of a cell. Every
  cell of every group needs an outcome name of its cell, or "open": the person leaves the cell undecided on purpose,
  so no clause may decide it. The option "open" leaves every cell of the group open. As an earlier decision of a
  later run (--decisions-ref, the run config's "decisions"), a file names its open cells in "cells"; the
  RUN/align/decisions.json that `align` writes names every cell.

Decision mutants (kv-decision-mutants/1): {"format": "kv-decision-mutants/1", "mutants": [{"name": "D1-both",
  "decision": "...", "edits": [["OLD", "NEW"], ...]}]}; each OLD occurs once in the implementation file that
  scripts/mutants.py edits for the variant.

C4 form of questions (DESIGN-C.md 3.3, 3.6; user decision JC1 in 10):
  python3 -I spec.py questions --check NAME=CHECK.json --answers NAME=ANSWERS.json [--check ... --answers ...]
                               [--reference CHECK.json] [--decisions-ref DECISIONS.json...] [--mode a|b]
                               [--groups GROUPS.json] [--bundle OUT.md] [--json OUT.json]
  CHECK.json is the check.json of `kv spec check --cells` on draft NAME; ANSWERS.json is its author's scenario
  answers. Each NAME needs both. The rules pick the question cells:
    1. one draft: its author answered open or assumed. Several drafts: one author answered open, or two or more
       answered assumed.
    2. (several drafts only) two drafts decide the cell differently: both DETERMINED with different outcomes, or one
       INFEASIBLE and the other not.
    3. a draft decides the cell (DETERMINED), and its own author answered another outcome, or open.
    4. (with --reference, a proxy) a draft decides the cell differently from the reference sidecar, whose clauses hold
       on the reference implementation: the reference is DETERMINED or INFEASIBLE, and the draft is neither that
       verdict nor OPEN. DESIGN-C.md 3.3 rule 4 asks for the failing clause that decides the cell; the pilot used
       this proxy.
    5. the cell is a boundary cell (`boundary` in the cell file: a clock reading equals a lease).
    6. (with --reference or earlier decisions) a draft decides a cell that is left undecided on purpose: the reference
       sidecar has the cell OPEN, or an earlier decision (--decisions-ref FILE) leaves it "open", and a draft has it
       DETERMINED (DESIGN-C.md 10.6 finding 3: rule 4 skips the cells the reference leaves OPEN). A cell that an
       earlier decision leaves open must be a scenario of these drafts.
  Mode a (with the hole) is used if every check.json has it, else mode b; --mode picks one. Each rule reads that mode.
  With --groups, every question cell must be in exactly one group, and a group may list question cells only.
  --bundle writes the review bundle: one question per group in the shape of DESIGN-C.md 3.6.
"""
import argparse
import hashlib
import importlib.util
import json
import os
import re
import shlex
import shutil
import stat
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
# Loading mutants.py and aicode.py must not write __pycache__ files.
sys.dont_write_bytecode = True

STATUSES = ("settled", "assumed", "open")
CHECK_BUDGET = 10      # check.sh calls per box, as compile.sh of AI-CODE.md 3 item 5
ROUNDS = 2             # correction rounds (DESIGN-C.md 4.2 step 1)
SPEC_TIMEOUT = 3600    # seconds per kv spec check (with code it runs one Silicon run per clause index)
VERIFY_TIMEOUT = 900   # seconds per kv verify (kv's own Silicon budget is 300 s per run)
NAME = re.compile(r"[A-Za-z0-9_-]+")
LEFT_OPEN = "open"     # the decided outcome of a cell that the person leaves undecided on purpose


class InputError(Exception):
    """A usage, input or tool error (exit 2)."""


class Precondition(Exception):
    """The command's precondition does not hold (exit 1)."""


# ---- files ----

def load(path):
    try:
        with open(path) as f:
            return json.load(f)
    except (OSError, ValueError) as e:
        raise InputError("cannot read %s: %s" % (path, e))


def read(path):
    with open(path) as f:
        return f.read()


def write(path, text, executable=False):
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "w") as f:
        f.write(text)
    if executable:
        os.chmod(path, 0o755)


def dump(path, data):
    write(path, json.dumps(data, indent=1, ensure_ascii=False) + "\n")


def sha256(text):
    return hashlib.sha256(text.encode()).hexdigest()


def remove_tree(path):
    """Removes PATH, also when the phase-2 step made its files read-only."""
    if os.path.isdir(path):
        for d, _, files in os.walk(path):
            for f in files:
                os.chmod(os.path.join(d, f), 0o644)
        shutil.rmtree(path)


def run_cmd(command, timeout):
    """Runs COMMAND; (exit code, stdout, stderr), with exit code None after TIMEOUT seconds."""
    try:
        p = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
        return p.returncode, p.stdout, p.stderr
    except subprocess.TimeoutExpired:
        return None, "", "did not finish in %d s" % timeout
    except OSError as e:
        raise InputError("cannot run %s: %s" % (command[0], e))


MODULES = {}


def load_module(name):
    """scripts/NAME.py of this repository, loaded on first use (the copy of spec.py in a run needs neither)."""
    if name not in MODULES:
        path = os.path.join(HERE, name + ".py")
        if not os.path.exists(path):
            raise InputError("%s is not next to spec.py; run this command with the repository's scripts/spec.py" % path)
        spec = importlib.util.spec_from_file_location(name, path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        MODULES[name] = module
    return MODULES[name]


def pairs(values, option):
    out = {}
    for v in values or []:
        if "=" not in v:
            raise InputError("%s takes NAME=FILE, not %r" % (option, v))
        name, path = v.split("=", 1)
        if name in out:
            raise InputError("%s %s is given twice" % (option, name))
        out[name] = path
    return out


# ---- the run directory ----

class Run:
    """RUN/run.json and the layout below it:
    tool/          copies the boxes' check.sh uses: kv, spec.py, the snapshot, SKELETON.kv, LANGUAGE.md, the variant
    boxes/NAME/    phase1/ and phase2/: what the drafter sees
    harness/NAME/  state.json, the check.sh counts and calls, the collect attempts (outside the boxes)
    drafts/NAME/   the frozen draft, its assembled sidecar/, check/ (kv spec check) and answers.json
    cells/         scenario.cells, cells.json, notes.md, cost.json
    reference/     check/ of the reference sidecar
    questions.json, groups.json, bundle.md, outcomes/, align/"""

    def __init__(self, path):
        self.dir = os.path.abspath(path)
        if not os.path.exists(self.path("run.json")):
            raise Precondition("%s has no run.json: create the run with task --config FILE --env FILE" % self.dir)
        self.cfg = load(self.path("run.json"))

    def path(self, *parts):
        return os.path.join(self.dir, *parts)

    @property
    def kv(self):
        return self.path("tool", "kv", "bin", "kv")

    @property
    def snapshot(self):
        return self.path("tool", "snapshot")

    @property
    def variant(self):
        return self.cfg["variant"]

    @property
    def common(self):
        return self.cfg["common"]

    @property
    def code(self):
        return bool(self.cfg.get("root"))

    def box(self, name, phase):
        return self.path("boxes", name, "phase%d" % phase)

    def harness(self, name):
        return self.path("harness", name)

    def drafts(self, name):
        return self.path("drafts", name)

    def blocks(self):
        return {b["anchor"]: b for b in load(os.path.join(self.snapshot, "sources.json"))}

    def state(self):
        p = self.path("state.json")
        return load(p) if os.path.exists(p) else {"passed": []}

    def save_state(self, st):
        dump(self.path("state.json"), st)

    def drafter(self, name):
        p = os.path.join(self.harness(name), "state.json")
        if not os.path.exists(p):
            raise Precondition("no drafter %s in %s: run task first" % (name, self.dir))
        return load(p)

    def save_drafter(self, name, st):
        dump(os.path.join(self.harness(name), "state.json"), st)

    def cell_list(self):
        p = self.path("cells", "cells.json")
        if not os.path.exists(p):
            raise Precondition("%s has no cells yet: run cells first" % self.dir)
        return {c["id"]: c for c in load(p)["cells"]}


def init_run(run_dir, config_path, env_path):
    if os.path.isdir(run_dir) and os.listdir(run_dir):
        raise InputError("%s is not empty and has no run.json: give a new run directory" % run_dir)
    cfg = load(config_path)
    if cfg.get("format") != "kv-spec-run/1":
        raise InputError("%s is not kv-spec-run/1" % config_path)
    base = os.path.dirname(os.path.abspath(config_path))

    def path(key, optional=False):
        if cfg.get(key) is None:
            if optional:
                return None
            raise InputError("%s has no %r" % (config_path, key))
        p = os.path.normpath(os.path.join(base, cfg[key]))
        if not os.path.exists(p):
            raise InputError("%s: %s %s does not exist" % (config_path, key, p))
        return p

    for key in ("subject", "coverage"):
        if not isinstance(cfg.get(key), str) or not cfg[key].strip():
            raise InputError("%s has no %r text" % (config_path, key))
    env = load(env_path)
    for key in ("kvInstall", "reqShield", "classpath", "kotlinc"):
        if key not in env:
            raise InputError("%s has no %r (run ./gradlew aicodeEnv)" % (env_path, key))
    snapshot, variant = path("snapshot"), path("variant")
    for f in ("sources.json", "SOURCES.md"):
        if not os.path.exists(os.path.join(snapshot, f)):
            raise InputError("the snapshot %s has no %s" % (snapshot, f))
    includes = re.findall(r'(?m)^include\s+"([^"]+)"', read(variant))
    if len(includes) != 1 or "/" in includes[0]:
        raise InputError("the variant %s must include exactly one file of its own directory, found %s" % (variant, includes))
    decisions = cfg.get("decisions") or []
    if not isinstance(decisions, list):
        raise InputError("%s: decisions is a list of kv-decisions/1 files" % config_path)
    refs = [os.path.normpath(os.path.join(base, f)) for f in decisions]
    missing = [f for f in refs if not os.path.exists(f)]
    if missing:
        raise InputError("%s: decisions %s does not exist" % (config_path, ", ".join(missing)))
    hole_file = path("hole", optional=True)
    hole = None
    if hole_file:
        m = HOLE.search(read(hole_file))
        if not m:
            raise InputError("%s has no hole MI_INV" % hole_file)
        hole = m.group(0)
    tool = os.path.join(os.path.abspath(run_dir), "tool")
    os.makedirs(tool)
    copy_kv(env["kvInstall"], os.path.join(tool, "kv"))
    shutil.copy(os.path.abspath(__file__), os.path.join(tool, "spec.py"))
    os.makedirs(os.path.join(tool, "snapshot"))
    for f in ("sources.json", "SOURCES.md"):
        shutil.copy(os.path.join(snapshot, f), os.path.join(tool, "snapshot", f))
    shutil.copy(path("skeleton"), os.path.join(tool, "SKELETON.kv"))
    shutil.copy(path("language"), os.path.join(tool, "LANGUAGE.md"))
    os.makedirs(os.path.join(tool, "variant"))
    shutil.copy(variant, os.path.join(tool, "variant", os.path.basename(variant)))
    if hole:
        write(os.path.join(tool, "hole.kv"), hole)
    code = bool(cfg.get("code"))
    dump(os.path.join(run_dir, "run.json"), {
        "format": "kv-spec-run/1", "config": os.path.abspath(config_path), "subject": cfg["subject"].strip(),
        "coverage": cfg["coverage"].strip(), "variant": os.path.basename(variant), "common": includes[0], "hole": bool(hole),
        "reference": path("reference", optional=True), "decisions": refs, "root": env["reqShield"] if code else None,
        "classpath": env["classpath"] if code else None, "kotlinc": env["kotlinc"] if code else None})


def copy_kv(src, dst):
    """A copy of the kv install, so that a box's check.sh names no path of this repository (as aicode.py's kv_copy)."""
    jar = os.path.join("lib", "translator.jar")
    if not os.path.exists(os.path.join(src, "bin", "kv")):
        raise InputError("%s is no kv install (no bin/kv): run ./gradlew installDist" % src)
    if os.path.exists(os.path.join(dst, jar)) and read_bytes(os.path.join(dst, jar)) == read_bytes(os.path.join(src, jar)):
        return
    shutil.rmtree(dst, ignore_errors=True)
    shutil.copytree(src, dst)


def read_bytes(path):
    with open(path, "rb") as f:
        return f.read()


# ---- the draft text ----

SOURCE_COMMENT = re.compile(r"^\s*//\s*source:")
SOURCE_QUOTED = re.compile(r'^\s*//\s*source:\s*\[([^\]\n]+)\]\s*"([^"\n]+)"\s*$')
SOURCE_NONE = re.compile(r'^\s*//\s*source:\s*none\s*\(([^"\n]*)\)\s*$')
CLAUSE = re.compile(r"^(\s*)(issue|ensures|ends_only_if|unbacked_only_if|invariant|lemma|probe)\b")
HOLE = re.compile(r'hole\s+MI_INV\s+""".*?"""', re.S)


def code_part(line):
    """LINE without its `//` comment (outside strings), each whitespace run as one space."""
    in_string = False
    for i, c in enumerate(line):
        if c == '"':
            in_string = not in_string
        elif not in_string and line.startswith("//", i):
            line = line[:i]
            break
    return " ".join(line.split())


def skeleton_errors(draft, skeleton):
    """The fixed lines of SKELETON missing from DRAFT, or out of order (comments and spacing do not count), and a
    changed hole."""
    errors = []
    lines = [code_part(l) for l in draft.split("\n")]
    i = 0
    for n, line in enumerate(skeleton.split("\n"), 1):
        c = code_part(line)
        if not c:
            continue
        while i < len(lines) and lines[i] != c:
            i += 1
        if i == len(lines):
            errors.append("DRAFT.kv: line %d of SKELETON.kv is missing, changed or out of order: %s" % (n, line.strip()))
            break
        i += 1
    fixed = HOLE.search(skeleton)
    holes = HOLE.findall(draft)
    if fixed and (len(holes) != 1 or " ".join(holes[0].split()) != " ".join(fixed.group(0).split())):
        errors.append("DRAFT.kv: the hole MI_INV must stay as SKELETON.kv has it")
    return errors


def source_comment_errors(draft):
    return ['DRAFT.kv:%d: a source comment is // source: [ANCHOR] "EXACT QUOTE" or // source: none (REASON); the quote '
            'and the reason hold no double quote character' % n
            for n, line in enumerate(draft.split("\n"), 1)
            if SOURCE_COMMENT.match(line) and not (SOURCE_QUOTED.match(line) or SOURCE_NONE.match(line))]


def clause_end(lines, i, indent, probe):
    """The index after the clause that starts at line I: a probe ends at its `}`; any other clause at the first line
    that is not indented deeper than it (DESIGN-v0.md 4.2), or is blank or a comment."""
    j = i + 1
    if probe:
        while j < len(lines) and not (lines[j].strip() == "}" and len(lines[j]) - len(lines[j].lstrip()) == indent):
            j += 1
        return min(j + 1, len(lines))
    while j < len(lines) and lines[j].strip() and not lines[j].strip().startswith("//") and len(lines[j]) - len(lines[j].lstrip()) > indent:
        j += 1
    return j


def intent_lines(lines):
    """The indexes of the lines that start an intent clause: CLAUSE lines outside `trusted fun` declarations (whose
    `ensures` belong to the trust layer)."""
    trusted = False
    for i, line in enumerate(lines):
        if line.startswith("trusted fun"):
            trusted = True
        elif line.strip() and not line[0].isspace() and not line.lstrip().startswith("//"):
            trusted = False
        if not trusted and CLAUSE.match(line):
            yield i


def convert_sources(text, blocks):
    """TEXT with each `// source:` comment above a clause written as a source line after the clause (DESIGN-C.md 5.2),
    with the anchor block's fingerprint from BLOCKS. Source comments may be separated from their clause by blank lines
    and other comments. Returns the text, its notes (clauses without a source comment, and comments without a clause)
    and its line map: for each line of the text, the line of TEXT it comes from (a source line: its comment's line)."""
    lines = text.split("\n")
    starts = set(intent_lines(lines))
    out, origin, pending, notes = [], [], [], []
    i = 0
    while i < len(lines):
        line = lines[i]
        if SOURCE_COMMENT.match(line):
            pending.append((i + 1, line))
            i += 1
            continue
        m = CLAUSE.match(line) if i in starts else None
        if not m:
            if pending and line.strip() and not line.strip().startswith("//"):
                notes.append("lines %s: source comments above no clause" % ", ".join(str(n) for n, _ in pending))
                pending = []
            out.append(line)
            origin.append(i + 1)
            i += 1
            continue
        indent = len(m.group(1))
        j = clause_end(lines, i, indent, m.group(2) == "probe")
        out.extend(lines[i:j])
        origin.extend(range(i + 1, j + 1))
        if not pending:
            notes.append("line %d: %s has no source comment" % (i + 1, " ".join(line.split()[:2]).rstrip(":")))
        for n, comment in pending:
            out.append(" " * (indent + 2) + source_line(comment, blocks))
            origin.append(n)
        pending = []
        i = j
    if pending:
        notes.append("lines %s: source comments above no clause" % ", ".join(str(n) for n, _ in pending))
    return "\n".join(out), notes, origin


def source_line(comment, blocks):
    m = SOURCE_QUOTED.match(comment)
    if m:
        anchor = m.group(1).strip()
        block = blocks.get(anchor)
        return 'source "%s" quote "%s"%s' % (anchor, m.group(2), ' sha256 "%s"' % block["sha256"] if block else "")
    return 'source none "%s"' % SOURCE_NONE.match(comment).group(1).strip()


def write_sidecar(run, text, out_dir):
    """OUT_DIR with TEXT as the drafted file (the variant's include) and the variant file; the variant's path."""
    remove_tree(out_dir)
    write(os.path.join(out_dir, run.common), text)
    shutil.copy(run.path("tool", "variant", run.variant), os.path.join(out_dir, run.variant))
    return os.path.join(out_dir, run.variant)


def assemble(run, draft_text, out_dir):
    """The checkable sidecar of a box DRAFT.kv in OUT_DIR: the draft with source lines, and the run's hole if it has
    one. Returns the variant's path, the notes of the source conversion and the line map: for each line of the
    drafted file, the line of DRAFT.kv it comes from (the run's hole: the line where the draft's hole starts)."""
    text, notes, origin = convert_sources(draft_text, run.blocks())
    if run.cfg.get("hole"):
        holes = list(HOLE.finditer(text))
        if len(holes) != 1:
            raise InputError("the draft has %d holes MI_INV (must be 1)" % len(holes))
        h = holes[0]
        block = read(run.path("tool", "hole.kv"))
        first = text.count("\n", 0, h.start())
        origin = origin[:first] + [origin[first]] * (block.count("\n") + 1) + origin[first + h.group(0).count("\n") + 1:]
        text = text[:h.start()] + block + text[h.end():]
    return write_sidecar(run, text, out_dir), notes, origin


def draft_positions(run, line, origin):
    """LINE with each position in the drafted file (the variant's include) written as the position in DRAFT.kv that
    the line map ORIGIN of `assemble` gives."""
    def sub(p):
        if p.group("file") != run.common:
            return p.group(0)
        return "DRAFT.kv:%d:%s" % (origin[int(p.group("line")) - 1], p.group("col"))
    return POSITION.sub(sub, line)


# ---- kv ----

def spec_check(run, sidecar, out, cells=None, code=False, draft=True):
    """`kv spec check` of SIDECAR into OUT; its check.json. Exit 4 (findings) is no tool error."""
    command = [run.kv, "spec", "check"]
    if code:
        command += ["--root", run.cfg["root"], "--classpath", run.cfg["classpath"], "--kotlinc", run.cfg["kotlinc"]]
    command += ["--snapshot", run.snapshot] + (["--cells", cells] if cells else []) + (["--draft"] if draft else [])
    remove_tree(out)
    exit_code, stdout, stderr = run_cmd(command + ["--out", out, sidecar], SPEC_TIMEOUT)
    write(os.path.join(out, "report.txt"), stdout + ("\n--- stderr ---\n" + stderr if stderr.strip() else ""))
    if exit_code not in (0, 4) or not os.path.exists(os.path.join(out, "check.json")):
        raise InputError("kv spec check of %s did not run (exit %s): %s" % (sidecar, exit_code, (stderr or stdout).strip()[-600:]))
    return load(os.path.join(out, "check.json"))


FINDING = re.compile(r"^(?P<msg>.*?): sidecar (?P<id>.+?) (?P<file>[^\s/:]+\.kv):(?P<line>\d+):(?P<col>\d+)(?P<rest>.*)$")
POSITION = re.compile(r"(?:[^\s:]*/)?(?P<file>[^\s/:]+\.kv):(?P<line>\d+):(?P<col>\d+)")


def error_line(run, text, work):
    """A kv error or Silicon finding as a line about DRAFT.kv: position first, then the message."""
    text = text.replace(work + os.sep, "")

    def name(f):
        return "DRAFT.kv" if f == run.common else f
    m = FINDING.match(text)
    if m:
        return "%s:%s:%s: %s (in %s)%s" % (name(m.group("file")), m.group("line"), m.group("col"), m.group("msg"), m.group("id"), m.group("rest"))
    return POSITION.sub(lambda p: "%s:%s:%s" % (name(p.group("file")), p.group("line"), p.group("col")), text)


def grammar_errors(run, draft_text, work):
    """The error lines of the grammar check of a box DRAFT.kv (module docstring). WORK holds the check's files. No line
    tells a verification result: the check has no code and no cells, and only errors are kept."""
    work = os.path.abspath(work)
    errors = skeleton_errors(draft_text, read(run.path("tool", "SKELETON.kv"))) + source_comment_errors(draft_text)
    check = spec_check(run, write_sidecar(run, draft_text, os.path.join(work, "sidecar")), os.path.join(work, "check"))
    errors += [error_line(run, e, work) for e in check.get("errors") or []]
    b = (check.get("cells") or {}).get("b") or {}
    if b.get("error"):
        found = [error_line(run, f, work) for f in b.get("findings") or []]
        errors += found or ["DRAFT.kv: the type check of the clauses did not finish"]
    return list(dict.fromkeys(errors))


def verify_draft(run, sidecar_dir, root, bindings, work):
    """`kv verify --draft` of the assembled sidecar in SIDECAR_DIR on ROOT, with the binding adaptations BINDINGS
    (AI-CODE.md 6.1) applied to a copy; the record of the run."""
    mutants = load_module("mutants")
    if bindings:
        aicode = load_module("aicode")
        adapted = work + "-spec"
        remove_tree(adapted)
        shutil.copytree(sidecar_dir, adapted)
        names = [run.variant, run.common]
        texts = aicode.adapt({n: read(os.path.join(adapted, n)) for n in names}, bindings)
        for n, t in texts.items():
            write(os.path.join(adapted, n), t)
        sidecar_dir = adapted
    command = [run.kv, "verify", "--draft", "--root", root, "--classpath", run.cfg["classpath"], "--kotlinc", run.cfg["kotlinc"],
               "--out", work + ".vpr", os.path.join(sidecar_dir, run.variant)]
    start = time.time()
    code, out, err = run_cmd(command, VERIFY_TIMEOUT)
    write(work + ".report", out + ("\n--- stderr ---\n" + err if err.strip() else ""))
    rec = {"verdict": "ERROR (timeout)" if code is None else mutants.VERDICTS.get(code, "exit %s" % code),
           "seconds": round(time.time() - start, 1), "actions": {}, "others": []}
    try:
        rows, others, checks = mutants.parse_report(out)
        rec["actions"] = {a: [v, sorted(ids)] for a, (v, ids) in rows.items()}
        rec["others"] = [" ".join(o) for o in others]
        rec["pass2"] = {a: [s, sorted(ids)] for a, (s, ids) in checks.items()}
    except mutants.ScriptError as e:
        rec["parse_error"] = str(e)
    rec["errors"] = report_errors(out)
    if code is None or code == 4:
        rec["error"] = (err or out).strip()[-600:]
    return rec


def report_errors(report):
    """The error lines of a `kv verify` report (DESIGN-v0.md 5.8), one line each: a pass-1 or property-check finding of
    an action after the action's name, a finding of init, a lemma or a probe (the report names it), and the Silicon
    errors outside every method. Rows, verdicts and the other parts of the report are left out."""
    mutants = load_module("mutants")
    out, row, outside = [], None, False
    for line in report.splitlines():
        m = mutants.ROW.match(line)
        if m:
            row, outside = m.group(1) or m.group(2), False
        elif mutants.OTHERS.match(line):
            row, outside = None, False
        elif line == "Silicon errors:":
            row, outside = None, True
        elif outside and line.startswith("  ") and not line.startswith("   "):
            out.append(line.strip())
        elif line.startswith("      ") and not line.startswith("       "):
            out.append(("%s: " % row if row else "") + line.strip())
        elif row and line.startswith(mutants.PASS2):
            out.append("%s: %s" % (row, line[len(mutants.PASS2):]))
        elif not line.startswith(" "):
            row, outside = None, False
    return out


def run_summary(rec):
    """A verify record in one phrase: the verdict, then each row that is not PROVEN with its failing obligations."""
    bad = ["%s %s" % (a, ", ".join(ids) if ids else v) for a, (v, ids) in sorted(rec["actions"].items()) if v != "PROVEN"]
    bad += [o for o in rec.get("others") or [] if not o.endswith(" PROVEN")]
    if rec.get("error") and not bad:
        bad.append(rec["error"].splitlines()[-1] if rec["error"].strip() else "no report")
    return "%s (%s)" % (rec["verdict"], "; ".join(bad)) if bad else rec["verdict"]


# ---- box texts ----

TASK1 = """# Task: draft the formal specification from the team's text

You turn the team's text into a formal specification. A person on the team will review your draft and decide every
point that the text leaves open, so your draft must make each of those points visible.

Files in this directory:
- `SOURCES.md`: the team's text, split into blocks with anchors. This text is the only source of intent. The
  specification is about {subject}. Other parts of the text may be about other things.
- `LANGUAGE.md`: the specification language.
- `SKELETON.kv`: the fixed part of the specification (bindings to the code shape, ghost state, operations).
- `check.sh`: checks the grammar and the types of your `DRAFT.kv` (see "Checking DRAFT.kv").

There is no implementation to look at. Work from the text and the language description only.

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
(no ellipsis, no joining of two blocks, no double quote character). If a clause comes from no sentence of the text
(for example a probe, or a consistency fact), write `// source: none (REASON)` instead.

### 2. `COVERAGE.md`

A table with one row per block of SOURCES.md that states required behavior of {coverage}. Columns: anchor; what the
block requires (one line); the clause IDs that encode it, or "not encoded" with the reason (out of the model, cannot
be stated in the language, or a decision you did not want to make).

### 3. `QUESTIONS.md`

Every decision that you had to make, or that a reader of the text could make in more than one way. For each one:
- the anchor and the exact quote that leaves it open,
- the options (at least two), stated as concrete behavior,
- the option your DRAFT.kv encodes, and the clause IDs that encode it,
- how sure you are that the text means this option (high, medium, low), and why.

Write all three files in English.

## Checking DRAFT.kv

`./check.sh` checks that DRAFT.kv keeps the fixed lines of SKELETON.kv, that each source comment has the form above,
and that the clauses parse and type-check. It prints one line per error, or that it found none. It does not say
whether a clause is right: the person on the team decides that. You have at most {budget} calls.

When you are done, report the names of the files you wrote, the number of questions, and the number of `check.sh`
calls you used.

## Rules

- Work only inside this directory. Do not read, list or search any file outside it.
- Do not run any program other than `check.sh`.
- Do not use the web, memory tools, other agents, or the advisor tool.
"""

TASK2 = """# Task: answer the scenarios from the team's text

`SCENARIOS.md` lists scenarios. Each one starts from the empty module, runs a few steps, and lists the outcomes to
pick from. Answer each scenario from the team's text (`SOURCES.md`) alone. `LANGUAGE.md` explains the notation of
the steps and the outcomes.

Write your answers into `ANSWERS.json`. It has one entry per scenario. In each entry, set:
- `outcome`: the name of the outcome that the text gives, one of the names that the scenario lists, or `null` if
  none fits;
- `text`: the outcome in a few words (if none fits, the outcome you would give);
- `status`: `"settled"` (a sentence of the text decides it), `"assumed"` (the text suggests it but does not decide
  it) or `"open"` (the text does not decide it);
- `sources`: the anchors and exact quotes that decide or suggest it, each as `{{"anchor": "...", "quote": "..."}}`,
  or `[]` if there is none.
Do not change the `cell` of an entry, and do not add or remove entries.

`./check.sh` checks the form of ANSWERS.json. It prints one line per error, or that it found none. You have at most
{budget} calls.

When you are done, report the number of answers of each status and the number of `check.sh` calls you used.

## Rules

- Answer from SOURCES.md, not from a draft of the specification. Do not open, change or copy the files of your
  earlier drafting task.
- Work only inside this directory. Do not read, list or search any file outside it.
- Do not run any program other than `check.sh`.
- Do not use the web, memory tools, other agents, or the advisor tool.
"""

TASK_ALIGN = """# Task: make the draft follow the team's decisions

`DRAFT.kv` is a draft of the formal specification of {subject}. It was written from the team's text (`SOURCES.md`) in
the language of `LANGUAGE.md`; its fixed part is `SKELETON.kv`. A person on the team has decided the points that the
text leaves open. `DECISIONS.md` lists each decision: the scenarios it settles, and the outcome the person chose for
each scenario.

Edit `DRAFT.kv` so that its clauses give each scenario of `DECISIONS.md` the chosen outcome:
1. Change the clauses that decide these scenarios, or add clauses where none decides them. If DECISIONS.md leaves a
   scenario open, no clause may decide its outcome: change or remove the clauses that decide it. Keep every other
   clause as it is.
2. For each decision that chooses an outcome, add a probe named `decision_ID` (ID as in DECISIONS.md, for example
   `decision_D1`). The probe runs one of the decision's scenarios that has a chosen outcome, from the empty module,
   and asserts that outcome.
3. Before each clause and probe that you add or change, write its source comments as the draft does:
   `// source: [ANCHOR] "EXACT QUOTE"` (a part of that block, copied character for character, no double quote
   character), or `// source: none (REASON)`, for example `// source: none (decision D1 of the team)`.
4. Do not change, remove or reorder the lines of `SKELETON.kv`, and do not edit the `hole`.

`./check.sh` checks the grammar and the types of DRAFT.kv. It prints one line per error, or that it found none. It
does not say whether the clauses follow the decisions: the team's tool checks that afterwards. You have at most
{budget} calls.

When you are done, report the IDs of the clauses you changed or added, the probes you added, and the number of
`check.sh` calls you used.

## Rules

- Work only inside this directory. Do not read, list or search any file outside it.
- Do not run any program other than `check.sh`.
- Do not use the web, memory tools, other agents, or the advisor tool.
"""

CHECK_SH = r"""#!/bin/bash
# Checks @WHAT@ in this directory and prints one line per error, or that it found none.
# At most @BUDGET@ calls. The count and a copy of each call are kept outside this directory.
DIR="$(cd "$(dirname "$0")" && pwd)"
H=@HARNESS@
BUDGET=@BUDGET@
n=$(( $(cat "$H/check.count" 2>/dev/null || echo 0) + 1 ))
if [ "$n" -gt "$BUDGET" ]; then
  echo "BUDGET EXHAUSTED: all $BUDGET check.sh calls are used. Stop and report."
  echo "refused $(date +%T): budget exhausted" >> "$H/check.log"
  exit 3
fi
echo "$n" > "$H/check.count"
mkdir -p "$H/calls/$n"
cp "$DIR/@FILE@" "$H/calls/$n/" 2>/dev/null
python3 -I @SPEC@ check-box --run @RUN@ --kind @KIND@ --file "$DIR/@FILE@" --work "$H/calls/$n" > "$H/calls/$n/output.txt" 2>&1
code=$?
cat "$H/calls/$n/output.txt"
echo "call $n $(date +%T) exit=$code" >> "$H/check.log"
echo "(check.sh call $n of $BUDGET used)"
exit "$code"
"""

CHECKED = {"draft": ("DRAFT.kv", "the grammar and the types of DRAFT.kv", "grammar or type"),
           "answers": ("ANSWERS.json", "the form of ANSWERS.json", "form")}


def check_sh(run, harness, kind):
    file, what, _ = CHECKED[kind]
    q = shlex.quote
    return (CHECK_SH.replace("@WHAT@", what).replace("@HARNESS@", q(harness)).replace("@BUDGET@", str(CHECK_BUDGET))
            .replace("@SPEC@", q(run.path("tool", "spec.py"))).replace("@RUN@", q(run.dir)).replace("@KIND@", kind).replace("@FILE@", file))


def prompt(box):
    return ("Read TASK.md in the directory %s and do the task it describes. Work only inside %s: do not read, list or "
            "search anything outside it.\n" % (box, box))


def correction(kind, n, errors):
    """The text of CORRECTION-N.md: the error lines and nothing else (DESIGN-C.md 2.2 step 4, 4.2 step 1)."""
    file, _, what = CHECKED[kind]
    return ("# Corrections, round %d of %d\n\n%s has these %s errors. Fix them in %s and change nothing else.\n\n%s\n"
            % (n, ROUNDS, file, what, file, "\n".join("- " + e for e in errors)))


# ---- task ----

def cmd_task(args):
    if not NAME.fullmatch(args.drafter):
        raise InputError("a drafter name is letters, digits, - and _: %r" % args.drafter)
    if not os.path.exists(os.path.join(args.run, "run.json")):
        if not (args.config and args.env):
            raise InputError("a new run needs --config FILE and --env FILE")
        init_run(args.run, args.config, args.env)
    elif args.config or args.env:
        raise InputError("%s exists: --config and --env only create a run" % args.run)
    run = Run(args.run)
    name = args.drafter
    box = run.box(name, 1)
    if os.path.exists(box) and not args.force:
        raise Precondition("%s exists; --force builds it again and drops the drafter's earlier work" % box)
    for d in (run.path("boxes", name), run.harness(name), run.drafts(name)):
        remove_tree(d)
    st = run.state()
    if name in st["passed"]:
        st["passed"].remove(name)
        run.save_state(st)
    h = os.path.join(run.harness(name), "phase1")
    os.makedirs(h)
    write(os.path.join(box, "TASK.md"), TASK1.format(subject=run.cfg["subject"], coverage=run.cfg["coverage"], budget=CHECK_BUDGET))
    shutil.copy(os.path.join(run.snapshot, "SOURCES.md"), box)
    shutil.copy(run.path("tool", "LANGUAGE.md"), box)
    shutil.copy(run.path("tool", "SKELETON.kv"), box)
    write(os.path.join(box, "check.sh"), check_sh(run, h, "draft"), executable=True)
    run.save_drafter(name, {"drafter": name, "phase1": {"status": "drafting", "rounds": 0, "attempts": []}, "phase2": None})
    text = prompt(box)
    write(os.path.join(run.harness(name), "prompt-phase1.txt"), text)
    print("phase-1 box of %s: %s" % (name, box))
    print("prompt (also in %s):" % os.path.join(run.harness(name), "prompt-phase1.txt"))
    print(text, end="")
    return 0


# ---- check-box ----

def answers_rows(data, cells):
    """The answers of a kv-answers/1 file by scenario ID, and its errors, against CELLS (ID -> cell with outcomes)."""
    if not isinstance(data, dict) or data.get("format") != "kv-answers/1":
        return {}, ['the file is not kv-answers/1 (it needs "format": "kv-answers/1")']
    rows, errors = {}, []
    for a in data.get("answers") or []:
        if not isinstance(a, dict):
            errors.append("an answer is not an object: %r" % (a,))
            continue
        row = a.get("cell")
        if row in rows:
            errors.append("answers %s twice" % row)
            continue
        formal = a.get("formal_cell") or row
        if formal not in cells:
            errors.append("answers %s, which is no cell of the check (formal cell %s)" % (row, formal))
            continue
        if a.get("status") not in STATUSES:
            errors.append("%s has status %r, not one of %s" % (row, a.get("status"), ", ".join(STATUSES)))
            continue
        outs = [o["name"] for o in cells[formal]["outcomes"]]
        if a.get("outcome") is not None and a["outcome"] not in outs:
            errors.append("%s names outcome %r; cell %s has %s" % (row, a["outcome"], formal, ", ".join(outs)))
            continue
        sources = a.get("sources") or []
        if not isinstance(sources, list) or any(not isinstance(s, dict) or not isinstance(s.get("anchor"), str) or not isinstance(s.get("quote"), str) for s in sources):
            errors.append('%s: sources is a list of {"anchor": "...", "quote": "..."}' % row)
            continue
        rows[row] = dict(a, formal_cell=formal)
    missing = [c for c in cells if c not in rows]
    if missing:
        errors.append("has no answer for %s" % ", ".join(missing))
    return rows, errors


def answers_form_errors(run, path):
    try:
        data = json.loads(read(path))
    except FileNotFoundError:
        return ["ANSWERS.json is missing"]
    except ValueError as e:
        return ["ANSWERS.json is no valid JSON: %s" % e]
    return ["ANSWERS.json: " + e for e in answers_rows(data, run.cell_list())[1]]


def cmd_check_box(args):
    run = Run(args.run)
    file, _, what = CHECKED[args.kind]
    if args.kind == "draft":
        errors = grammar_errors(run, read(args.file), args.work) if os.path.exists(args.file) else ["DRAFT.kv is missing"]
    else:
        errors = answers_form_errors(run, args.file)
    for e in errors:
        print(e)
    print("check.sh: %s %s error%s in %s." % (len(errors) or "no", what, "" if len(errors) == 1 else "s", file))
    return 1 if errors else 0


# ---- collect ----

def collect_round(run, name, phase, kind, errors):
    """Records one collection of PHASE. Without errors the phase passes. With errors: a correction round while rounds
    are left (the box gets CORRECTION-<n>.md), else the phase is dropped. True if it passed."""
    st = run.drafter(name)
    ph = st["phase%d" % phase]
    ph["attempts"].append({"time": time.strftime("%Y-%m-%d %H:%M:%S"), "errors": len(errors)})
    box = run.box(name, phase)
    if not errors:
        ph["status"] = "passed"
        run.save_drafter(name, st)
        return True
    if ph["rounds"] >= ROUNDS:
        ph["status"] = "dropped"
        ph["errors"] = errors
        run.save_drafter(name, st)
        print("%s phase %d: DROPPED after %d correction round(s); the errors (also in %s):" % (name, phase, ROUNDS, os.path.join(run.harness(name), "state.json")))
        for e in errors:
            print("  " + e)
        return False
    ph["rounds"] += 1
    ph["status"] = "correcting"
    run.save_drafter(name, st)
    f = os.path.join(box, "CORRECTION-%d.md" % ph["rounds"])
    write(f, correction(kind, ph["rounds"], errors))
    file = CHECKED[kind][0]
    text = ("In the directory %s, CORRECTION-%d.md lists errors of %s. Fix them as it says and change nothing else. Work "
            "only inside %s.\n" % (box, ph["rounds"], file, box))
    write(os.path.join(run.harness(name), "prompt-phase%d-correction-%d.txt" % (phase, ph["rounds"])), text)
    print("%s phase %d: %d error(s); correction round %d of %d: %s" % (name, phase, len(errors), ph["rounds"], ROUNDS, f))
    print("prompt for the drafter:")
    print(text, end="")
    return False


def check_calls(run, name, phase):
    f = os.path.join(run.harness(name), "phase%d" % phase, "check.count")
    return int(read(f).strip()) if os.path.exists(f) else 0


def cmd_collect(args):
    run = Run(args.run)
    name = args.drafter
    st = run.drafter(name)
    p1 = st["phase1"]
    if p1["status"] == "dropped" or (st.get("phase2") or {}).get("status") == "dropped":
        raise Precondition("%s was dropped; task --force starts the drafter again" % name)
    if p1["status"] != "passed":
        return collect_phase1(run, name)
    if st.get("phase2") is None:
        raise Precondition("phase 1 of %s is collected; run cells for phase 2" % name)
    if st["phase2"]["status"] == "passed":
        raise Precondition("both phases of %s are collected" % name)
    return collect_phase2(run, name)


def collect_phase1(run, name):
    box = run.box(name, 1)
    if not os.path.exists(os.path.join(box, "DRAFT.kv")):
        raise Precondition("no DRAFT.kv in %s yet" % box)
    errors = ["%s is missing: write it as TASK.md says" % f for f in ("COVERAGE.md", "QUESTIONS.md") if not os.path.exists(os.path.join(box, f))]
    n = len(run.drafter(name)["phase1"]["attempts"]) + 1
    errors += grammar_errors(run, read(os.path.join(box, "DRAFT.kv")), os.path.join(run.harness(name), "collect-phase1-%d" % n))
    if not collect_round(run, name, 1, "draft", errors):
        return 1
    d = run.drafts(name)
    remove_tree(d)
    os.makedirs(d)
    for f in ("DRAFT.kv", "COVERAGE.md", "QUESTIONS.md"):
        shutil.copy(os.path.join(box, f), d)
    text = read(os.path.join(d, "DRAFT.kv"))
    write(os.path.join(d, "draft.sha256"), sha256(text) + "\n")
    _, notes, _ = assemble(run, text, os.path.join(d, "sidecar"))
    st = run.state()
    st["passed"].append(name)
    run.save_state(st)
    clauses = len(list(intent_lines(text.split("\n"))))
    quoted = len([l for l in text.split("\n") if SOURCE_QUOTED.match(l)])
    print("%s phase 1: collected after %d correction round(s), %d check.sh call(s); %s" % (
        name, run.drafter(name)["phase1"]["rounds"], check_calls(run, name, 1), d))
    print("  %d clause(s), %d quoted source comment(s), %d source note(s)%s" % (clauses, quoted, len(notes), "".join("\n    " + x for x in notes)))
    print("  draft %d of the run to pass%s" % (len(st["passed"]), " (the cells come from it)" if len(st["passed"]) == 1 else ""))
    return 0


def collect_phase2(run, name):
    box = run.box(name, 2)
    errors = answers_form_errors(run, os.path.join(box, "ANSWERS.json"))
    if not collect_round(run, name, 2, "answers", errors):
        return 1
    data = load(os.path.join(box, "ANSWERS.json"))
    data["author"] = name
    dump(os.path.join(run.drafts(name), "answers.json"), data)
    rows, _ = answers_rows(data, run.cell_list())
    counts = {s: sum(1 for a in rows.values() if a["status"] == s) for s in STATUSES}
    print("%s phase 2: collected after %d correction round(s), %d check.sh call(s): %s" % (
        name, run.drafter(name)["phase2"]["rounds"], check_calls(run, name, 2), ", ".join("%d %s" % (counts[s], s) for s in STATUSES)))
    frozen = read(os.path.join(run.drafts(name), "draft.sha256")).strip()
    draft = os.path.join(run.box(name, 1), "DRAFT.kv")
    if not os.path.exists(draft) or sha256(read(draft)) != frozen:
        print("  NOTE: the phase-1 DRAFT.kv of %s changed after collection; the frozen draft in %s stays in use" % (name, run.drafts(name)))
    return 0


# ---- cells ----

def check_summary(check):
    """One line about a check.json: per mode the cell counts, the failing clauses, the errors."""
    parts = []
    for mode, m in sorted((check.get("cells") or {}).items()):
        if m.get("error"):
            parts.append("mode %s ERROR: %s" % (mode, m["error"]))
            continue
        vs = list(m["cells"].values())
        parts.append("mode %s %d DETERMINED, %d OPEN, %d INFEASIBLE" % (
            mode, sum(v.startswith("DETERMINED") for v in vs), vs.count("OPEN"), vs.count("INFEASIBLE")))
    iso = check.get("isolation")
    if iso:
        parts.append("%d failing clause(s), %d failing init, lemma or probe on the code" % (
            sum(c["verdict"] == "FAILED" for c in iso["clauses"]), sum(m["verdict"] == "FAILED" for m in iso.get("methods") or [])))
    if check.get("errors"):
        parts.append("errors: %s" % "; ".join(check["errors"]))
    return "; ".join(parts) or "no cells"


def scenarios_md(cells, notes):
    out = ["# Scenarios", ""]
    if notes.strip():
        out += [notes.strip(), ""]
    out += ["Each scenario starts from the empty module and runs the steps that its text names. In the outcomes, `r` (or",
            "`b`) is the result of the last step, and `t1`, `t2` are tokens that earlier steps returned. Pick the outcome",
            "that the team's text gives.", "",
            "| ID | Scenario | Outcomes to pick from |", "|---|---|---|"]
    for c in cells:
        outs = "<br>".join("`%s`: `%s`" % (o["name"], " ".join(o["expr"].split())) for o in c["outcomes"])
        out.append("| %s | %s | %s |" % (c["id"], (c.get("title") or "").replace("|", "\\|"), outs.replace("|", "\\|")))
    return "\n".join(out) + "\n"


def answers_form(name, cells):
    return {"format": "kv-answers/1", "author": name,
            "answers": [{"cell": c["id"], "outcome": None, "text": "", "status": None, "sources": []} for c in cells]}


def build_phase2(run, name, cells, notes):
    box1, box2 = run.box(name, 1), run.box(name, 2)
    # Phase 2 does not edit the draft (DESIGN-C.md 2.2 step 5): the phase-1 box becomes read-only, and the phase-2
    # box holds no file of it. The frozen copy in drafts/ is what the later steps use.
    for f in os.listdir(box1):
        p = os.path.join(box1, f)
        if os.path.isfile(p):
            os.chmod(p, stat.S_IRUSR | stat.S_IRGRP | stat.S_IROTH)
    remove_tree(box2)
    h = os.path.join(run.harness(name), "phase2")
    remove_tree(h)
    os.makedirs(h)
    write(os.path.join(box2, "TASK.md"), TASK2.format(budget=CHECK_BUDGET))
    shutil.copy(os.path.join(run.snapshot, "SOURCES.md"), box2)
    shutil.copy(run.path("tool", "LANGUAGE.md"), box2)
    write(os.path.join(box2, "SCENARIOS.md"), scenarios_md(cells, notes))
    dump(os.path.join(box2, "ANSWERS.json"), answers_form(name, cells))
    write(os.path.join(box2, "check.sh"), check_sh(run, h, "answers"), executable=True)
    st = run.drafter(name)
    st["phase2"] = {"status": "answering", "rounds": 0, "attempts": []}
    run.save_drafter(name, st)
    text = prompt(box2)
    write(os.path.join(run.harness(name), "prompt-phase2.txt"), text)
    return box2, text


def cmd_cells(args):
    run = Run(args.run)
    passed = run.state()["passed"]
    if not passed:
        raise Precondition("no draft has passed collection yet")
    cdir = run.path("cells")
    target = os.path.join(cdir, "scenario.cells")
    if os.path.exists(target):
        raise Precondition("%s exists: new cells would leave the answers to the old ones without their cells" % target)
    os.makedirs(cdir, exist_ok=True)
    first = passed[0]
    if args.cells:
        shutil.copy(args.cells, target)
        source = "the cell file %s" % os.path.abspath(args.cells)
    else:
        sidecar = os.path.join(run.drafts(first), "sidecar", run.variant)
        code, out, err = run_cmd([run.kv, "spec", "cells", "--out", target, sidecar], 600)
        if code != 0:
            raise InputError("kv spec cells on the draft of %s: exit %s: %s" % (first, code, (err or out).strip()[-600:]))
        source = "kv spec cells on the draft of %s (the first that passed collection)" % first
    notes = read(args.notes) if args.notes else ""
    write(os.path.join(cdir, "notes.md"), notes)
    print("cells: %s" % source)
    checks = {}
    for name in passed:
        checks[name] = spec_check(run, os.path.join(run.drafts(name), "sidecar", run.variant), os.path.join(run.drafts(name), "check"),
                                  cells=target, code=run.code)
        print("  draft %s: %s" % (name, check_summary(checks[name])))
    if run.cfg.get("reference"):
        ref = spec_check(run, run.cfg["reference"], run.path("reference", "check"), cells=target, code=run.code, draft=False)
        print("  reference %s: %s" % (run.cfg["reference"], check_summary(ref)))
    cells = checks[first].get("cell_list") or []
    if not cells:
        raise InputError("the check of %s lists no cells: %s" % (first, "; ".join(checks[first].get("errors") or [])))
    dump(os.path.join(cdir, "cells.json"), {"source": source, "cells": cells})
    cost = None
    for name in passed:
        box2, text = build_phase2(run, name, cells, notes)
        if cost is None:
            files = {f: len(read(os.path.join(box2, f))) for f in sorted(os.listdir(box2))}
            cost = {"cells": len(cells), "boundary": sum(1 for c in cells if c.get("boundary")), "answers": len(cells),
                    "box_chars": files, "total_chars": sum(files.values()),
                    "cell_chars": files["SCENARIOS.md"] + files["ANSWERS.json"]}
            dump(os.path.join(cdir, "cost.json"), cost)
        print("phase-2 box of %s: %s" % (name, box2))
        print("prompt (also in %s):" % os.path.join(run.harness(name), "prompt-phase2.txt"))
        print(text, end="")
    print("phase-2 box size: %d cells (%d boundary), %d characters (SCENARIOS.md and ANSWERS.json: %d); %s" % (
        cost["cells"], cost["boundary"], cost["total_chars"], cost["cell_chars"], os.path.join(cdir, "cost.json")))
    return 0


# ---- questions (C4) ----

def verdict(text):
    """('DETERMINED', {outcomes}) | ('OPEN', None) | ('INFEASIBLE', None) | ('ERROR', None)."""
    m = re.fullmatch(r"DETERMINED\((.*)\)", text or "")
    if m:
        return "DETERMINED", frozenset(m.group(1).split("|"))
    if text in ("OPEN", "INFEASIBLE"):
        return text, None
    return "ERROR", None


def short(text):
    """A verdict as a table shows it: the outcome(s), open, infeasible or error."""
    kind, outs = verdict(text)
    return "|".join(sorted(outs)) if kind == "DETERMINED" else kind.lower()


class Draft:
    def __init__(self, name, check_path, answers_path, mode):
        self.name = name
        check = load(check_path)
        modes = check.get("cells") or {}
        if mode not in modes:
            raise InputError("%s: %s has no mode %s cells" % (name, check_path, mode))
        if modes[mode].get("error"):
            raise InputError("%s: mode %s of %s counts for nothing: %s" % (name, mode, check_path, modes[mode]["error"]))
        self.cells = modes[mode]["cells"]
        self.cell_list = {c["id"]: c for c in check.get("cell_list") or []}
        if set(self.cell_list) != set(self.cells):
            raise InputError("%s: %s has no cell_list for its cells (a kv spec check from before C4?)" % (name, check_path))
        ans = load(answers_path)
        if not isinstance(ans, dict) or ans.get("format") != "kv-answers/1":
            raise InputError("%s: %s is not kv-answers/1" % (name, answers_path))
        self.answers, errors = answers_rows(ans, self.cell_list)
        if errors:
            raise InputError("%s: %s: %s" % (name, answers_path, errors[0]))


def rules_of(row, drafts, reference, boundary, left_open):
    """The rules of DESIGN-C.md 3.3 (with JC1, and rule 6 of 10.6 finding 3) that make scenario row `row` a question.
    LEFT_OPEN holds the rows that earlier decisions leave open."""
    formal = drafts[0].answers[row]["formal_cell"]
    st = [d.answers[row]["status"] for d in drafts]
    vs = [verdict(d.cells[formal]) for d in drafts]
    hit = []
    if (len(drafts) == 1 and st[0] in ("open", "assumed")) or (len(drafts) > 1 and ("open" in st or st.count("assumed") >= 2)):
        hit.append(1)
    if len(drafts) > 1:
        def differ(a, b):
            if a[0] == "DETERMINED" and b[0] == "DETERMINED":
                return a[1] != b[1]
            return (a[0] == "INFEASIBLE") != (b[0] == "INFEASIBLE") and "ERROR" not in (a[0], b[0])
        if any(differ(a, b) for i, a in enumerate(vs) for b in vs[i + 1:]):
            hit.append(2)
    for d, (kind, outs) in zip(drafts, vs):
        a = d.answers[row]
        if kind == "DETERMINED" and (a["status"] == "open" or (a.get("outcome") is not None and a["outcome"] not in outs)):
            hit.append(3)
            break
    if reference is not None:
        r = reference.get(formal)
        if r is not None and verdict(r)[0] in ("DETERMINED", "INFEASIBLE"):
            if any(d.cells[formal] not in (r, "OPEN") and verdict(d.cells[formal])[0] != "ERROR" for d in drafts):
                hit.append(4)
    if boundary.get(formal):
        hit.append(5)
    if (reference is not None and reference.get(formal) == "OPEN") or row in left_open:
        if any(kind == "DETERMINED" for kind, _ in vs):
            hit.append(6)
    return hit


def check_groups(groups, questions, rows):
    """The errors of a group file: each question cell in exactly one group, and only question cells in groups."""
    errors = []
    if groups.get("format") != "kv-groups/1":
        errors.append("the group file is not kv-groups/1")
    seen = {}
    ids = set()
    for g in groups.get("groups") or []:
        gid = g.get("id")
        if gid in ids:
            errors.append("group %s is given twice" % gid)
        ids.add(gid)
        if not g.get("cells"):
            errors.append("group %s has no cells" % gid)
        for c in g.get("cells") or []:
            if c not in rows:
                errors.append("group %s lists %s, which is no cell" % (gid, c))
            elif c not in questions:
                errors.append("group %s lists %s, which is no question" % (gid, c))
            if c in seen:
                errors.append("%s is in two groups: %s and %s" % (c, seen[c], gid))
            else:
                seen[c] = gid
    for c in questions:
        if c not in seen:
            errors.append("question %s is in no group" % c)
    return errors


def group_options(cells, drafts, reference):
    """The options of a group (DESIGN-C.md 3.6): each distinct tuple of the cells' verdicts, from the reference and the
    drafts, lettered A, B, ...: [(letter, {cell: short verdict}, [who])]."""
    formal = {c: drafts[0].answers[c]["formal_cell"] for c in cells}
    sources = ([("reference", reference)] if reference is not None else []) + [("draft " + d.name, d.cells) for d in drafts]
    options = []
    for who, cells_of in sources:
        key = tuple(short(cells_of.get(formal[c])) for c in cells)
        for o in options:
            if o[0] == key:
                o[1].append(who)
                break
        else:
            options.append((key, [who]))
    return [(chr(ord("A") + i), dict(zip(cells, key)), who) for i, (key, who) in enumerate(options)]


def outcome_rows(res):
    """The bundle rows of RUN/outcomes/G.json: one row per implementation, one column per option."""
    letters = sorted(res["options"])
    out = ["", "Results per option on implementations (measured: `kv verify --draft` of a draft fixed to each option; "
           "DESIGN-C.md 3.6):", ""]
    for letter in letters:
        o = res["options"][letter]
        if o.get("undecided"):
            out.append("- Option %s: the draft %s does not decide the cells as option %s (%s), so it was not run." % (
                letter, o["draft"], letter, "; ".join(o["undecided"])))
    out += ["| Implementation | " + " | ".join(letters) + " |", "|---" * (1 + len(letters)) + "|"]
    for root in res["roots"]:
        out.append("| %s | %s |" % (root, " | ".join(
            run_summary(res["options"][l]["results"][root]) if root in res["options"][l].get("results", {}) else "not run" for l in letters)))
    return out + [""]


def bundle(groups, questions, drafts, reference, cell_list, outcomes=None):
    """The review bundle: one question per group (DESIGN-C.md 3.6)."""
    out = ["# Questions for review", "",
           "Each question is one decision. The options come from the data: each draft's verdict and, if given, the "
           "reference sidecar. A verdict `open` means the clauses do not decide the cell. Record each answer as a named "
           "probe with `reviewed` and `source` (DESIGN-C.md 3.5).", ""]
    for g in groups.get("groups") or []:
        cells = g["cells"]
        formal = {c: drafts[0].answers[c]["formal_cell"] for c in cells}
        out += ["## Q-%s %s" % (g["id"], g.get("title", "")), "", g.get("question", ""), ""]
        out.append("Cells: " + ", ".join("%s (rules %s)" % (c, ", ".join(map(str, questions[c]))) for c in cells))
        out += ["", "| Cell | Scenario | Outcomes |", "|---|---|---|"]
        for c in cells:
            info = cell_list[formal[c]]
            title = info.get("title") or ""
            if formal[c] != c:
                title += " (the steps of %s)" % formal[c]
            outs = "; ".join("%s: `%s`" % (o["name"], " ".join(o["expr"].split())) for o in info["outcomes"])
            out.append("| %s | %s | %s |" % (c, title.replace("|", "\\|"), outs.replace("|", "\\|")))
        out += ["", "Sources (the quotes in the authors' answers):"]
        quotes = {}
        for d in drafts:
            for c in cells:
                for s in d.answers[c].get("sources") or []:
                    quotes.setdefault((s["anchor"], s["quote"]), []).append("%s %s" % (d.name, c))
        if not quotes:
            out.append("- none")
        for (anchor, quote), who in quotes.items():
            out.append('- [%s] "%s" (%s)' % (anchor, quote, ", ".join(who)))
        out += ["", "Options (the outcome of each cell; from the verdicts):"]
        for letter, values, who in group_options(cells, drafts, reference):
            out.append("%s. %s: %s." % (letter, ", ".join("%s %s" % (c, values[c]) for c in cells), ", ".join(who)))
        res = (outcomes or {}).get(g["id"])
        if res:
            out += outcome_rows(res)
        else:
            out += ["", "Results per option on implementations (decision mutants, AI code): not measured.", ""]
        out += ["| Draft | " + " | ".join("%s verdict | %s answer" % (c, c) for c in cells) + " |",
                "|---" * (1 + 2 * len(cells)) + "|"]
        for d in drafts:
            row = []
            for c in cells:
                a = d.answers[c]
                row += [short(d.cells[formal[c]]), "%s (%s)" % (a.get("outcome") or "none", a["status"])]
            out.append("| %s | %s |" % (d.name, " | ".join(row)))
        out.append("")
    return "\n".join(out)


def run_inputs(run):
    """The checks, answers and reference check of a run's drafters whose answers are collected."""
    names = [n for n in run.state()["passed"] if os.path.exists(os.path.join(run.drafts(n), "answers.json"))]
    if not names:
        raise Precondition("no drafter's answers are collected in %s yet" % run.dir)
    checks = {n: os.path.join(run.drafts(n), "check", "check.json") for n in names}
    answers = {n: os.path.join(run.drafts(n), "answers.json") for n in names}
    ref = run.path("reference", "check", "check.json")
    return checks, answers, ref if os.path.exists(ref) else None


def run_outcomes(run):
    d = run.path("outcomes")
    if not os.path.isdir(d):
        return {}
    return {f[:-5]: load(os.path.join(d, f)) for f in sorted(os.listdir(d)) if f.endswith(".json")}


def questions_cmd(args, quiet=False):
    run = Run(args.run) if getattr(args, "run", None) else None
    if run:
        if args.check or args.answers or args.reference:
            raise InputError("--run takes the checks, answers and reference of the run; drop --check, --answers and --reference")
        checks, answers, reference_path = run_inputs(run)
    else:
        checks = pairs(args.check, "--check")
        answers = pairs(args.answers, "--answers")
        reference_path = args.reference
        if not checks:
            raise InputError("questions needs --run RUN or at least one --check NAME=CHECK.json")
        if set(checks) != set(answers):
            raise InputError("each draft needs --check and --answers: %s" % ", ".join(sorted(set(checks) ^ set(answers))))
    say = (lambda *a: None) if quiet else print
    mode = args.mode
    if mode is None:
        mode = "a" if all("a" in (load(p).get("cells") or {}) for p in checks.values()) else "b"
    drafts = [Draft(n, checks[n], answers[n], mode) for n in checks]
    cell_list = drafts[0].cell_list
    for d in drafts[1:]:
        if d.cell_list != cell_list or set(d.answers) != set(drafts[0].answers):
            raise InputError("drafts %s and %s were checked or answered on different cells" % (drafts[0].name, d.name))
        if any(d.answers[r]["formal_cell"] != drafts[0].answers[r]["formal_cell"] for r in d.answers):
            raise InputError("drafts %s and %s map an answer to different formal cells" % (drafts[0].name, d.name))
    reference = None
    if reference_path:
        ref = load(reference_path).get("cells") or {}
        if mode not in ref or ref[mode].get("error"):
            raise InputError("the reference %s has no mode %s cells" % (reference_path, mode))
        reference = ref[mode]["cells"]
    boundary = {c: bool(info.get("boundary")) for c, info in cell_list.items()}
    rows = list(drafts[0].answers)
    refs = ((run.cfg.get("decisions") or []) if run else []) + (args.decisions_ref or [])
    left = left_open(refs)
    unknown = [r for r in left if r not in drafts[0].answers]
    if unknown:
        raise InputError("the earlier decisions leave %s open, which is no scenario of these drafts (decisions of another cell file?)" % ", ".join(unknown))
    questions = {}
    for row in rows:
        hit = rules_of(row, drafts, reference, boundary, left)
        if hit:
            questions[row] = hit
    rule1 = "the author answered open or assumed" if len(drafts) == 1 else "one author answered open, or two or more assumed"
    rule6 = (["the reference's OPEN cells"] if reference is not None else []) + (["%d cell(s) that earlier decisions leave open" % len(left)] if refs else [])
    say("kv spec questions: %d draft(s) (%s), mode %s; rule 1: %s; rule 2 %s; rule 4 %s; rule 6 %s" % (
        len(drafts), ", ".join(d.name for d in drafts), mode, rule1,
        "on" if len(drafts) > 1 else "off (one draft)", "on (proxy: --reference)" if reference is not None else "off (no --reference)",
        "on (%s)" % ", ".join(rule6) if rule6 else "off (no --reference, no earlier decisions)"))
    for row, hit in questions.items():
        f = drafts[0].answers[row]["formal_cell"]
        say("  %s%s rules %s | answers %s | drafts %s%s" % (
            row, "" if f == row else " (cell %s)" % f, ",".join(map(str, hit)),
            " ".join("%s=%s/%s" % (d.name, d.answers[row].get("outcome") or "none", d.answers[row]["status"]) for d in drafts),
            " ".join("%s=%s" % (d.name, short(d.cells[f])) for d in drafts),
            "" if reference is None else " | reference %s" % short(reference.get(f))))
    say("question cells: %d %s" % (len(questions), " ".join(questions)))
    result = {"mode": mode, "drafts": [d.name for d in drafts], "rule1": rule1, "left_open": left,
              "questions": [{"cell": r, "formal_cell": drafts[0].answers[r]["formal_cell"], "rules": h} for r, h in questions.items()]}
    code = 0
    groups_path = args.groups
    if run and not groups_path and os.path.exists(run.path("groups.json")):
        groups_path = run.path("groups.json")
    bundle_path = args.bundle or (run.path("bundle.md") if run else None)
    if groups_path:
        groups = load(groups_path)
        if run and os.path.abspath(groups_path) != run.path("groups.json"):
            dump(run.path("groups.json"), groups)
        errors = check_groups(groups, questions, rows)
        result["group_errors"] = errors
        if errors:
            code = 1
            say("group check: FAILED")
            for e in errors:
                say("  " + e)
        else:
            say("group check: OK, %d group(s)" % len(groups.get("groups") or []))
            result["groups"] = [dict(g, options=[{"letter": l, "cells": v, "who": w} for l, v, w in group_options(g["cells"], drafts, reference)])
                                for g in groups.get("groups") or []]
            if bundle_path:
                write(bundle_path, bundle(groups, questions, drafts, reference, cell_list, run_outcomes(run) if run else None))
                say("review bundle: %s" % bundle_path)
    elif args.bundle:
        raise InputError("--bundle needs --groups")
    json_path = args.json or (run.path("questions.json") if run else None)
    if json_path:
        dump(json_path, result)
    return code


# ---- decisions and checks of decided cells ----

def decided_errors(check, expected, exact):
    """The cells of EXPECTED (formal cell -> outcome) that CHECK does not decide that way, in each mode it ran, as
    "CELL mode M: GOT, not WANT". EXACT: the verdict as the bundle shows it must equal the expected text (an option);
    else the cell must be DETERMINED with the expected outcome among its outcomes (a person's decision), or OPEN if
    the decision leaves it open."""
    modes = check.get("cells") or {}
    if not modes:
        return ["the check ran no cells: %s" % ("; ".join(check.get("errors") or []) or "no error given")]
    errors = []
    for mode, m in sorted(modes.items()):
        if m.get("error"):
            errors.append("mode %s counts for nothing: %s" % (mode, m["error"]))
            continue
        for cell, want in expected.items():
            got = m["cells"].get(cell)
            kind, outs = verdict(got)
            if not (short(got) == want if exact or want == LEFT_OPEN else kind == "DETERMINED" and want in outs):
                errors.append("%s mode %s: %s, not %s" % (cell, mode, short(got), want))
    return errors


def run_questions(run):
    p = run.path("questions.json")
    q = load(p) if os.path.exists(p) else {}
    if not q.get("groups"):
        raise Precondition("%s has no groups: run questions --run %s --groups FILE first" % (p, run.dir))
    return q


def left_open(paths):
    """The scenario rows that the decision files PATHS (kv-decisions/1) leave open, in order: the cells whose outcome
    is "open". A group decided "open" must name its cells here; RUN/align/decisions.json names every cell."""
    rows = []
    for path in paths:
        data = load(path)
        if not isinstance(data, dict) or data.get("format") != "kv-decisions/1":
            raise InputError("%s is not kv-decisions/1" % path)
        for d in data.get("decisions") or []:
            cells = d.get("cells") or {}
            if d.get("option") == LEFT_OPEN and not cells:
                raise InputError('%s: group %s is decided "open" and names no cell; give its cells in "cells" (the run\'s align/decisions.json names them)' % (path, d.get("group")))
            rows += [c for c, o in cells.items() if o == LEFT_OPEN and c not in rows]
    return rows


def resolve_decisions(q, files, cells):
    """The decisions of FILES (kv-decisions/1) as {group, option, note, cells: {row: outcome}}, one per group of Q."""
    groups = {g["id"]: g for g in q["groups"]}
    formal = {x["cell"]: x["formal_cell"] for x in q["questions"]}
    got = {}
    for path, data in files:
        if not isinstance(data, dict) or data.get("format") != "kv-decisions/1":
            raise InputError("%s is not kv-decisions/1" % path)
        for d in data.get("decisions") or []:
            gid = d.get("group")
            if gid not in groups:
                raise InputError("%s: a decision for %r, which is no group of the bundle" % (path, gid))
            if gid in got:
                raise InputError("%s: group %s is decided twice" % (path, gid))
            g = groups[gid]
            values = {}
            if d.get("option") == LEFT_OPEN:
                values.update({c: LEFT_OPEN for c in g["cells"]})
            elif d.get("option") is not None:
                option = next((o for o in g["options"] if o["letter"] == d["option"]), None)
                if option is None:
                    raise InputError("%s: group %s has no option %r" % (path, gid, d["option"]))
                values.update(option["cells"])
            values.update(d.get("cells") or {})
            extra = [c for c in values if c not in g["cells"]]
            missing = [c for c in g["cells"] if c not in values]
            if extra or missing:
                raise InputError("%s: group %s decides %s; its cells are %s" % (path, gid, ", ".join(sorted(values)), ", ".join(g["cells"])))
            for c, o in values.items():
                outs = [x["name"] for x in cells[formal[c]]["outcomes"]]
                if o not in outs and o != LEFT_OPEN:
                    raise InputError('%s: group %s gives %s the outcome %r, which is no outcome of cell %s (%s); give it in "cells"' % (
                        path, gid, c, o, formal[c], ", ".join(outs)))
            got[gid] = {"group": gid, "option": d.get("option"), "note": d.get("note", ""), "cells": values}
    missing = [g for g in groups if g not in got]
    if missing:
        raise InputError("no decision for group(s) %s" % ", ".join(missing))
    return [got[g] for g in groups]


def scenario_title(cells, formal, row):
    """The steps of scenario ROW (formal cell FORMAL) as DECISIONS.md and FEEDBACK show them."""
    return (cells[formal].get("title") or "") + ("" if formal == row else " (the steps of %s)" % formal)


def decisions_md(q, decisions, cells, notes):
    groups = {g["id"]: g for g in q["groups"]}
    formal = {x["cell"]: x["formal_cell"] for x in q["questions"]}
    out = ["# Decisions", "", "A person on the team decided these points. Each decision lists the scenarios it settles and the outcome",
           "chosen for each. A scenario starts from the empty module, as a probe does; the outcomes are written in the",
           "notation of LANGUAGE.md: `r` (or `b`) is the result of the last step, `t1`, `t2` are tokens of earlier steps.", ""]
    if any(o == LEFT_OPEN for d in decisions for o in d["cells"].values()):
        out += ["A scenario whose chosen outcome is \"left open\" must stay undecided: the person requires none of its outcomes,",
                "so no clause may decide it.", ""]
    if notes.strip():
        out += [notes.strip(), ""]
    for d in decisions:
        g = groups[d["group"]]
        out += ["## %s %s" % (g["id"], g.get("title", "")), ""]
        if g.get("question"):
            out += [g["question"], ""]
        if d["note"]:
            out += ["Note of the person: %s" % d["note"], ""]
        out += ["| Scenario | Steps | Chosen outcome |", "|---|---|---|"]
        for c, o in d["cells"].items():
            title = scenario_title(cells, formal[c], c).replace("|", "\\|")
            if o == LEFT_OPEN:
                out.append("| %s | %s | left open: no clause may decide it |" % (c, title))
                continue
            expr = next(x["expr"] for x in cells[formal[c]]["outcomes"] if x["name"] == o)
            out.append("| %s | %s | `%s`: `%s` |" % (c, title, o, " ".join(expr.split()).replace("|", "\\|")))
        out.append("")
    return "\n".join(out)


# ---- align ----

def cmd_align(args):
    run = Run(args.run)
    if args.check:
        if args.decisions or args.draft or args.force:
            raise InputError("align --check takes no other option")
        return align_check(run)
    if not args.decisions:
        raise InputError("align needs --decisions FILE (or --check)")
    q = run_questions(run)
    passed = run.state()["passed"]
    name = args.draft or (passed[0] if passed else None)
    if name not in passed:
        raise Precondition("%s is no draft that passed collection (%s)" % (name, ", ".join(passed) or "none"))
    box = run.path("align", "box")
    if os.path.exists(box) and not args.force:
        raise Precondition("%s exists; --force builds it again and drops the aligned draft" % box)
    cells = run.cell_list()
    decisions = resolve_decisions(q, [(f, load(f)) for f in args.decisions], cells)
    remove_tree(run.path("align"))
    dump(run.path("align", "decisions.json"), {"format": "kv-decisions/1", "draft": name, "decisions": decisions})
    h = run.path("harness", "_align")
    remove_tree(h)
    os.makedirs(h)
    write(os.path.join(box, "TASK.md"), TASK_ALIGN.format(subject=run.cfg["subject"], budget=CHECK_BUDGET))
    shutil.copy(os.path.join(run.drafts(name), "DRAFT.kv"), box)
    os.chmod(os.path.join(box, "DRAFT.kv"), 0o644)
    write(os.path.join(box, "DECISIONS.md"), decisions_md(q, decisions, cells, read(run.path("cells", "notes.md"))))
    shutil.copy(os.path.join(run.snapshot, "SOURCES.md"), box)
    shutil.copy(run.path("tool", "LANGUAGE.md"), box)
    shutil.copy(run.path("tool", "SKELETON.kv"), box)
    write(os.path.join(box, "check.sh"), check_sh(run, h, "draft"), executable=True)
    text = prompt(box)
    write(os.path.join(h, "prompt.txt"), text)
    print("alignment box (draft %s, %d decision(s)): %s" % (name, len(decisions), box))
    print("prompt (also in %s):" % os.path.join(h, "prompt.txt"))
    print(text, end="")
    return 0


def align_check(run):
    p = run.path("align", "decisions.json")
    if not os.path.exists(p):
        raise Precondition("no alignment box in %s: run align --decisions FILE first" % run.dir)
    dec = load(p)
    q = run_questions(run)
    text = read(run.path("align", "box", "DRAFT.kv"))
    work = run.path("align", "work")
    remove_tree(work)
    result = {"draft": dec["draft"], "grammar": grammar_errors(run, text, os.path.join(work, "grammar"))}
    print("align check: draft %s, %d decision(s)" % (dec["draft"], len(dec["decisions"])))
    if result["grammar"]:
        print("  grammar or type errors (send them to the alignment agent):")
        for e in result["grammar"]:
            print("    " + e)
        dump(run.path("align", "result.json"), result)
        return 1
    side, notes, origin = assemble(run, text, run.path("align", "sidecar"))
    check = spec_check(run, side, run.path("align", "check"), cells=run.path("cells", "scenario.cells"), code=run.code)
    formal = {x["cell"]: x["formal_cell"] for x in q["questions"]}
    rows = [(c, o) for d in dec["decisions"] for c, o in d["cells"].items()]
    expected = {formal[c]: o for c, o in rows if o != LEFT_OPEN}
    left = {formal[c]: o for c, o in rows if o == LEFT_OPEN}
    result["undecided"] = decided_errors(check, expected, exact=False)
    modes = ", ".join(sorted(check.get("cells") or {})) or "none"
    if result["undecided"]:
        print("  decided cells: %d of %d NOT as decided (modes %s):" % (len({e.split()[0] for e in result["undecided"]}), len(expected), modes))
        for e in result["undecided"]:
            print("    " + e)
    else:
        print("  decided cells: all %d DETERMINED as decided in mode(s) %s" % (len(expected), modes))
    result["open_decided"] = decided_errors(check, left, exact=False) if left else []
    if result["open_decided"]:
        print("  cells decided open: %d of %d NOT OPEN (modes %s):" % (len({e.split()[0] for e in result["open_decided"]}), len(left), modes))
        for e in result["open_decided"]:
            print("    " + e)
    elif left:
        print("  cells decided open: all %d OPEN in mode(s) %s" % (len(left), modes))
    before = (load(os.path.join(run.drafts(dec["draft"]), "check", "check.json")).get("cells") or {})
    result["changed"] = ["%s mode %s: %s -> %s" % (c, mode, short(before[mode]["cells"].get(c)), short(v))
                         for mode, m in sorted((check.get("cells") or {}).items())
                         if mode in before and not m.get("error") and not before[mode].get("error")
                         for c, v in m["cells"].items() if c not in expected and c not in left and before[mode]["cells"].get(c) != v]
    print("  other cells whose verdict changed from draft %s: %s" % (dec["draft"], "none" if not result["changed"] else ""))
    for c in result["changed"]:
        print("    " + c)
    iso = check.get("isolation")
    if iso:
        result["failing_clauses"] = ["%s %s" % (c["action"], c["clause"]) for c in iso["clauses"] if c["verdict"] == "FAILED"]
        print("  clause isolation on the code: %d failing clause(s)%s" % (len(result["failing_clauses"]), "".join("\n    " + c for c in result["failing_clauses"])))
        methods = iso.get("methods") or []
        result["methods_not_proven"] = ["%s %s: %s" % (m["label"], m["verdict"], m["detail"]) for m in methods if m["verdict"] != "PROVEN"]
        print("  init, lemmas and probes on the code: %d of %d not PROVEN%s" % (
            len(result["methods_not_proven"]), len(methods), "".join("\n    " + m for m in result["methods_not_proven"])))
    rec = None
    if run.code:
        rec = verify_draft(run, run.path("align", "sidecar"), run.cfg["root"], [], os.path.join(work, "verify"))
        result["reference"] = rec
        print("  kv verify --draft on the reference implementation: %s" % run_summary(rec))
    probes = set(re.findall(r"(?m)^\s*probe\s+decision_(\w+)", text))
    chosen = [d["group"] for d in dec["decisions"] if any(o != LEFT_OPEN for o in d["cells"].values())]
    result["missing_probes"] = ["decision_" + g for g in chosen if g not in probes]
    print("  decision probes (DESIGN-C.md 10 UC5): %s" % ("all present" if not result["missing_probes"] else "missing " + ", ".join(result["missing_probes"])))
    result["source_notes"] = notes
    failed = bool(result["undecided"] or result["open_decided"] or (rec and rec["verdict"] != "PROVEN"))
    if failed:
        bad = {m.group(1) for e in result["undecided"] + result["open_decided"] for m in [CELL_ERROR.match(e)] if m}
        result["feedback"] = loop_back(run, feedback_lines(run, rows, formal, bad, rec, origin))
    dump(run.path("align", "result.json"), result)
    print("  aligned sidecar: %s" % run.path("align", "sidecar"))
    return 1 if failed else 0


# A cell error of decided_errors: "CELL mode M: GOT, not WANT".
CELL_ERROR = re.compile(r"^(\S+) mode \w+: ")


def feedback_lines(run, rows, formal, bad, rec, origin):
    """The lines of FEEDBACK-<n>.md: each decided scenario of ROWS ((row, outcome) in DECISIONS.md order) whose formal
    cell is in BAD, as its steps and the decided outcome; then the error lines of the `kv verify` record REC if it is
    not PROVEN, with their positions in DRAFT.kv (ORIGIN). No verdict and no table (DESIGN-C.md 10.6 finding 5)."""
    cells = run.cell_list()
    out = []
    for c, o in rows:
        if formal[c] not in bad:
            continue
        title = scenario_title(cells, formal[c], c)
        if o == LEFT_OPEN:
            out.append("Scenario %s (%s): no clause may decide its outcome; it must stay undecided." % (c, title))
        else:
            expr = next(x["expr"] for x in cells[formal[c]]["outcomes"] if x["name"] == o)
            out.append("Scenario %s (%s): the clauses must give the outcome `%s`: `%s`." % (c, title, o, " ".join(expr.split())))
    if rec and rec["verdict"] != "PROVEN":
        out += [draft_positions(run, e, origin) for e in rec.get("errors") or []]
    return list(dict.fromkeys(out))


def loop_back(run, lines):
    """The loop-back of `align --check` (DESIGN-C.md 10.6 finding 5): writes LINES into the box file FEEDBACK-<n>.md,
    as collect writes CORRECTION-<n>.md, at most ROUNDS rounds, and prints the prompt for the alignment agent. The
    round number, or None if there is no line or no round left."""
    if not lines:
        print("  loop-back: none: the failure has no error line for the alignment agent")
        return None
    h = run.path("harness", "_align")
    state = os.path.join(h, "feedback.json")
    n = load(state)["rounds"] if os.path.exists(state) else 0
    if n >= ROUNDS:
        print("  loop-back: none left: the %d feedback rounds are used" % ROUNDS)
        return None
    n += 1
    dump(state, {"rounds": n})
    box = run.path("align", "box")
    f = os.path.join(box, "FEEDBACK-%d.md" % n)
    write(f, "# Feedback, round %d of %d\n\nThe team's tool found these problems in DRAFT.kv. Fix them in DRAFT.kv. Change only what the fixes "
             "need, and keep following TASK.md and DECISIONS.md.\n\n%s\n" % (n, ROUNDS, "\n".join("- " + l for l in lines)))
    text = "In the directory %s, FEEDBACK-%d.md lists problems of DRAFT.kv. Fix them as it says. Work only inside %s.\n" % (box, n, box)
    write(os.path.join(h, "prompt-feedback-%d.txt" % n), text)
    print("  loop-back: %d line(s), round %d of %d: %s" % (len(lines), n, ROUNDS, f))
    print("prompt for the alignment agent:")
    print(text, end="")
    return n


# ---- outcomes ----

def suite_of(mutants, variant):
    for suite in mutants.SUITES.values():
        for unit in suite.units:
            if suite.sidecar(unit) == variant:
                return suite, unit
    raise InputError("no suite of scripts/mutants.py has the variant sidecar %s" % variant)


def build_roots(run, work, mutants_file, impls):
    """The implementations of DESIGN-C.md 3.6: the reference (the run's checkout itself), each decision mutant and
    each given implementation (copies under WORK). {name: (root, binding adaptations)}."""
    mutants = load_module("mutants")
    suite, unit = suite_of(mutants, run.variant)
    edited = suite.edited(unit)
    roots = {"reference": (run.cfg["root"], [])}
    if mutants_file:
        data = load(mutants_file)
        if data.get("format") != "kv-decision-mutants/1":
            raise InputError("%s is not kv-decision-mutants/1" % mutants_file)
        for m in data.get("mutants") or []:
            dst = os.path.join(work, "dm-" + m["name"])
            mutants.copy_sources(suite, run.cfg["root"], dst, unit)
            path = os.path.join(dst, edited)
            text = read(path)
            for old, new in m["edits"]:
                if text.count(old) != 1:
                    raise InputError("decision mutant %s: an edit matches %d times (must be 1): %r" % (m["name"], text.count(old), old))
                text = text.replace(old, new)
            write(path, text)
            roots["dm:" + m["name"]] = (dst, [])
    aicode = load_module("aicode") if impls else None
    for name, d in impls.items():
        impl = os.path.join(d, os.path.basename(edited))
        if not os.path.exists(impl):
            raise InputError("--impl %s: no %s" % (name, impl))
        bindings_file = os.path.join(d, "bindings.txt")
        bindings = aicode.read_bindings(bindings_file) if os.path.exists(bindings_file) else []
        if any(b[0] == "source" for b in bindings):
            raise InputError("--impl %s: a 'source' adaptation moves files; outcomes does not support it" % name)
        dst = os.path.join(work, "impl-" + name)
        mutants.copy_sources(suite, run.cfg["root"], dst, unit)
        shutil.copy(impl, os.path.join(dst, edited))
        roots["impl:" + name] = (dst, bindings)
    return roots


def cmd_outcomes(args):
    run = Run(args.run)
    if not run.code:
        raise Precondition("the run has no code (run config \"code\"): outcomes runs implementations")
    q = run_questions(run)
    group = next((g for g in q["groups"] if g["id"] == args.group), None)
    if group is None:
        raise Precondition("the bundle has no group %s (groups: %s)" % (args.group, ", ".join(g["id"] for g in q["groups"])))
    options = {o["letter"]: o for o in group["options"]}
    drafts = pairs(args.option, "--option")
    if not drafts:
        raise InputError("outcomes needs --option LETTER=DRAFT.kv")
    unknown = sorted(set(drafts) - set(options))
    if unknown:
        raise InputError("group %s has no option %s (options: %s)" % (args.group, ", ".join(unknown), ", ".join(sorted(options))))
    formal = {x["cell"]: x["formal_cell"] for x in q["questions"]}
    base = run.path("outcomes", args.group)
    remove_tree(base)
    data = {"group": args.group, "cells": group["cells"], "options": {}, "roots": []}
    ready = []
    for letter, path in sorted(drafts.items()):
        side, _, _ = assemble(run, read(path), os.path.join(base, letter, "sidecar"))
        check = spec_check(run, side, os.path.join(base, letter, "check"), cells=run.path("cells", "scenario.cells"))
        expected = {formal[c]: options[letter]["cells"][c] for c in group["cells"]}
        undecided = decided_errors(check, expected, exact=True)
        data["options"][letter] = {"draft": os.path.abspath(path), "cells": options[letter]["cells"], "undecided": undecided, "results": {}}
        print("option %s (%s): %s" % (letter, ", ".join("%s %s" % kv for kv in options[letter]["cells"].items()),
                                      "the draft decides the cells so (mode b)" if not undecided else "NOT RUN: " + "; ".join(undecided)))
        if not undecided:
            ready.append(letter)
    roots = build_roots(run, os.path.join(base, "roots"), args.mutants, pairs(args.impl, "--impl"))
    data["roots"] = list(roots)
    jobs = [(letter, name) for letter in ready for name in roots]

    def one(job):
        letter, name = job
        root, bindings = roots[name]
        return verify_draft(run, os.path.join(base, letter, "sidecar"), root, bindings, os.path.join(base, letter, "runs", name.replace(":", "_")))
    start = time.time()
    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        for (letter, name), rec in zip(jobs, pool.map(one, jobs)):
            data["options"][letter]["results"][name] = rec
    data["seconds"] = round(time.time() - start, 1)
    out = run.path("outcomes", args.group + ".json")
    dump(out, data)
    print("%d kv verify run(s) in %.0f s; %s" % (len(jobs), data["seconds"], out))
    for line in outcome_rows(data)[1:]:
        print(line)
    if os.path.exists(run.path("groups.json")):
        questions_cmd(argparse.Namespace(run=run.dir, check=None, answers=None, reference=None, decisions_ref=None, mode=q["mode"], groups=None, bundle=None, json=None), quiet=True)
        print("review bundle: %s" % run.path("bundle.md"))
    return 1 if len(ready) < len(drafts) else 0


# ---- main ----

def main(argv):
    p = argparse.ArgumentParser(prog="spec.py", description="The spec drafting tool of DESIGN-C.md 7.1 (see the module docstring).")
    sub = p.add_subparsers(dest="command", required=True)
    t = sub.add_parser("task", help="the phase-1 drafting box of one drafter (creates the run on first use)")
    t.add_argument("--run", required=True)
    t.add_argument("--drafter", required=True)
    t.add_argument("--config", help="the run config (kv-spec-run/1); only when the run is created")
    t.add_argument("--env", help="build/kv/aicode-env.json of ./gradlew aicodeEnv; only when the run is created")
    t.add_argument("--force", action="store_true")
    c = sub.add_parser("collect", help="collect and check a drafter's box: phase 1 (draft) or phase 2 (answers)")
    c.add_argument("--run", required=True)
    c.add_argument("--drafter", required=True)
    s = sub.add_parser("cells", help="the scenario cells, the checks of the drafts and the phase-2 boxes")
    s.add_argument("--run", required=True)
    s.add_argument("--cells", help="a cell file to use instead of kv spec cells on the first draft")
    s.add_argument("--notes", help="a text that SCENARIOS.md and DECISIONS.md show first (for a hand-written cell file)")
    q = sub.add_parser("questions", help="the question rules, the group check and the review bundle (DESIGN-C.md 3.3)")
    q.add_argument("--run", help="take the checks, answers and reference of this run")
    q.add_argument("--check", action="append", help="NAME=CHECK.json of kv spec check --cells")
    q.add_argument("--answers", action="append", help="NAME=ANSWERS.json (kv-answers/1)")
    q.add_argument("--reference", help="CHECK.json of a sidecar whose clauses hold on the reference implementation (rule 4 proxy)")
    q.add_argument("--decisions-ref", action="append", help="DECISIONS.json (kv-decisions/1) of an earlier run: rule 6 asks again about the cells it leaves open")
    q.add_argument("--mode", choices=("a", "b"))
    q.add_argument("--groups", help="GROUPS.json (kv-groups/1)")
    q.add_argument("--bundle", help="write the review bundle (Markdown) here")
    q.add_argument("--json", help="write the result as JSON here")
    o = sub.add_parser("outcomes", help="the results per option of a group (DESIGN-C.md 3.6)")
    o.add_argument("--run", required=True)
    o.add_argument("--group", required=True)
    o.add_argument("--option", action="append", help="LETTER=DRAFT.kv: a draft fixed to that option of the group")
    o.add_argument("--mutants", help="decision mutants (kv-decision-mutants/1)")
    o.add_argument("--impl", action="append", help="NAME=DIR: an implementation (DIR holds its file and an optional bindings.txt)")
    o.add_argument("--jobs", type=int, default=3)
    a = sub.add_parser("align", help="the alignment box from the decisions, or --check of the aligned draft")
    a.add_argument("--run", required=True)
    a.add_argument("--decisions", action="append", help="kv-decisions/1 file(s)")
    a.add_argument("--draft", help="the drafter whose draft is aligned (default: the first that passed collection)")
    a.add_argument("--force", action="store_true")
    a.add_argument("--check", action="store_true")
    b = sub.add_parser("check-box", help="what a box's check.sh runs")
    b.add_argument("--run", required=True)
    b.add_argument("--kind", choices=sorted(CHECKED), required=True)
    b.add_argument("--file", required=True)
    b.add_argument("--work", required=True)
    args = p.parse_args(argv)
    commands = {"task": cmd_task, "collect": cmd_collect, "cells": cmd_cells, "questions": questions_cmd,
                "outcomes": cmd_outcomes, "align": cmd_align, "check-box": cmd_check_box}
    try:
        return commands[args.command](args)
    except InputError as e:
        print("spec.py: %s" % e, file=sys.stderr)
        return 2
    except Precondition as e:
        print("spec.py: %s" % e, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
