package kv.kvir

import kv.kvir.KType.Abs
import kv.kvir.KType.BoolT
import kv.kvir.KType.IntT
import kv.kvir.KType.RefT

/**
 * The KVIR of the coroutine `KeyLocalLock.tryLock` action, built by hand from DESIGN-v0.md 1.3.
 * Positions are those of req-shield `core-kotlin-coroutine/.../KeyLocalLock.kt` (branch feature/NOISSUE/upgrade-kotlin2-and-deps).
 * The ledger is a small stand-in: the real one comes from the module audit (M4).
 */
object TryLockFixture {
    fun p(
        line: Int,
        col: Int,
    ) = Pos("KeyLocalLock.kt", line, col)

    val key = Abs("Key", false)
    val token = Abs("Token", false)
    val tokenOrNull = Abs("Token", true)
    val lockInfoOrNull = RefT("LockInfo", true)

    val permits = ObjField("permits", "semaphore", IntT, "jdk.Semaphore", false, p(45, 9))
    val expiresAt = ObjField("expiresAt", "expiresAt", IntT, null, true, p(50, 9))
    val isHeld = ObjField("isHeld", "isHeld", BoolT, "jdk.AtomicBoolean", false, p(57, 9))
    val tokenField = ObjField("token", "token", tokenOrNull, null, true, p(65, 9))
    val lockInfo =
        ObjClass(
            "LockInfo",
            "KeyLocalLock.LockInfo",
            listOf(permits, expiresAt, isHeld, tokenField),
            mapOf("isHeld" to BoolLit(false, p(57, 37)), "token" to NullLit(tokenOrNull, p(65, 40))),
            p(44, 5),
        )

    val m = MapVar("m", "lockMap", p(69, 9), key, "LockInfo")
    val seq = CounterVar("seq", "tokenSequence", p(75, 9), 0)

    // Header of the action.
    val k = Local("k", key, p(149, 9))
    val existing = Local("existing__155", lockInfoOrNull, p(155, 43))
    val now = Local("now", IntT, p(150, 9))
    val timeout = Local("timeout", IntT, p(172, 48))
    val rej = Local("rej", BoolT, p(181, 21))
    val limit = Local("limit", IntT, p(181, 21))
    val keyValue = FreeValue(k, ActionKey(p(155, 25)), null, "completeKey")
    val freeValues =
        listOf(
            FreeValue(now, Captured(p(150, 9)), "now", "now"),
            FreeValue(timeout, InstanceVal("lockTimeoutMillis", p(172, 48)), "timeout", "this.lockTimeoutMillis"),
            FreeValue(rej, NondetCall("LocalLockLimit.rejectsNewEntry", p(181, 21)), "rej", "LocalLockLimit.rejectsNewEntry(...)"),
            FreeValue(limit, NondetCall("LocalLockLimit.rejectsNewEntry", p(181, 21)), "limit", "LocalLockLimit.rejectsNewEntry(...)"),
        )

    // Locals of the body.
    val acquiredToken = Local("acquiredToken__151", tokenOrNull, p(151, 9))
    val cas = Local("cond_161_21", BoolT, p(161, 21))
    val acquired = Local("cond_168_21", BoolT, p(168, 21))
    val n1 = Local("n_80_52", IntT, p(80, 52))
    val token169 = Local("token__169", token, p(169, 21))
    val n2 = Local("n_80_52b", IntT, p(80, 52))
    val n3 = Local("n_80_52c", IntT, p(80, 52))
    val token187 = Local("token__187", token, p(187, 17))
    val newLock = Local("newLock__188", RefT("LockInfo", false), p(188, 17))
    val newAcquired = Local("cond_189_17", BoolT, p(189, 17))
    val res = Local("res", tokenOrNull, p(195, 16))

    fun v(
        l: Local,
        pos: Pos,
    ) = Var(l, pos)

    /** `existing.f` read at [pos]. */
    fun ex(pos: Pos) = Var(existing, pos)

    val existingBranch: List<Stmt> =
        listOf(
            Let(cas, BoolLit(false, p(161, 21)), true, p(161, 21)),
            If(
                Cmp(CmpOp.GT, v(now, p(161, 21)), FieldRead(ex(p(161, 27)), expiresAt, p(161, 27)), p(161, 21)),
                listOf(ModelOp(cas, "jdk.AtomicBoolean.compareAndSet", FieldTarget(ex(p(161, 49)), isHeld), listOf(BoolLit(true, p(161, 49)), BoolLit(false, p(161, 49))), null, p(161, 49))),
                emptyList(),
                p(161, 21),
            ),
            If(
                v(cas, p(161, 17)),
                listOf(
                    FieldWrite(ex(p(163, 21)), tokenField, NullLit(tokenOrNull, p(163, 38)), p(163, 21)),
                    ModelOp(null, "jdk.Semaphore.release", FieldTarget(ex(p(164, 21)), permits), emptyList(), null, p(164, 21)),
                ),
                emptyList(),
                p(161, 17),
            ),
            ModelOp(acquired, "jdk.Semaphore.tryAcquire", FieldTarget(ex(p(168, 21)), permits), emptyList(), null, p(168, 21)),
            If(
                v(acquired, p(168, 17)),
                listOf(
                    ModelOp(n1, "jdk.AtomicLong.incrementAndGet", StateTarget(seq), emptyList(), "A80", p(80, 52)),
                    Let(token169, TokenOf(v(n1, p(80, 52)), token, p(80, 43)), false, p(169, 21)),
                    ModelOp(null, "jdk.AtomicBoolean.set", FieldTarget(ex(p(170, 21)), isHeld), listOf(BoolLit(true, p(170, 41))), null, p(170, 21)),
                    FieldWrite(ex(p(171, 21)), tokenField, v(token169, p(171, 38)), p(171, 21)),
                    FieldWrite(ex(p(172, 21)), expiresAt, Arith(ArithOp.PLUS, v(now, p(172, 42)), v(timeout, p(172, 48)), "A172", p(172, 42)), p(172, 21)),
                    Assign(acquiredToken, v(token169, p(173, 39)), p(173, 21)),
                ),
                emptyList(),
                p(168, 17),
            ),
            Exit(ExitResult.Keep, p(175, 17)),
        )

