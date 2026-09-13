package io.mo.xiaoaiplug.hook

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ReplyCardsTest {
    @Test fun nestedSinksAndDialogAliasesUseTheSameCard() {
        val cards = ReplyCards<Any>()
        val card = cards.getOrCreate("turn-1") { Any() }
        assertSame(card, cards.getOrCreate("turn-1") { error("duplicate card") })
        assertEquals("turn-1", cards.keyFor(card))
    }

    @Test fun historyRebindDoesNotBecomeTheCurrentReply() {
        val cards = ReplyCards<Any>()
        val old = cards.getOrCreate("turn-1") { Any() }
        val current = cards.getOrCreate("turn-2") { Any() }
        assertNotSame(old, current)
        assertEquals("turn-1", cards.keyFor(old))
        assertNull(cards.keyFor(Any())) // 未认领的原生/历史卡不靠全局接管状态猜测
    }

    @Test fun concurrentFallbackAndNativeCardArrivalCreateOneCard() {
        val cards = ReplyCards<Any>()
        val created = AtomicInteger()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = pool.invokeAll((1..40).map { Callable {
                cards.getOrCreate("turn-1") { created.incrementAndGet(); Any() }
            } }).map { it.get() }
            assertEquals(1, created.get())
            results.forEach { assertSame(results.first(), it) }
        } finally {
            pool.shutdownNow()
        }
    }
}
