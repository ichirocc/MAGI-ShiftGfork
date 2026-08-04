package com.magi.app.v6.engine

/** staff, day, oldShift を3つずつ積む undo ログ */
class UndoStack(capacityCells: Int = 64) {
    private var buf = IntArray(capacityCells * 3)
    var size: Int = 0
        private set

    fun clear() {
        size = 0
    }

    fun ensureCapacity(cells: Int) {
        val need = cells * 3
        if (need <= buf.size) return
        var cap = buf.size.coerceAtLeast(12)
        while (cap < need) cap *= 2
        buf = buf.copyOf(cap)
    }

    fun record(staff: Int, day: Int, oldShift: Int) {
        if (size + 3 > buf.size) ensureCapacity(size / 3 + 8)
        buf[size++] = staff
        buf[size++] = day
        buf[size++] = oldShift
    }

    fun snapshot(): IntArray = buf.copyOf(size)

    fun revertInto(schedule: Array<IntArray>) {
        var u = size - 3
        while (u >= 0) {
            schedule[buf[u]][buf[u + 1]] = buf[u + 2]
            u -= 3
        }
        size = 0
    }
}
