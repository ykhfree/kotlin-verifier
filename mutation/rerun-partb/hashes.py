"""AI-CODE.md 15.1 prediction 3 (Part B, DESIGN-D (a)): the normalized model hashes of the mechanical mutants, without
Silicon. For each of the six modules it lists the mutation sites with `KV mutation-sites`, applies each site to a
copy, runs `KV emit` and hashes the program as scripts/mechanical.py does (steps 1-4 of its docstring, with its own
functions). It then compares each hash and each site with a results.jsonl of an earlier mechanical run.

Usage: python3 -I hashes.py --kv KV --req-shield DIR --classpath CP --aspect-classpath CP --kotlinc ARGS --specs DIR
                            --normalizer VPR_NORMALIZE.py --work DIR --scripts DIR --against RESULTS.jsonl --out FILE.jsonl
Writes one line per mutant (id, module, compiled, model_hash, edits) to --out and prints the comparison."""
import argparse, concurrent.futures, importlib.util, json, os, shutil, sys

sys.dont_write_bytecode = True
ap = argparse.ArgumentParser()
for name in ("--kv", "--req-shield", "--classpath", "--aspect-classpath", "--kotlinc", "--specs", "--normalizer", "--work", "--scripts", "--against", "--out"):
    ap.add_argument(name, required=True)
ap.add_argument("--jobs", type=int, default=3)
ap.add_argument("--timeout", type=int, default=900)
args = ap.parse_args()


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


mech = load("mechanical", os.path.join(args.scripts, "mechanical.py"))
norm = load("vpr_normalize", args.normalizer)
modules = [mech.Module(n, s, u, args) for n, s, u in mech.MODULES]
for m in modules:
    shutil.rmtree(m.work, ignore_errors=True)
with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
    list(pool.map(lambda m: mech.list_sites(args, m), modules))
    everyone = [m.original for m in modules] + [mu for m in modules for mu in m.sites]
    list(pool.map(lambda mu: mech.emit(args, norm, mu), everyone))

recs = [{"id": mu.id, "module": m.name, "compiled": mu.compiled, "model_hash": mu.hash, "edits": mu.site["edits"] if mu.site else None}
        for m in modules for mu in [m.original] + m.sites]
with open(args.out, "w") as f:
    for r in recs:
        f.write(json.dumps(r, sort_keys=True) + "\n")

before = {}
for line in open(args.against):
    if line.strip():
        r = json.loads(line)
        before[r["id"]] = r
mine = {r["id"]: r for r in recs if not r["id"].endswith("/original")}
same_ids = sorted(set(mine) & set(before))
print("mutants: %d now, %d before, %d in both" % (len(mine), len(before), len(same_ids)))
print("only now: %s" % sorted(set(mine) - set(before)))
print("only before: %s" % sorted(set(before) - set(mine)))
edits = [i for i in same_ids if mine[i]["edits"] != before[i]["edits"]]
hashes = [i for i in same_ids if mine[i]["model_hash"] != before[i]["model_hash"]]
compiled = [i for i in same_ids if mine[i]["compiled"] != before[i]["compiled"]]
print("different edits: %d %s" % (len(edits), edits[:10]))
print("different compiled: %d %s" % (len(compiled), compiled[:10]))
print("different model hashes: %d %s" % (len(hashes), hashes[:10]))
print("same model hashes: %d (with a model: %d)" % (len(same_ids) - len(hashes), sum(mine[i]["model_hash"] is not None for i in same_ids)))
for m in modules:
    print("original %s: %s" % (m.name, m.original.hash))
