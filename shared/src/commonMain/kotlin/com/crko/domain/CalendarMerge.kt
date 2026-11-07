package com.crko.domain

/**
 * Pure merge rules for records brought in by a calendar file or a subscription feed. Nothing here
 * touches storage: the caller supplies the identifier factory and applies the returned list.
 */
data class MergeResult<T>(val items: List<T>, val changed: Int, val removed: Int)

/**
 * The cancellations [import] may apply: one whose `SEQUENCE` is older than every stored copy of
 * that UID is stale and must not remove the newer record (RFC 5546 conflict resolution).
 */
fun gatedCancellations(import: IcsImport, storedEvents: List<Event>): Set<String> =
    import.cancelledUids.filterTo(mutableSetOf()) { uid ->
        val incoming = import.cancelled.firstOrNull { it.uid == uid }?.sequence ?: 0
        val stored = storedEvents.filter { matchesUid(it, uid) }.maxOfOrNull { it.sequence } ?: 0
        incoming >= stored
    }

/**
 * Merges parsed events. A known UID is replaced when the file carries a newer
 * `SEQUENCE`/`DTSTAMP`, so re-importing an updated file does not keep stale copies. With
 * [source] set, records proven to come from that file are also removed when the new file marks
 * their series cancelled or no longer lists them.
 */
fun mergeEvents(
    base: List<Event>,
    incoming: List<Event>,
    cancelledUids: Set<String>,
    source: String?,
    removeVanished: Boolean,
    describedUids: Set<String> = emptySet(),
    idProvider: () -> String,
    /**
     * UIDs with changes still queued for upload. A two-way sync passes them so a record the user
     * edited locally is never removed just because the server no longer mentions it.
     */
    protectedUids: Set<String> = emptySet(),
): MergeResult<Event> {
    val merged = base.toMutableList()
    var removed = 0
    if (cancelledUids.isNotEmpty()) {
        val before = merged.size
        merged.removeAll { event -> cancelledUids.any { uid -> matchesUid(event, uid) } }
        removed += before - merged.size
    }
    // A record that simply stopped appearing in the file used to be kept because without import
    // provenance a partial export looks exactly like a removal. Provenance closes that gap: only
    // records this very file brought in are eligible, and only when the file still describes the
    // same kind of content.
    if (removeVanished) {
        val present = describedUids.ifEmpty { incoming.mapNotNullTo(mutableSetOf()) { it.icsUid } }
        val before = merged.size
        merged.removeAll { event ->
            // A file import only owns records it brought in; a subscription owns its whole
            // calendar, so anything upstream no longer describes is gone.
            (source == null || event.importSource == source) &&
                event.icsUid != null && event.icsUid !in present &&
                event.icsUid !in protectedUids
        }
        removed += before - merged.size
    }
    var changed = 0
    val seen = mutableSetOf<String>()
    incoming.forEach { parsed ->
        val incoming = parsed.copy(local = parsed.local.copy(importSource = parsed.importSource ?: source))
        val uid = incoming.icsUid
        if (uid == null) {
            merged += incoming.copy(id = idProvider())
            changed++
            return@forEach
        }
        // A master and every exception share the UID, so the storage identity is the pair.
        if (!seen.add(uid + "|" + incoming.recurrenceId)) return@forEach
        val exact = merged.indexOfFirst { it.icsUid == uid && it.recurrenceId == incoming.recurrenceId }
        val index = if (exact >= 0) {
            exact
        } else if (incoming.recurrenceId == null) {
            merged.indexOfFirst { matchesOwnExport(it.id, uid) }
        } else {
            -1
        }
        if (index >= 0) {
            val existing = merged[index]
            if (isNewer(incoming.sequence, incoming.dtStamp, incoming.lastModified, existing.sequence, existing.dtStamp, existing.lastModified)) {
                merged[index] = incoming.copy(id = existing.id)
                changed++
            } else if (existing.importSource != incoming.importSource) {
                // Provenance follows the newest import even when the content itself is stale,
                // or the next import of the renamed file would remove the record after all.
                merged[index] = existing.copy(local = existing.local.copy(importSource = incoming.importSource))
            }
        } else {
            merged += incoming.copy(id = idProvider())
            changed++
        }
    }
    return MergeResult(merged, changed, removed)
}

