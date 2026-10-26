package kv.model

/** What a built-in client model call means to the client lowering (DESIGN-E 3.3, 6.5). */
enum class ClientRole {
    /** Hands its Runnable argument to an executor: a task (DESIGN-E 3.4). */
    SPAWN,

    /** A callback the tool checks has no effect: it only logs (DESIGN-E 6.4 row 10). */
    CALLBACK,

    /** A call that returns normally or throws one of the model's kinds; no other effect. */
    CALL,

    /** No effect on the model and no exception. */
    NO_EFFECT,

    /** `runCatching { }`: a try that catches every Throwable (DESIGN-E 3.3). */
    RUN_CATCHING,

    /** `Result.getOrElse { }` on a runCatching result: the catch block. */
    GET_OR_ELSE,
}

/**
 * One library callable of a built-in client model (DESIGN-E 6.5). [owner] is the class id, or the package with `/`
 * for a top-level function; [throws] are the exception kinds of DESIGN-E 3.5 the call may throw besides an Error.
 */
data class ClientModel(
    val model: String,
    val owner: String,
    val name: String,
    val role: ClientRole,
    val throws: List<String> = emptyList(),
)

/**
 * The built-in models of the client lowering (DESIGN-E 3.3, 6.5), apart from the L1 table [TrustedModels], so the
 * object and instance module lowering never matches them. A client sidecar may `use` these and the L1 models.
 */
object ClientModels {
    val entries: List<ClientModel> =
        listOf(
            // CompletableFuture.runAsync(task, executor) (JDK 21 CompletableFuture.java:2031-2034, 1815-1820;
            // Executor.java:126-136): the executor accepts the task, which runs later once, or execute throws
            // RejectedExecutionException and the task never runs. A3: an accepted task runs.
            ClientModel("jdk.CompletableFuture.runAsync", "java/util/concurrent/CompletableFuture", "runAsync", ClientRole.SPAWN, listOf("REE")),
            // whenComplete(callback) on the future runAsync returned: the tool checks that the callback only logs.
            ClientModel("jdk.CompletableFuture.whenComplete", "java/util/concurrent/CompletableFuture", "whenComplete", ClientRole.CALLBACK),
            // Thread.sleep(ms): returns, or throws InterruptedException.
            ClientModel("jdk.Thread.sleep", "java/lang/Thread", "sleep", ClientRole.CALL, listOf("INTR")),
            // Thread.currentThread().interrupt(): sets the interrupt flag, which the model does not track.
            ClientModel("jdk.Thread.interrupt", "java/lang/Thread", "interrupt", ClientRole.NO_EFFECT),
            ClientModel("jdk.Thread.interrupt", "java/lang/Thread", "currentThread", ClientRole.NO_EFFECT),
            // runCatching { } and getOrElse { } (kotlin-stdlib 2.0.21 Result.kt:141-147, 188-196): a try that
            // catches every Throwable, and its catch block.
            ClientModel("kotlin.runCatching", "kotlin", "runCatching", ClientRole.RUN_CATCHING),
            ClientModel("kotlin.runCatching", "kotlin", "getOrElse", ClientRole.GET_OR_ELSE),
        )

    /** Names a client sidecar may `use` besides the L1 models. */
    val builtIn: Set<String> = entries.map { it.model }.toSet()

    /** The model of the callable `owner.name`, or null. */
    fun lookup(
        owner: String,
        name: String,
    ): ClientModel? = entries.find { it.owner == owner && it.name == name }
}
