package app.tabula.core

/** An installed app as the launcher sees it. [id] is the component to launch. */
data class AppEntry(val label: String, val id: String)

object AppSearch {
    fun search(apps: List<AppEntry>, query: String): List<AppEntry> {
        val q = query.trim().lowercase()
        val sorted = apps.sortedBy { it.label.lowercase() }
        if (q.isEmpty()) return sorted
        return sorted.mapNotNull { app -> rank(app.label.lowercase(), q)?.let { it to app } }
            .sortedBy { it.first }
            .map { it.second }
    }

    private fun rank(label: String, q: String): Int? {
        val words = label.split(' ', '-', '_').filter { it.isNotEmpty() }
        return when {
            label.startsWith(q) -> 0
            words.any { it.startsWith(q) } -> 1
            words.joinToString("") { it.take(1) }.startsWith(q) -> 2
            label.contains(q) -> 3
            else -> null
        }
    }
}