    val newEntryBranch: List<Stmt> =
        listOf(
            Nondet(listOf(rej, limit), "LocalLockLimit.rejectsNewEntry", p(181, 21)),
            If(
                v(rej, p(181, 21)),
                listOf(
                    ModelOp(n2, "jdk.AtomicLong.incrementAndGet", StateTarget(seq), emptyList(), "A80", p(80, 52)),
                    Assign(acquiredToken, TokenOf(v(n2, p(80, 52)), token, p(80, 43)), p(182, 21)),
                    Exit(ExitResult.Remove, p(183, 21)),
                ),
                listOf(
                    ModelOp(n3, "jdk.AtomicLong.incrementAndGet", StateTarget(seq), emptyList(), "A80", p(80, 52)),
                    Let(token187, TokenOf(v(n3, p(80, 52)), token, p(80, 43)), false, p(187, 17)),
                    New(
                        newLock,
                        lockInfo,
                        mapOf(
                            "permits" to IntLit(1, p(188, 50)),
                            "expiresAt" to Arith(ArithOp.PLUS, v(now, p(188, 54)), v(timeout, p(188, 60)), "A188", p(188, 54)),
                            "isHeld" to BoolLit(false, p(57, 37)),
                            "token" to v(token187, p(188, 79)),
                        ),
                        p(188, 31),
                    ),
                    ModelOp(newAcquired, "jdk.Semaphore.tryAcquire", FieldTarget(v(newLock, p(189, 17)), permits), emptyList(), null, p(189, 17)),
                    ModelOp(null, "jdk.AtomicBoolean.set", FieldTarget(v(newLock, p(190, 17)), isHeld), listOf(BoolLit(true, p(190, 36))), null, p(190, 17)),
                    Assign(acquiredToken, v(token187, p(191, 35)), p(191, 17)),
                    Exit(ExitResult.Put(v(newLock, p(192, 17))), p(192, 17)),
                ),
                p(181, 17),
            ),
        )

    val body: List<Stmt> =
        listOf(
            If(
                Cmp(CmpOp.NE, ex(p(156, 17)), NullLit(lockInfoOrNull, p(156, 29)), p(156, 17)),
                existingBranch,
                newEntryBranch,
                p(156, 13),
            ),
        )

    val commit: List<GhostOp> =
        listOf(
            EndIfNotCarried(tokenField, p(155, 9)),
            AssertFresh(v(res, p(155, 9)), p(155, 9)),
            Issue(
                v(res, p(155, 9)),
                linkedMapOf(
                    "gKey" to v(k, p(155, 9)),
                    "gBacked" to Carried(v(k, p(155, 9)), v(res, p(155, 9)), p(155, 9)),
                    "gLease" to Arith(ArithOp.PLUS, v(now, p(155, 9)), v(timeout, p(155, 9)), null, p(155, 9)),
                ),
                p(155, 9),
            ),
        )

    val action =
        Action(
            id = "tryLock#155",
            pos = p(155, 9),
            map = m,
            key = keyValue,
            entry = existing,
            free = freeValues,
            body = body,
            commit = commit,
            status = Status.Supported,
            translated = setOf(4, 5, 6, 7),
        )

    val operation =
        Operation(
            name = "tryLock",
            kotlin = "fun KeyLocalLock.tryLock",
            pos = p(145, 5),
            action = action,
            cells = listOf(Cell(acquiredToken, NullLit(tokenOrNull, p(151, 54)), p(151, 9))),
            result = OpResult(res, v(acquiredToken, p(195, 16)), p(195, 16)),
        )

    val module =
        Module(
            id = "com.linecorp.cse.reqshield.kotlin.coroutine.KeyLocalLock.Companion",
            types = listOf(AbsType("Key", null), AbsType("Token", "local-\${Long}")),
            state = listOf(m, seq),
            classes = listOf(lockInfo),
            ghost =
                GhostSchema(
                    "tok",
                    linkedMapOf(
                        "gIssued" to GhostKind.SET_ONCE,
                        "gKey" to GhostKind.FROZEN,
                        "gBacked" to GhostKind.FROZEN,
                        "gHeld" to GhostKind.ENDS,
                        "gLease" to GhostKind.FROZEN,
                    ),
                ),
            operations = listOf(operation),
            strayActions = emptyList(),
            firNodeCount = 8,
            ledger =
                listOf(
                    Disposition(0, DispositionKind.FREE_SOURCE, "lockKeyOf(key, lockType)", p(149, 27)),
                    Disposition(1, DispositionKind.FREE_SOURCE, "nowToEpochTime()", p(150, 19)),
                    Disposition(2, DispositionKind.CELL, "AtomicReference(null)", p(151, 29)),
                    Disposition(3, DispositionKind.CELL, "acquiredToken.get()", p(195, 16)),
                ),
            diagnostics =
                listOf(
                    Diagnostic("A80", DiagnosticKind.ASSUMPTION, "Long unbounded: tokenSequence.incrementAndGet()", p(80, 52)),
                    Diagnostic("A172", DiagnosticKind.ASSUMPTION, "Long unbounded: now + lockTimeoutMillis", p(172, 42)),
                    Diagnostic("A188", DiagnosticKind.ASSUMPTION, "Long unbounded: now + lockTimeoutMillis", p(188, 54)),
                ),
        )
}
