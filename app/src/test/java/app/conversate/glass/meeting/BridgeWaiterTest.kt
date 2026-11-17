package app.conversate.glass.meeting

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BridgeWaiterTest {
    @Test fun notificationBeforeWaitIsNotLost() {
        val signal = BridgeWaiter()
        val observed = signal.version()
        signal.wake()
        val done = CountDownLatch(1)
        val thread = Thread { signal.awaitAfter(observed, 10000); done.countDown() }
        thread.start()
        try { assertTrue(done.await(1, TimeUnit.SECONDS)) }
        finally { signal.wake(); thread.join(1000) }
    }
    @Test fun newWorkWakesWaitingRequestWithoutPollingDelay() {
        val signal = BridgeWaiter()
        val observed = signal.version()
        val ready = CountDownLatch(1)
        val done = CountDownLatch(1)
        val thread = Thread { ready.countDown(); signal.awaitAfter(observed, 10000); done.countDown() }
        thread.start()
        try {
            assertTrue(ready.await(1, TimeUnit.SECONDS))
            signal.wake()
            assertTrue(done.await(1, TimeUnit.SECONDS))
        } finally { signal.wake(); thread.join(1000) }
    }
    @Test fun emptyRequestReturnsAtItsBoundedTimeout() {
        val signal = BridgeWaiter()
        val started = System.nanoTime()
        signal.awaitAfter(signal.version(), 40)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 25)
    }
}
