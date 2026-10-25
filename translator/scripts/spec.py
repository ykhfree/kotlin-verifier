#!/usr/bin/env python3
"""Spec drafting helpers of DESIGN-C.md 7.1. C4 has one subcommand: `questions`.

questions (DESIGN-C.md 3.3, 3.6; user decision JC1 in 10):
  python3 -I spec.py questions --check NAME=CHECK.json --answers NAME=ANSWERS.json [--check ... --answers ...]
                               [--reference CHECK.json] [--mode a|b] [--groups GROUPS.json]
                               [--bundle OUT.md] [--json OUT.json]

  CHECK.json is the check.json of `kv spec check --cells` on draft NAME; ANSWERS.json is its author's scenario answers
  (format kv-answers/1, below). Each NAME needs both. The rules pick the question cells:
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
  Mode a (with the hole) is used if every check.json has it, else mode b; --mode picks one.
  With --groups, every question cell must be in exactly one group, and a group may list question cells only.
  --bundle writes the review bundle: one question per group in the shape of DESIGN-C.md 3.6.
  Exit 0; 1 if the group check fails; 2 for a usage or input error.

Answers (kv-answers/1), one file per author:
  {"format": "kv-answers/1", "author": "haiku",
   "answers": [{"cell": "C04", "text": "null", "outcome": "null", "status": "open",
                "sources": [{"anchor": "README.md#local-lock-map-size/2", "quote": "..."}]},
               {"cell": "C09", "formal_cell": "C08", ...}]}
  `cell` is the scenario ID the author answered; `formal_cell` names the cell of the cell file whose steps it has, if
  that is another cell (the pilot's C09 has the steps of C08). `outcome` is one outcome name of that cell, or null if
  the author picked none. `status` is settled, assumed or open. Every cell of the cell file needs one answer.

Groups (kv-groups/1), written by an LLM in C5:
  {"format": "kv-groups/1",
   "groups": [{"id": "D1", "title": "Expiry boundary", "question": "...?", "cells": ["C04", "C15"]}]}
"""
import argparse
import json
import re
import sys

STATUSES = ("settled", "assumed", "open")


class InputError(Exception):
    pass


def load(path):
    try:
        with open(path) as f:
            return json.load(f)
    except (OSError, ValueError) as e:
        raise InputError("cannot read %s: %s" % (path, e))


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
        if ans.get("format") != "kv-answers/1":
            raise InputError("%s: %s is not kv-answers/1" % (name, answers_path))
        self.answers = {}
        for a in ans.get("answers") or []:
            row = a.get("cell")
            if row in self.answers:
                raise InputError("%s: %s answers %s twice" % (name, answers_path, row))
            formal = a.get("formal_cell") or row
            if formal not in self.cells:
                raise InputError("%s: %s answers %s, which is no cell of the check (formal cell %s)" % (name, answers_path, row, formal))
            if a.get("status") not in STATUSES:
                raise InputError("%s: %s: %s has status %r, not one of %s" % (name, answers_path, row, a.get("status"), ", ".join(STATUSES)))
            outs = [o["name"] for o in self.cell_list[formal]["outcomes"]]
            if a.get("outcome") is not None and a["outcome"] not in outs:
                raise InputError("%s: %s: %s names outcome %r; cell %s has %s" % (name, answers_path, row, a["outcome"], formal, ", ".join(outs)))
            self.answers[row] = dict(a, formal_cell=formal)
        missing = [c for c in self.cells if c not in self.answers]
        if missing:
            raise InputError("%s: %s has no answer for %s" % (name, answers_path, ", ".join(missing)))


def rules_of(row, drafts, reference, boundary):
    """The rules of DESIGN-C.md 3.3 (with JC1) that make scenario row `row` a question."""
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


def bundle(groups, questions, drafts, reference, cell_list):
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
        options = []
        sources = ([("reference", reference)] if reference is not None else []) + [("draft " + d.name, d.cells) for d in drafts]
        for who, cells_of in sources:
            key = tuple(short(cells_of.get(formal[c])) for c in cells)
            for o in options:
                if o[0] == key:
                    o[1].append(who)
                    break
            else:
                options.append((key, [who]))
        for i, (key, who) in enumerate(options):
            out.append("%s. %s: %s." % (chr(ord("A") + i), ", ".join("%s %s" % (c, v) for c, v in zip(cells, key)), ", ".join(who)))
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


