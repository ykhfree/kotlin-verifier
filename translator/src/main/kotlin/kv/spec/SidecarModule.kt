package kv.spec

import kv.kvir.AbsType
import kv.kvir.Action
import kv.kvir.ActionKey
import kv.kvir.Captured
import kv.kvir.CounterVar
import kv.kvir.FreeValue
import kv.kvir.GhostSchema
import kv.kvir.InstanceVal
import kv.kvir.KType
import kv.kvir.Local
import kv.kvir.MapVar
import kv.kvir.Module
import kv.kvir.Nondet
import kv.kvir.NondetCall
import kv.kvir.ObjClass
import kv.kvir.ObjField
import kv.kvir.Operation
import kv.kvir.Origin
import kv.kvir.Pos
import kv.kvir.Return
import kv.kvir.RunAction
import kv.kvir.Status
import kv.kvir.Var
import kv.lower.bindGhosts
import kv.model.TrustedModels
import kv.sidecar.FreeSource
import kv.sidecar.IssueItem
import kv.sidecar.SPos
import kv.sidecar.Selector
import kv.sidecar.Sidecar
import kv.sidecar.SidecarError
import kv.sidecar.SidecarException
import kv.sidecar.StateKind

/**
 * A module built from the sidecar alone, for the contract-only model of `kv spec check` without code (DESIGN-C.md
 * 3.2: only mode b). [assumptions] say which types the sidecar did not give.
 */
data class SidecarModule(
    val module: Module,
    val assumptions: List<String>,
)

/**
 * The module of [sidecar] without Kotlin code, with the ghost rules bound: the types, the state, the classes, and per
 * operation an action whose key is `k` (the map key type), whose free values are the sidecar's, and whose body holds
 * only the trusted calls of `free ... = call` items, so that their contracts become preconditions. No body is emitted
 * from it ([kv.emit.ContractOnly]). Types the sidecar does not write are taken as follows, and listed in
 * [SidecarModule.assumptions]; a wrong one makes Silicon report a type error, never a wrong cell:
 * - a free value from a local, a parameter or `this` without `: TYPE` is an Int (a Kotlin Long or Int);
 * - an annotated free value of a template type may be NULL (the sidecar does not say that it is non-null);
 * - the result an issue rule names has the ghost record's index type, and may be NULL; any other result is a Bool.
 * v0 object modules only: a sidecar without a ghost record (an instance module, DESIGN-B.md) is rejected.
 */
fun sidecarModule(sidecar: Sidecar): SidecarModule {
    val module = sidecar.modules.single()
    val ghost = sidecar.ghosts.singleOrNull() ?: throw SidecarException(listOf(SidecarError(module.pos, "contract-only", "kv spec check without --root reads object modules with a ghost record only (DESIGN-v0.md 3.5); give --root for this sidecar")))
    val assumptions = mutableListOf<String>()
    fun pos(p: SPos) = Pos(p.file, p.line, p.col)
    val templates = sidecar.types.filter { it.template != null }.map { it.name.text }.toSet()

    fun abs(name: String) = KType.Abs(name, nullable = name in templates)
    fun viper(name: String): KType =
        when (name) {
            "Int" -> KType.IntT
            "Bool" -> KType.BoolT
            else -> abs(name)
        }
    val types = sidecar.types.map { AbsType(it.name.text, it.template, it.kclass?.text?.replace('.', '/')) }
    val classes =
        sidecar.classes.map { c ->
            val fields =
                c.fields.map { f ->
                    // As the lowering types a sidecar field (ModuleContext): Semaphore and Long are Int, AtomicBoolean is Bool.
                    val type =
                        when (f.type.text) {
                            "Semaphore", "Long" -> KType.IntT
                            "AtomicBoolean" -> KType.BoolT
                            else -> KType.Abs(f.type.text, f.nullable)
                        }
                    val model = TrustedModels.fieldModels[f.type.text]?.values?.firstOrNull { m -> sidecar.uses.any { it.model.text == m } }
                    ObjField(f.name.text, f.kname.text, type, model, false, pos(c.pos))
                }
            ObjClass(c.name.text, c.kname.text, fields, emptyMap(), pos(c.pos))
        }
    // The code orders the state by Kotlin declaration, which the sidecar does not give: the maps come first here.
    val state =
        sidecar.states.sortedBy { it.kind !is StateKind.MapOf }.map { st ->
            when (val k = st.kind) {
                is StateKind.MapOf -> MapVar(st.name.text, st.kname.text, pos(st.pos), KType.Abs(k.key.text, false), k.value.text)
                // The contract-only init states no counter value (ContractOnly), so this initial value is never read.
                StateKind.Counter -> CounterVar(st.name.text, st.kname.text, pos(st.pos), 0)
            }
        }
    val map = state.filterIsInstance<MapVar>().singleOrNull() ?: throw SidecarException(listOf(SidecarError(module.pos, "contract-only", "the contract-only model needs exactly one state map")))
    val operations =
        sidecar.operations.map { op ->
            val at = pos(op.pos)
            val issue = op.items.filterIsInstance<IssueItem>().singleOrNull()
            val key = FreeValue(Local("k", map.key, at), ActionKey(at), null, "k")
            val free = mutableListOf<FreeValue>()
            val body = mutableListOf<kv.kvir.Stmt>()
            for (item in op.free) {
                val src = item.source
                val origin: Origin =
                    when (src) {
                        is FreeSource.Call -> NondetCall(src.name.text, at)
                        is FreeSource.This -> InstanceVal(src.name.text, at)
                        is FreeSource.Local, is FreeSource.Param -> Captured(at)
                    }
                val locals =
                    if (src is FreeSource.Call) {
                        val t = sidecar.trusted.single { it.shortName == src.name.text }
                        val values = t.returns + t.ghosts
                        item.names.mapIndexed { i, n -> Local(n.text, viper(values[i].type.text), pos(n.pos)) }
                    } else {
                        item.names.map { n ->
                            val type = item.type?.let { abs(it.text) } ?: KType.IntT.also { assumptions += "free value ${n.text} of ${op.name.text} has no type: an Int" }
                            Local(n.text, type, pos(n.pos))
                        }
                    }
                locals.forEach { free += FreeValue(it, origin, it.name, it.name) }
                // The trusted call binds its values here, so the emitter gives the action its contract as preconditions.
                if (src is FreeSource.Call) body += Nondet(locals, sidecar.trusted.single { it.shortName == src.name.text }.fn.text, at)
            }
            val result =
                op.result?.let { r ->
                    val type = if (issue != null) abs(ghost.index.text) else KType.BoolT.also { assumptions += "result ${r.name.text} of ${op.name.text} has no issue rule: a Bool" }
                    Local(r.name.text, type, pos(r.pos))
                }
            val selector =
                when (val sel = op.selector) {
                    is Selector.Fun -> "fun ${sel.target.text}"
                    is Selector.ComputeIn -> "compute in ${sel.target.text}"
                }
            val action = Action(op.name.text, at, map, key, Local("entry", KType.RefT(map.valueClass, true), at), free, body, emptyList(), Status.Supported, emptySet())
            Operation(op.name.text, selector, at, action, emptyList(), result, listOf(RunAction(at), Return(result?.let { Var(it, at) }, at)))
        }
    val schema = GhostSchema(ghost.name.text, ghost.fields.associate { it.name.text to it.kind })
    val m = Module(module.fqn.text, types, state, classes, schema, operations, emptyList(), 0, emptyList(), emptyList(), file = sidecar.file)
    return SidecarModule(bindGhosts(m, sidecar), assumptions)
}
