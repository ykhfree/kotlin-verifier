"""Seeds aicode/round2 for the round-2 tool run (AI-CODE.md 11): the 12 template implementations unchanged, the
round-1 binding adaptations, `use kotlinx.Semaphore` for the four kotlinx implementations, and results.jsonl lines
with the round-1 fields that do not come from the tool (no tool, hole or final verdict)."""
import json, os, shutil, sys
repo = sys.argv[1]
src, dst = os.path.join(repo, "aicode"), os.path.join(repo, "aicode", "round2")
KOTLINX = ("T-sonnet-2", "T-opus-1", "T-opus-3", "T-opus-4")
KEEP = ("id", "model", "kind", "compiled", "compile_calls", "keylock_changed", "sandbox_extra_files", "similarity", "similarity_flag", "tests", "judge")
if os.path.exists(dst):
    sys.exit("%s exists" % dst)
recs = [json.loads(l) for l in open(os.path.join(src, "results.jsonl")) if l.strip()]
template = [r for r in recs if r.get("kind") == "template"]
assert len(template) == 12, len(template)
os.makedirs(dst)
with open(os.path.join(dst, "results.jsonl"), "w") as f:
    for r in template:
        f.write(json.dumps({k: r[k] for k in KEEP if k in r}, ensure_ascii=False, sort_keys=True) + "\n")
for r in template:
    id = r["id"]
    impl = os.path.join(dst, "impl", id)
    os.makedirs(impl)
    shutil.copy(os.path.join(src, "impl", id, "KeyLocalLock.kt"), impl)
    lines = []
    old = os.path.join(src, "impl", id, "bindings.txt")
    if os.path.exists(old):
        lines.append(open(old).read().rstrip("\n"))
    if id in KOTLINX:
        assert "import kotlinx.coroutines.sync.Semaphore" in open(os.path.join(impl, "KeyLocalLock.kt")).read(), id
        lines.append("# AI-CODE.md 11: LockInfo.semaphore is a kotlinx.coroutines.sync.Semaphore, whose model the sidecar must use\nuse kotlinx.Semaphore")
    if lines:
        open(os.path.join(impl, "bindings.txt"), "w").write("\n".join(lines) + "\n")
print("seeded", len(template))