fun mergeTodos(
    base: List<Todo>,
    incoming: List<Todo>,
    source: String?,
    removeVanished: Boolean,
    describedUids: Set<String> = emptySet(),
    idProvider: () -> String,
    protectedUids: Set<String> = emptySet(),
): MergeResult<Todo> {
    val merged = base.toMutableList()
    var removed = 0
    if (removeVanished) {
        val present = describedUids.ifEmpty { incoming.mapNotNullTo(mutableSetOf()) { it.icsUid } }
        val before = merged.size
        merged.removeAll { todo ->
            (source == null || todo.importSource == source) &&
                todo.icsUid != null && todo.icsUid !in present &&
                todo.icsUid !in protectedUids
        }
        removed += before - merged.size
    }
    var changed = 0
    val seen = mutableSetOf<String>()
    incoming.forEach { parsed ->
        val incoming = parsed.copy(local = parsed.local.copy(importSource = parsed.importSource ?: source))
        val uid = incoming.icsUid
        if (uid == null) {
            merged += incoming.copy(id = idProvider())
            changed++
            return@forEach
        }
        // A series master and each completed occurrence share the UID, so the storage identity is
        // the pair, exactly like an event's exception and its master.
        if (!seen.add(uid + "|" + incoming.recurrenceId)) return@forEach
        val exact = merged.indexOfFirst { it.icsUid == uid && it.recurrenceId == incoming.recurrenceId }
        val index = when {
            exact >= 0 -> exact
            incoming.recurrenceId == null && '#' !in uid -> merged.indexOfFirst { matchesOwnExport(it.id, uid) }
            else -> -1
        }
        if (index >= 0) {
            val existing = merged[index]
            if (isNewer(incoming.sequence, incoming.dtStamp, incoming.lastModified, existing.sequence, existing.dtStamp, existing.lastModified)) {
                merged[index] = incoming.copy(id = existing.id)
                changed++
            } else if (existing.importSource != incoming.importSource) {
                merged[index] = existing.copy(local = existing.local.copy(importSource = incoming.importSource))
            }
        } else {
            merged += incoming.copy(id = idProvider())
            changed++
        }
    }
    return MergeResult(merged, changed, removed)
}

fun mergeJournals(
    base: List<Journal>,
    incoming: List<Journal>,
    source: String?,
    removeVanished: Boolean,
    describedUids: Set<String> = emptySet(),
    idProvider: () -> String,
    protectedUids: Set<String> = emptySet(),
): MergeResult<Journal> {
    val merged = base.toMutableList()
    var removed = 0
    if (removeVanished) {
        val present = describedUids.ifEmpty { incoming.mapNotNullTo(mutableSetOf()) { it.icsUid } }
        val before = merged.size
        merged.removeAll { journal ->
            (source == null || journal.importSource == source) &&
                journal.icsUid != null && journal.icsUid !in present &&
                journal.icsUid !in protectedUids
        }
        removed += before - merged.size
    }
    var changed = 0
    val seen = mutableSetOf<String>()
    incoming.forEach { parsed ->
        val incoming = parsed.copy(local = parsed.local.copy(importSource = parsed.importSource ?: source))
        val uid = incoming.icsUid
        if (uid == null) {
            merged += incoming.copy(id = idProvider())
            changed++
            return@forEach
        }
        if (!seen.add(uid)) return@forEach
        val exact = merged.indexOfFirst { it.icsUid == uid }
        val index = if (exact >= 0) exact else if ('#' !in uid) merged.indexOfFirst { matchesOwnExport(it.id, uid) } else -1
        if (index >= 0) {
            val existing = merged[index]
            if (isNewer(incoming.sequence, incoming.dtStamp, incoming.lastModified, existing.sequence, existing.dtStamp, existing.lastModified)) {
                merged[index] = incoming.copy(id = existing.id)
                changed++
            } else if (existing.importSource != incoming.importSource) {
                merged[index] = existing.copy(local = existing.local.copy(importSource = incoming.importSource))
            }
        } else {
            merged += incoming.copy(id = idProvider())
            changed++
        }
    }
    return MergeResult(merged, changed, removed)
}

/**
 * One definition per `TZID`: the incoming file's copy wins, while zones it does not mention keep
 * the definition already stored.
 */
fun mergeCalendarTimeZones(
    existing: List<CalendarTimeZone>,
    incoming: List<CalendarTimeZone>,
): List<CalendarTimeZone> {
    val merged = LinkedHashMap<String, CalendarTimeZone>()
    (existing + incoming).forEach { zone ->
        val id = normalizeTimeZoneId(zone.tzid)
        if (id.isNotEmpty() && zone.definition.isNotBlank()) {
            merged[id] = zone.copy(tzid = id)
        }
    }
    return merged.values.toList()
}

/**
 * Whether [event] belongs to [uid]. The master and its exceptions share the UID, so no synthetic
 * suffix is involved; own-export ids (`<id>@crko`) are recognized as before.
 */
internal fun matchesUid(event: Event, uid: String): Boolean =
    event.icsUid == uid || matchesOwnExport(event.id, uid)

/** Whether [uid] is the own-export id (`<recordId>@crko`) of [recordId]. */
internal fun matchesOwnExport(recordId: String?, uid: String): Boolean {
    if (!uid.endsWith("@crko")) return false
    return uid.removeSuffix("@crko") == recordId
}

/** `SEQUENCE` wins, then `DTSTAMP`, then `LAST-MODIFIED`; missing metadata never overwrites. */
internal fun isNewer(
    sequence: Int,
    dtStamp: String?,
    lastModified: String?,
    existingSequence: Int,
    existingDtStamp: String?,
    existingLastModified: String?,
): Boolean {
    if (sequence != existingSequence) return sequence > existingSequence
    // An unparseable value must not hide the other property, on either side: a malformed DTSTAMP
    // falls through to LAST-MODIFIED just like an absent one.
    val incoming = parseInstantOrNull(dtStamp) ?: parseInstantOrNull(lastModified) ?: return false
    val existing = parseInstantOrNull(existingDtStamp) ?: parseInstantOrNull(existingLastModified)
    return existing == null || incoming > existing
}

private fun parseInstantOrNull(value: String?): kotlin.time.Instant? =
    value?.let { runCatching { kotlin.time.Instant.parse(it) }.getOrNull() }
