package app.conversate.glass.meeting

/** A blocking signal for the local HTTP worker; waiting releases the monitor and uses no poll loop. */
class BridgeWaiter {
    private val monitor = java.lang.Object()
    private var generation = 0L
    fun version(): Long = synchronized(monitor) { generation }
    fun wake() = synchronized(monitor) { generation++; monitor.notifyAll() }
    fun awaitAfter(observed: Long, timeoutMs: Long) = synchronized(monitor) {
        if (generation == observed && timeoutMs > 0) monitor.wait(timeoutMs)
    }
}
