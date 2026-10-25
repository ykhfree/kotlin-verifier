"""Pilot harness: turns a drafter's DRAFT.kv into a checkable spec directory, and runs `kv verify`.

Usage:
  python3 -I assemble.py build DRAFT.kv OUT_DIR [--hole signed|true]
      Writes OUT_DIR/keylocallock.common.kv (the draft, the hole replaced, pilot reviewed notes added) and
      OUT_DIR/keylocallock.coroutine.kv (the signed variant file). Prints the kv exit code of a check run.
  python3 -I assemble.py verify SPEC_DIR ROOT OUT.vpr [--variant coroutine]
      Runs kv verify; prints the report; exit code = kv's.

Pilot reviewed notes: the tool rejects an issue rule or an allowance clause without `reviewed` (U7). The harness adds
`reviewed "PILOT, not signed by the user" sha256 "<fp>"` after each one. The fingerprint comes from the tool's own
missing-fingerprint message, so the harness never re-implements the clause-text rule.
"""
import os
import re
import subprocess
import sys

SD = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KV = os.path.join(SD, "kv-frozen", "bin", "kv")
CP = open(os.path.join(SD, "cp.txt")).read().strip()
KOTLINC = open(os.path.join(SD, "kotlinc.txt")).read().strip()
SIGNED = os.path.join(SD, "specs-signed")
REQ_SHIELD = "/Users/ykhfree/IdeaProjects/req-shield"
NOTE = 'reviewed "PILOT, not signed by the user"'
CLAUSE = re.compile(r"^(\s*)(issue|ends_only_if|unbacked_only_if)\b")


def signed_hole():
    text = open(os.path.join(SIGNED, "keylocallock.common.kv")).read()
    return re.search(r'hole MI_INV """.*?"""', text, re.S).group(0)


def add_notes(text):
    """Adds the pilot note (without fingerprint) after every issue rule and allowance clause that has no note."""
    lines = text.split("\n")
    out = []
    i = 0
    while i < len(lines):
        m = CLAUSE.match(lines[i])
        if not m:
            out.append(lines[i])
            i += 1
            continue
        indent = len(m.group(1))
        j = i + 1
        # Continuation lines are indented deeper than the clause's first line (DESIGN-v0.md 4.2).
        while j < len(lines) and lines[j].strip() and not lines[j].strip().startswith("//") and \
                len(lines[j]) - len(lines[j].lstrip()) > indent:
            j += 1
        out.extend(lines[i:j])
        nxt = lines[j].strip() if j < len(lines) else ""
        if not nxt.startswith("reviewed"):
            out.append(" " * indent + NOTE)
        i = j
    return "\n".join(out)


def run_kv(spec_dir, root, out_vpr, variant="coroutine"):
    sidecar = os.path.join(spec_dir, "keylocallock.%s.kv" % variant)
    cmd = [KV, "verify", "--root", root, "--classpath", CP, "--kotlinc", KOTLINC, "--out", out_vpr, sidecar]
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=900)
    return p.returncode, p.stdout, p.stderr


def build(draft, out_dir, hole="signed"):
    os.makedirs(out_dir, exist_ok=True)
    text = open(draft).read()
    replacement = signed_hole() if hole == "signed" else 'hole MI_INV """\n  true\n"""'
    text, n = re.subn(r'hole MI_INV """.*?"""', lambda _: replacement, text, flags=re.S)
    if n != 1:
        raise SystemExit("the draft must hold exactly one hole, found %d" % n)
    text = add_notes(text)
    common = os.path.join(out_dir, "keylocallock.common.kv")
    for v in ("coroutine", "core", "reactor"):
        with open(os.path.join(SIGNED, "keylocallock.%s.kv" % v)) as f:
            vt = f.read()
        with open(os.path.join(out_dir, "keylocallock.%s.kv" % v), "w") as f:
            f.write(vt)
    # Two rounds: the first run names the fingerprint of every note that has none.
    for _ in range(3):
        with open(common, "w") as f:
            f.write(text)
        code, out, err = run_kv(out_dir, REQ_SHIELD, os.path.join(out_dir, "check.vpr"))
        msgs = re.findall(r"keylocallock\.common\.kv:(\d+):(\d+).*?has no fingerprint: write sha256 \"([0-9a-f]+)\"", out + err)
        if not msgs:
            break
        lines = text.split("\n")
        for line, _col, fp in msgs:
            idx = int(line) - 1
            if lines[idx].strip() == NOTE:
                lines[idx] = lines[idx] + ' sha256 "%s"' % fp
        text = "\n".join(lines)
    with open(os.path.join(out_dir, "check.report"), "w") as f:
        f.write(out + ("\n--- stderr ---\n" + err if err.strip() else ""))
    print("kv exit %d (%s)" % (code, {0: "PROVEN", 1: "FAILED", 3: "INCOMPLETE", 4: "ERROR"}.get(code, "?")))
    return code


def main():
    if sys.argv[1] == "build":
        hole = "signed"
        if "--hole" in sys.argv:
            hole = sys.argv[sys.argv.index("--hole") + 1]
        sys.exit(0 if build(sys.argv[2], sys.argv[3], hole) in (0, 1, 3) else 4)
    if sys.argv[1] == "verify":
        variant = "coroutine"
        if "--variant" in sys.argv:
            variant = sys.argv[sys.argv.index("--variant") + 1]
        code, out, err = run_kv(sys.argv[2], sys.argv[3], sys.argv[4], variant)
        sys.stdout.write(out)
        sys.stderr.write(err)
        sys.exit(code)


if __name__ == "__main__":
    main()
