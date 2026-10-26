package judge

/** Clock that the shadowed nowToEpochTime() reads. It replaces only tryLock's clock, not System.currentTimeMillis(). */
object FakeClock {
    /** The fixed reading in epoch milliseconds. A negative value means: read the real clock. */
    @Volatile var fixed: Long = -1L

    fun read(): Long = if (fixed >= 0) fixed else System.currentTimeMillis()
}
