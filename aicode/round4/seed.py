"""Seeds the two results directories of round 4 (AI-CODE.md 15, Part B): the tool after DESIGN-D (a) on the 24 AI
implementations, in fresh directories. Each gets the implementations and their bindings.txt unchanged, and
results.jsonl lines with the fields that do not come from the tool (no tool, hole or final verdict).

  aicode/round4/r2  from aicode/round2 (the 12 second-round template implementations; as aicode/round3a/seed.py)
  aicode/round4/g   from aicode/round3g (the 12 fresh implementations of the generalization test)

Nothing under aicode/round2 or aicode/round3g is written.
Usage: python3 -I aicode/round4/seed.py REPO"""
import json, os, shutil, sys

repo = sys.argv[1]
KEEP = ("id", "model", "kind", "compiled", "compile_calls", "keylock_changed", "sandbox_extra_files", "similarity", "similarity_flag", "tests", "judge")
for src_rel, dst_rel in (("round2", "r2"), ("round3g", "g")):
    src = os.path.join(repo, "aicode", src_rel)
    dst = os.path.join(repo, "aicode", "round4", dst_rel)
    if os.path.exists(os.path.join(dst, "results.jsonl")) or os.path.exists(os.path.join(dst, "impl")):
        sys.exit("%s is seeded already" % dst)
    recs = [json.loads(l) for l in open(os.path.join(src, "results.jsonl")) if l.strip()]
    assert len(recs) == 12 and all(r.get("kind") == "template" for r in recs), [r.get("id") for r in recs]
    os.makedirs(dst, exist_ok=True)
    with open(os.path.join(dst, "results.jsonl"), "w") as f:
        for r in recs:
            f.write(json.dumps({k: r[k] for k in KEEP if k in r}, ensure_ascii=False, sort_keys=True) + "\n")
    for r in recs:
        shutil.copytree(os.path.join(src, "impl", r["id"]), os.path.join(dst, "impl", r["id"]))
    print("seeded %s: %d" % (dst, len(recs)))
