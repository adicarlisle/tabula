package app.tabula.core

/** One incoming message, taken from another app's notification. */
data class Message(val id: String, val app: String, val sender: String, val text: String, val postedAt: Long)

/** All messaging apps feed one line; the user sees and handles one message at a time. */
class MessageQueue {
    private val items = mutableListOf<Message>()
    private val off = mutableSetOf<String>()

    val size: Int get() = items.size

    fun current(): Message? = items.firstOrNull()

    fun all(): List<Message> = items.toList()

    fun add(message: Message) {
        if (message.app in off) return
        val existing = items.indexOfFirst { it.id == message.id }
        if (existing >= 0) { items[existing] = message; return }
        val at = items.indexOfFirst { it.postedAt > message.postedAt }
        if (at < 0) items += message else items.add(at, message)
    }

    fun ignore(id: String) { items.removeAll { it.id == id } }

    fun ignoreAll() = items.clear()

    fun later() { if (items.size > 1) items += items.removeAt(0) }

    fun turnOff(app: String) { off += app; items.removeAll { it.app == app } }

    fun turnOn(app: String) { off -= app }

    fun isOff(app: String): Boolean = app in off
}
