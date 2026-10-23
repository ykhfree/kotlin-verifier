package kv.kvir

import kv.kvir.KType.Abs
import kv.kvir.KType.BoolT
import kv.kvir.KType.RefT

/**
 * The KVIR of core-spring `ReqShieldAspect.getOrCreateReqShield`, built by hand: an instance module whose operation
 * section runs a computeIfAbsent action and returns its value; the action's function calls the trusted fresh,
 * throwing `createReqShield` (DESIGN-B.md 5.2, spike/aspect/target.vpr). Positions are those of req-shield
 * `core-spring/.../aspect/ReqShieldAspect.kt` at 2e792d2. The ledger is a small stand-in.
 */
object AspectKvirFixture {
    fun p(
        line: Int,
        col: Int,
    ) = Pos("ReqShieldAspect.kt", line, col)

    val methodKey = Abs("MethodKey", false)
    val shield = RefT("Shield", false)
    val shieldOrNull = RefT("Shield", true)
    val shieldClass = ObjClass("Shield", "ReqShield", emptyList(), emptyMap(), p(50, 1))
    val m = MapVar("m", "reqShieldMap", p(84, 5), methodKey, "Shield", setOnce = "P_stable")

    val key = FreeValue(Local("k", methodKey, p(112, 38)), ActionKey(p(112, 38)), null, "getTargetMethod(joinPoint)")
    val thrown = FreeValue(Local("thrown", BoolT, p(113, 13)), NondetCall("ReqShieldAspect.createReqShield", p(113, 13)), "thrown", "ReqShieldAspect.createReqShield(...)")
    val cur = Local("cur_112_9", shieldOrNull, p(112, 9))
    val out = Local("out_112_9", shieldOrNull, p(112, 9))
    val r = Local("r_113_13", shield, p(113, 13))

    val body: List<Stmt> =
        listOf(
            Nondet(listOf(thrown.local), "ReqShieldAspect.createReqShield", p(113, 13)),
            If(
                Var(thrown.local, p(113, 13)),
                listOf(Raise(p(113, 13))),
                listOf(Fresh(r, shieldClass, "createReqShield", p(113, 13)), Exit(ExitResult.Put(Var(r, p(113, 13))), p(113, 13))),
                p(113, 13),
            ),
        )

    val action =
        Action(
            id = "getOrCreate#112",
            pos = p(112, 9),
            map = m,
            key = key,
            entry = cur,
            free = listOf(thrown),
            body = body,
            commit = emptyList(),
            status = Status.Supported,
            translated = setOf(1, 2),
            kind = ActionKind.COMPUTE_IF_ABSENT,
            out = out,
        )

    val res = Local("res", shieldOrNull, p(111, 5))
    val section: List<Stmt> = listOf(RunAction(p(112, 9)), Return(Var(out, p(112, 9)), p(112, 9)))

    val operation = Operation("getOrCreate", "fun ReqShieldAspect.getOrCreateReqShield", p(111, 5), action, emptyList(), res, section)

    val module =
        Module(
            id = "com.linecorp.cse.reqshield.spring.aspect.ReqShieldAspect",
            types = listOf(AbsType("MethodKey", null, "java/lang/reflect/Method")),
            state = listOf(m),
            classes = listOf(shieldClass),
            ghost = null,
            operations = listOf(operation),
            strayActions = emptyList(),
            firNodeCount = 3,
            ledger = listOf(Disposition(0, DispositionKind.STRUCTURE, "module object ReqShieldAspect", p(50, 1))),
            diagnostics = emptyList(),
            kind = ModuleKind.INSTANCE,
            freshFunctions = mapOf("createReqShield" to "Shield"),
            file = "ReqShieldAspect.kt",
        )
}