def questions_cmd(args):
    checks = pairs(args.check, "--check")
    answers = pairs(args.answers, "--answers")
    if not checks:
        raise InputError("questions needs at least one --check NAME=CHECK.json")
    if set(checks) != set(answers):
        raise InputError("each draft needs --check and --answers: %s" % ", ".join(sorted(set(checks) ^ set(answers))))
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
    if args.reference:
        ref = load(args.reference).get("cells") or {}
        if mode not in ref or ref[mode].get("error"):
            raise InputError("the reference %s has no mode %s cells" % (args.reference, mode))
        reference = ref[mode]["cells"]
    boundary = {c: bool(info.get("boundary")) for c, info in cell_list.items()}
    rows = list(drafts[0].answers)
    questions = {}
    for row in rows:
        hit = rules_of(row, drafts, reference, boundary)
        if hit:
            questions[row] = hit
    rule1 = "the author answered open or assumed" if len(drafts) == 1 else "one author answered open, or two or more assumed"
    print("kv spec questions: %d draft(s) (%s), mode %s; rule 1: %s; rule 2 %s; rule 4 %s" % (
        len(drafts), ", ".join(d.name for d in drafts), mode, rule1,
        "on" if len(drafts) > 1 else "off (one draft)", "on (proxy: --reference)" if reference is not None else "off (no --reference)"))
    for row, hit in questions.items():
        f = drafts[0].answers[row]["formal_cell"]
        print("  %s%s rules %s | answers %s | drafts %s%s" % (
            row, "" if f == row else " (cell %s)" % f, ",".join(map(str, hit)),
            " ".join("%s=%s/%s" % (d.name, d.answers[row].get("outcome") or "none", d.answers[row]["status"]) for d in drafts),
            " ".join("%s=%s" % (d.name, short(d.cells[f])) for d in drafts),
            "" if reference is None else " | reference %s" % short(reference.get(f))))
    print("question cells: %d %s" % (len(questions), " ".join(questions)))
    result = {"mode": mode, "drafts": [d.name for d in drafts], "rule1": rule1,
              "questions": [{"cell": r, "formal_cell": drafts[0].answers[r]["formal_cell"], "rules": h} for r, h in questions.items()]}
    code = 0
    if args.groups:
        groups = load(args.groups)
        errors = check_groups(groups, questions, rows)
        result["group_errors"] = errors
        if errors:
            code = 1
            print("group check: FAILED")
            for e in errors:
                print("  " + e)
        else:
            print("group check: OK, %d group(s)" % len(groups.get("groups") or []))
            if args.bundle:
                with open(args.bundle, "w") as f:
                    f.write(bundle(groups, questions, drafts, reference, cell_list))
                print("review bundle: %s" % args.bundle)
    elif args.bundle:
        raise InputError("--bundle needs --groups")
    if args.json:
        with open(args.json, "w") as f:
            json.dump(result, f, indent=1)
            f.write("\n")
    return code


def main(argv):
    p = argparse.ArgumentParser(prog="spec.py")
    sub = p.add_subparsers(dest="command", required=True)
    q = sub.add_parser("questions", help="the question rules and the group check (DESIGN-C.md 3.3)")
    q.add_argument("--check", action="append", help="NAME=CHECK.json of kv spec check --cells")
    q.add_argument("--answers", action="append", help="NAME=ANSWERS.json (kv-answers/1)")
    q.add_argument("--reference", help="CHECK.json of a sidecar whose clauses hold on the reference implementation (rule 4 proxy)")
    q.add_argument("--mode", choices=("a", "b"))
    q.add_argument("--groups", help="GROUPS.json (kv-groups/1)")
    q.add_argument("--bundle", help="write the review bundle (Markdown) here")
    q.add_argument("--json", help="write the result as JSON here")
    args = p.parse_args(argv)
    try:
        return questions_cmd(args)
    except InputError as e:
        print("spec.py: %s" % e, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
