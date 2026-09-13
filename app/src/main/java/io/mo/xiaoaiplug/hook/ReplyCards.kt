package io.mo.xiaoaiplug.hook

import java.util.WeakHashMap

/** 同轮的多个 dialogId 和嵌套 addCard 共用一个显示对象；绑定历史卡不改变其归属。 */
internal class ReplyCards<T : Any> {
    private val cards = LinkedHashMap<String, T>()
    private val owners = WeakHashMap<T, String>()

    @Synchronized fun get(key: String): T? = cards[key]
    @Synchronized fun keyFor(card: T): String? = owners[card]

    @Synchronized fun getOrCreate(key: String, create: () -> T): T {
        cards[key]?.let { return it }
        val card = create()
        cards[key] = card
        owners[card] = key
        if (cards.size > 32) cards.remove(cards.keys.first())
        return card
    }
}
