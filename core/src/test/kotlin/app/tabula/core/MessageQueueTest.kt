package app.tabula.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Examples: messages from every app line up and are shown one at a time. */
class MessageQueueTest {
    private val whatsapp = Message("w1", app = "com.whatsapp", sender = "Priya", text = "still coming at 10?", postedAt = 3)
    private val discord = Message("d1", app = "com.discord", sender = "Design server", text = "standup moved", postedAt = 2)
    private val messenger = Message("m1", app = "com.facebook.orca", sender = "Sam", text = "photos sent", postedAt = 1)

    private fun queueOfThree() = MessageQueue().apply { add(whatsapp); add(discord); add(messenger) }

    @Test
    fun `oldest message comes first, one at a time`() {
        val q = queueOfThree()
        assertEquals(messenger, q.current())
        assertEquals(3, q.size)
        assertEquals(listOf(messenger, discord, whatsapp), q.all())
    }

    @Test
    fun `ignore removes the current message and shows the next`() {
        val q = queueOfThree()
        q.ignore("m1")
        assertEquals(discord, q.current())
    }

    @Test
    fun `later moves the current message to the back`() {
        val q = queueOfThree()
        q.later()
        assertEquals(listOf(discord, whatsapp, messenger), q.all())
    }

    @Test
    fun `turning an app off removes its messages and blocks new ones`() {
        val q = queueOfThree()
        q.turnOff("com.whatsapp")
        q.add(whatsapp.copy(id = "w2", postedAt = 9))
        assertEquals(listOf(messenger, discord), q.all())
        q.turnOn("com.whatsapp")
        q.add(whatsapp.copy(id = "w3", postedAt = 10))
        assertEquals("w3", q.all().last().id)
    }

    @Test
    fun `an updated notification replaces the old one instead of duplicating`() {
        val q = queueOfThree()
        q.add(messenger.copy(text = "photos sent (2)"))
        assertEquals(3, q.size)
        assertEquals("photos sent (2)", q.current()?.text)
    }

    @Test
    fun `ignore all empties the queue`() {
        val q = queueOfThree()
        q.ignoreAll()
        assertNull(q.current())
        assertEquals(0, q.size)
    }
}
