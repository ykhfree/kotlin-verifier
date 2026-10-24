// Checks what kotlinx Semaphore.release() does when permits would exceed the initial count.
import kotlinx.coroutines.sync.Semaphore

fun main() {
    val s = Semaphore(1)
    println("tryAcquire=${s.tryAcquire()} permits=${s.availablePermits}")
    s.release()
    println("after one release permits=${s.availablePermits}")
    try {
        s.release()
        println("second release: no exception, permits=${s.availablePermits}")
    } catch (e: Throwable) {
        println("second release threw ${e.javaClass.name}: ${e.message}")
    }
}
