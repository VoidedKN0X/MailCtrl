package com.vknxworks.mailctrl.mail

/**
 * Conversations: which messages of a list belong together (a message and its answers). Pure logic, no Android
 * or mail-library code, so it's tested on the computer.
 *
 * Two messages are in the same conversation when
 * - one names the other's Message-ID in its "References" or "In-Reply-To" header, or both name the same one, or
 * - the headers don't say (some servers don't hand them out): one of them is an answer or a forward ("Re:",
 *   "Fwd:", ...) and the rest of its subject is the other's subject.
 *
 * Messages of different accounts are never put together.
 */
object Threads {
    private val PREFIX = Regex("^(re|fwd|fw|aw|antw|wg|tr|sv|vs|rv|res|enc)(\\[\\d+\\])?\\s*:\\s*", RegexOption.IGNORE_CASE)
    private val ID = Regex("<([^<>\\s]+)>")
    private val SPACES = Regex("\\s+")

    /** Message-IDs named in a header value ("<a@b> <c@d>"), without the angle brackets. At most the last [max]. */
    fun ids(header: String?, max: Int = 20): List<String> {
        if (header.isNullOrBlank()) return emptyList()
        val found = ID.findAll(header).map { it.groupValues[1] }.toList()
        val all = found.ifEmpty { listOfNotNull(header.trim().takeIf { it.isNotEmpty() && !it.contains(' ') }) }
        return all.takeLast(max)
    }

    /** [subject] without answer and forward prefixes, in lower case with single spaces. */
    fun baseSubject(subject: String): String {
        var s = subject.trim()
        while (true) {
            val next = PREFIX.replaceFirst(s, "")
            if (next == s) break
            s = next.trim()
        }
        return SPACES.replace(s, " ").lowercase()
    }

    /** Whether [subject] starts with an answer or forward prefix. */
    fun isAnswer(subject: String): Boolean = PREFIX.containsMatchIn(subject.trim())

    /**
     * [items] grouped into conversations. The conversations come in the order of their first message in
     * [items], and inside one the messages keep the order of [items] (so with a newest-first list: the newest
     * conversation first, and in it the newest message first).
     */
    fun <T> group(
        items: List<T>,
        account: (T) -> String,
        messageId: (T) -> String?,
        refs: (T) -> List<String>,
        subject: (T) -> String,
    ): List<List<T>> {
        val n = items.size
        if (n < 2) return items.map { listOf(it) }
        val parent = IntArray(n) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            // The smaller index stays the root, so a conversation is found by its first message.
            if (ra < rb) parent[rb] = ra else if (rb < ra) parent[ra] = rb
        }

        // Every Message-ID a message has or names, to the first message seen with it.
        val byId = HashMap<String, Int>()
        for (i in 0 until n) {
            val acc = account(items[i])
            val own = messageId(items[i])?.trim()?.removePrefix("<")?.removeSuffix(">")?.takeIf { it.isNotEmpty() }
            for (id in listOfNotNull(own) + refs(items[i])) {
                val key = acc + "\n" + id
                val seen = byId[key]
                if (seen == null) byId[key] = i else union(seen, i)
            }
        }

        // By subject, for an answer or forward whose headers didn't link it.
        val bySubject = HashMap<String, MutableList<Int>>()
        for (i in 0 until n) {
            val base = baseSubject(subject(items[i]))
            if (base.length < 3) continue
            bySubject.getOrPut(account(items[i]) + "\n" + base) { ArrayList() }.add(i)
        }
        for (same in bySubject.values) {
            if (same.size < 2) continue
            val answers = same.filter { isAnswer(subject(items[it])) }
            if (answers.isEmpty()) continue
            for (i in same) union(answers[0], i)
        }

        val groups = LinkedHashMap<Int, MutableList<T>>()
        for (i in 0 until n) groups.getOrPut(find(i)) { ArrayList() }.add(items[i])
        return groups.values.toList()
    }
}
