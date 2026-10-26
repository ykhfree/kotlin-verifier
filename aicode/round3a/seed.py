"""Seeds a results directory for round 3a (AI-CODE.md 14): the round-2 config unchanged (the 12 template
implementations and their bindings.txt, copied from aicode/round2/impl), and results.jsonl lines with the round-2
fields that do not come from the tool (no tool, hole or final verdict). The same steps as aicode/round2b/seed.py.

Usage: python3 -I aicode/round3a/seed.py REPO [DST]
DST is relative to REPO; default aicode/round3a. The baseline run of the pre-change tool uses aicode/round3a/base."""
import json, os, shutil, sys

repo = sys.argv[1]
src = os.path.join(repo, "aicode", "round2")
dst = os.path.join(repo, sys.argv[2] if len(sys.argv) > 2 else os.path.join("aicode", "round3a"))
KEEP = ("id", "model", "kind", "compiled", "compile_calls", "keylock_changed", "sandbox_extra_files", "similarity", "similarity_flag", "tests", "judge")
if os.path.exists(os.path.join(dst, "results.jsonl")) or os.path.exists(os.path.join(dst, "impl")):
    sys.exit("%s is seeded already" % dst)
recs = [json.loads(l) for l in open(os.path.join(src, "results.jsonl")) if l.strip()]
assert len(recs) == 12 and all(r.get("kind") == "template" for r in recs), [r.get("id") for r in recs]
os.makedirs(dst, exist_ok=True)
with open(os.path.join(dst, "results.jsonl"), "w") as f:
    for r in recs:
        f.write(json.dumps({k: r[k] for k in KEEP if k in r}, ensure_ascii=False, sort_keys=True) + "\n")
for r in recs:
    id = r["id"]
    shutil.copytree(os.path.join(src, "impl", id), os.path.join(dst, "impl", id))
print("seeded", len(recs))
