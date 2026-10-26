package kv.client

/** The entry body and helpers of one fixture (see [ClientFixture.client]). */
data class Snippet(
    val body: String,
    val helpers: String = "",
)

/**
 * One construct of DESIGN-E 6.4 (its row number [row]): a [supported] fixture that the client lowering translates,
 * and a [rejected] one that it reports as UNSUPPORTED with a reason that contains [reason].
 */
data class Construct(
    val row: Int,
    val title: String,
    val supported: Snippet,
    val rejected: Snippet,
    val reason: String,
)

/** The construct fixtures of DESIGN-E 6.4, one per row, in row order (E0: FIR shapes; E2: lowering). */
object ClientConstructs {
    private const val SUPPLIED = "return Data(value = callable.call(), ttl = 0L)"

    val all =
        listOf(
            Construct(
                1,
                "?.let { ...; return it } ?: run { return ... } (non-local return)",
                Snippet(
                    """
                    val cur = config.get.invoke(key)
                    cur?.let {
                        return it
                    } ?: run {
                        $SUPPLIED
                    }
                    """,
                ),
                Snippet(
                    """
                    run {
                        if (config.maxAttempt < 0) return@run
                        config.lock.tryLock(key, Kind.CREATE)
                    }
                    $SUPPLIED
                    """,
                ),
                "return@run",
            ),
            Construct(
                2,
                "if as an expression",
                Snippet(
                    """
                    val token = if (config.mode == Mode.ONLY_UPDATE) null else config.lock.tryLock(key, Kind.CREATE)
                    if (token != null) config.lock.unLock(key, Kind.CREATE, token)
                    $SUPPLIED
                    """,
                ),
                Snippet(
                    """
                    val supplier = if (config.mode == Mode.ONLY_UPDATE) callable else Callable<T?> { null }
                    return Data(value = supplier.call(), ttl = 0L)
                    """,
                ),
                "java.util.concurrent.Callable",
            ),
            Construct(
                3,
                "try with a return and a finally",
                Snippet(
                    """
                    val token = config.lock.tryLock(key, Kind.CREATE)
                    if (token != null) {
                        var done = false
                        try {
                            val cached = config.get.invoke(key)
                            if (cached != null) return cached
                            done = true
                        } finally {
                            if (!done) {
                                config.lock.unLock(key, Kind.CREATE, token)
                            }
                        }
                        config.lock.unLock(key, Kind.CREATE, token)
                    }
                    $SUPPLIED
                    """,
                ),
                Snippet(
                    """
                    try {
                        val cached = config.get.invoke(key)
                        if (cached != null) return cached
                    } finally {
                        return Data(value = callable.call(), ttl = 0L)
                    }
                    """,
                ),
                "inside a finally",
            ),
            Construct(
                4,
                "runCatching { }.getOrElse { }",
                Snippet(
                    """
                    val cur = read(key)
                    if (cur != null) return cur
                    $SUPPLIED
                    """,
                    """
                    private fun read(key: String): Data<T>? =
                        runCatching {
                            config.get.invoke(key)
                        }.getOrElse {
                            throw ClientError(Code.GET, it)
                        }
                    """,
                ),
                Snippet(
                    """
                    val cur = runCatching { config.get.invoke(key) }.getOrNull()
                    if (cur != null) return cur
                    $SUPPLIED
                    """,
                ),
                "getOrNull",
            ),
            Construct(
                5,
                "try / catch (e: Exception) / finally",
                Snippet(
                    """
                    val token = config.lock.tryLock(key, Kind.UPDATE)
                    val data = Data(value = callable.call(), ttl = 0L)
                    write(key, data, token)
                    return data
                    """,
                    """
                    private fun write(
                        key: String,
                        value: Data<T>,
                        token: String?,
                    ) {
                        try {
                            config.set.invoke(key, value, value.ttl)
                        } catch (e: Exception) {
                            throw ClientError(Code.SET, e)
                        } finally {
                            if (token != null) {
                                val unlocked = config.lock.unLock(key, Kind.UPDATE, token)
                                if (!unlocked) {
                                    log.debug("already released for key '{}'", key)
                                }
                            }
                        }
                    }
                    """,
                ),
                Snippet(
                    """
                    try {
                        config.set.invoke(key, Data(value = callable.call(), ttl = 0L), 0L)
                    } catch (e: java.io.IOException) {
                        log.warn("io for key '{}'", key, e)
                    }
                    $SUPPLIED
                    """,
                ),
                "java.io.IOException",
            ),
            Construct(
                6,
                "catch (e: RejectedExecutionException) with a nested try / catch (e: Exception)",
                Snippet(
                    """
                    val token = config.lock.tryLock(key, Kind.UPDATE)
                    val data = Data(value = callable.call(), ttl = 0L)
                    background(key, token) {
                        config.set.invoke(key, data, 0L)
                        if (token != null) config.lock.unLock(key, Kind.UPDATE, token)
                    }
                    return data
                    """,
                    """
                    private fun background(
                        key: String,
                        token: String?,
                        task: () -> Unit,
                    ) {
                        try {
                            CompletableFuture
                                .runAsync(task, config.executor)
                                .whenComplete { _, e -> if (e != null) log.error("failed for key '{}'", key, e) }
                        } catch (e: RejectedExecutionException) {
                            log.warn("rejected for key '{}'", key, e)
                            if (token != null) {
                                try {
                                    config.lock.unLock(key, Kind.UPDATE, token)
                                } catch (unlockError: Exception) {
                                    log.error("unlock failed for key '{}'", key, unlockError)
                                }
                            }
                        }
                    }
                    """,
                ),
                Snippet(
                    """
                    try {
                        CompletableFuture.runAsync({ config.set.invoke(key, Data(value = null, ttl = 0L), 0L) }, config.executor)
                    } catch (e: RejectedExecutionException) {
                        if (e.message != null) log.warn("rejected for key '{}'", key)
                    }
                    $SUPPLIED
                    """,
                ),
                "exception value",
            ),
            Construct(
                7,
                "while, break in a catch, return in a try",
                Snippet(
                    """
                    var attempts = 0
                    var failures = 0
                    while (attempts < config.maxAttempt) {
                        attempts++
                        try {
                            val cached = config.get.invoke(key)
                            if (cached != null) return cached
                            failures = 0
                        } catch (e: Exception) {
                            if (++failures >= Limits.MAX_FAILURES) break
                        }
                    }
                    $SUPPLIED
                    """,
                ),
                Snippet(
                    """
                    var attempts = 0
                    do {
                        attempts++
                        val cached = config.get.invoke(key)
                        if (cached != null) return cached
                    } while (attempts < config.maxAttempt)
                    $SUPPLIED
                    """,
                ),
                "do-while",
            ),
            Construct(
                8,
                "calls of function-typed values",
                Snippet(
                    """
                    val a = config.get.invoke(key)
                    val b = config.get(key)
                    val c = fetch(config.get, key)
                    if (a != null) return a
                    if (b != null) return b
                    if (c != null) return c
                    $SUPPLIED
                    """,
                    """
                    private fun fetch(
                        fn: (String) -> Data<T>?,
                        key: String,
                    ): Data<T>? = fn.invoke(key)
                    """,
                ),
                Snippet(
                    """
                    val local: (String) -> Data<T>? = { k -> config.get.invoke(k) }
                    val a = local(key)
                    if (a != null) return a
                    $SUPPLIED
                    """,
                ),
                "not a trusted value",
            ),
            Construct(
                9,
                "a lambda passed to a helper's function-typed parameter, then to runAsync (a task)",
                Snippet(
                    """
                    val data = Data(value = callable.call(), ttl = 0L)
                    spawn { config.set.invoke(key, data, 0L) }
                    return data
                    """,
                    """
                    private fun spawn(task: () -> Unit) {
                        CompletableFuture.runAsync(task, config.executor)
                    }
                    """,
                ),
                Snippet(
                    """
                    var data = Data(value = callable.call(), ttl = 0L)
                    spawn { config.set.invoke(key, data, 0L) }
                    data = Data(value = callable.call(), ttl = 1L)
                    return data
                    """,
                    """
                    private fun spawn(task: () -> Unit) {
                        CompletableFuture.runAsync(task, config.executor)
                    }
                    """,
                ),
                "captures var data",
            ),
            Construct(
                10,
                ".whenComplete { _, e -> ... } that only logs",
                Snippet(
                    """
                    val data = Data(value = callable.call(), ttl = 0L)
                    CompletableFuture
                        .runAsync({ config.set.invoke(key, data, 0L) }, config.executor)
                        .whenComplete { _, e -> if (e != null) report(key, e) }
                    return data
                    """,
                    """
                    private fun report(
                        key: String,
                        throwable: Throwable,
                    ) {
                        log.error("task failed for key '{}'", key, throwable)
                    }
                    """,
                ),
                Snippet(
                    """
                    val token = config.lock.tryLock(key, Kind.UPDATE)
                    val data = Data(value = callable.call(), ttl = 0L)
                    CompletableFuture
                        .runAsync({ config.set.invoke(key, data, 0L) }, config.executor)
                        .whenComplete { _, _ -> if (token != null) config.lock.unLock(key, Kind.UPDATE, token) }
                    return data
                    """,
                ),
                "whenComplete callback",
            ),
            Construct(
                11,
                "Thread.sleep and Thread.currentThread().interrupt()",
                Snippet(
                    """
                    pause(key)
                    $SUPPLIED
                    """,
                    """
                    private fun pause(key: String) {
                        try {
                            Thread.sleep(Limits.INTERVAL)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            log.warn("interrupted for key '{}'", key)
                            throw ClientError(Code.GET, e)
                        }
                    }
                    """,
                ),
                Snippet(
                    """
                    Thread.yield()
                    $SUPPLIED
                    """,
                ),
                "java.lang.Thread.yield",
            ),
            Construct(
                12,
                "a data class built by its secondary constructor",
                Snippet(
                    """
                    return build(callable.call(), 0L)
                    """,
                    """
                    private fun build(
                        value: T?,
                        ttl: Long,
                    ): Data<T> =
                        Data(
                            value = value,
                            ttl = ttl,
                        )
                    """,
                ),
                Snippet(
                    """
                    val pair = Pair(callable.call(), key)
                    return Data(value = pair.first, ttl = 0L)
                    """,
                ),
                "kotlin.Pair",
            ),
            Construct(
                13,
                "enum comparisons",
                Snippet(
                    """
                    val onlyCreate = config.mode == Mode.ONLY_CREATE
                    val kind = Kind.UPDATE
                    val token = if (onlyCreate) null else config.lock.tryLock(key, kind)
                    if (token != null) config.lock.unLock(key, kind, token)
                    $SUPPLIED
                    """,
                ),
                Snippet(
                    """
                    val kind =
                        when (config.mode) {
                            Mode.ONLY_CREATE -> Kind.CREATE
                            else -> Kind.UPDATE
                        }
                    val token = config.lock.tryLock(key, kind)
                    if (token != null) config.lock.unLock(key, kind, token)
                    $SUPPLIED
                    """,
                ),
                "when with subject",
            ),
            Construct(
                14,
                "throw ClientError(Code.X, cause = ...)",
                Snippet(
                    """
                    return Data(value = call(callable, key, Kind.CREATE, null), ttl = 0L)
                    """,
                    """
                    private fun call(
                        callable: Callable<T?>,
                        key: String,
                        kind: Kind?,
                        token: String?,
                    ): T? =
                        runCatching {
                            callable.call()
                        }.getOrElse {
                            if (token != null && kind != null) {
                                config.lock.unLock(key, kind, token)
                            }
                            throw ClientError(Code.SUP, cause = it)
                        }
                    """,
                ),
                Snippet(
                    """
                    val code = if (config.mode == Mode.BOTH) Code.GET else Code.SET
                    if (config.maxAttempt < 0) throw ClientError(code)
                    $SUPPLIED
                    """,
                ),
                "error code",
            ),
            Construct(
                15,
                "a null check of a nullable enum value",
                Snippet(
                    """
                    val token = config.lock.tryLock(key, Kind.CREATE)
                    release(key, if (token == null) null else Kind.CREATE, token)
                    $SUPPLIED
                    """,
                    """
                    private fun release(
                        key: String,
                        kind: Kind?,
                        token: String?,
                    ) {
                        if (token != null && kind != null) config.lock.unLock(key, kind, token)
                    }
                    """,
                ),
                Snippet(
                    """
                    val token = config.lock.tryLock(key, Kind.CREATE)
                    val kind: Kind? = if (token == null) null else Kind.CREATE
                    if (token != null) config.lock.unLock(key, kind!!, token)
                    $SUPPLIED
                    """,
                ),
                "!!",
            ),
            Construct(
                16,
                "slf4j logging",
                Snippet(
                    """
                    log.info("run for key '{}'", key)
                    $SUPPLIED
                    """,
                ),
                Snippet(
                    """
                    log.info("token {}", config.lock.tryLock(key, Kind.CREATE))
                    $SUPPLIED
                    """,
                ),
                "logging",
            ),
        )
}
