package com.crko.state.entry

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/** Where a cold-start entry or notification action should land. */
sealed interface EntryTarget {
    data object Today : EntryTarget

    /** Focus screen; [taskId] picks the task a task notification pointed at. */
    data class Focus(val taskId: String? = null) : EntryTarget

    /** Today page with the quick-capture field focused. */
    data object Capture : EntryTarget
}

/**
 * Hands entries from the platform (deep links, notification actions) to the app once the restored
 * state is ready. Entries that arrive before the first frame wait in the buffered channel; the UI
 * collects them after [com.crko.state.AppStateHolder] exists, so a cold start never drops one.
 */
object EntryInbox {
    private val channel = Channel<EntryTarget>(Channel.BUFFERED)

    val entries = channel.receiveAsFlow()

    fun offer(target: EntryTarget) {
        channel.trySend(target)
    }

    /**
     * Parses `crko://today`, `crko://start` and `crko://capture`; `crko://start?taskId=…` also
     * carries a task. Unknown links are ignored rather than landing on an arbitrary page.
     */
    fun offerDeepLink(url: String?) {
        val text = url?.trim().orEmpty()
        if (!text.startsWith("crko://", ignoreCase = true)) return
        val rest = text.substring("crko://".length).trimEnd('/')
        val host = rest.substringBefore('?').substringBefore('/').lowercase()
        val query = rest.substringAfter('?', "")
        val taskId = query
            .split('&')
            .firstOrNull { it.startsWith("taskId=") }
            ?.substringAfter("taskId=")
            ?.takeIf { it.isNotBlank() }
        offer(
            when (host) {
                "today" -> EntryTarget.Today
                "start" -> EntryTarget.Focus(taskId)
                "capture" -> EntryTarget.Capture
                else -> return
            },
        )
    }
}
