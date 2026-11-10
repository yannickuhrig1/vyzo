package fr.yannickuhrig.vyzo.data.epg

import fr.yannickuhrig.vyzo.data.db.ProgramEntity
import fr.yannickuhrig.vyzo.data.db.ProgramSlot

/** Garde les programmes de [incoming] qui tiennent entièrement dans les créneaux libres laissés par [taken]. */
fun programsFittingGaps(taken: List<ProgramSlot>, incoming: List<ProgramEntity>): List<ProgramEntity> {
    if (taken.isEmpty()) return incoming
    val busy = ArrayList<LongArray>()
    taken.sortedBy { it.start }.forEach { slot ->
        val last = busy.lastOrNull()
        if (last != null && slot.start <= last[1]) last[1] = maxOf(last[1], slot.stop)
        else busy += longArrayOf(slot.start, slot.stop)
    }
    return incoming.filterNot { overlapsAny(busy, it.start, it.stop) }
}

private fun overlapsAny(busy: List<LongArray>, start: Long, stop: Long): Boolean {
    var low = 0
    var high = busy.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (busy[middle][1] > start) high = middle else low = middle + 1
    }
    return low < busy.size && busy[low][0] < stop
}
