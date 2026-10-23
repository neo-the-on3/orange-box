package io.github.neotheon3.orangebox.core.obd.pid

/**
 * Mode 01 supported-PID set, built from the 4-byte bitmaps answered by PIDs 00, 20, 40, 60, 80, A0, C0.
 * Bit 7 of byte A in the bitmap for base `b` means PID `b + 1` is supported; the last bit (`b + 0x20`)
 * means the next bitmap exists.
 */
class SupportedPids(private val pids: Set<Int>) {
    operator fun contains(pid: Int) = pid in pids
    operator fun contains(pid: ObdPid) = pid.pid in pids
    val all: Set<Int> get() = pids

    fun filter(candidates: Collection<ObdPid>): List<ObdPid> = candidates.filter { it in this }

    override fun toString() = pids.sorted().joinToString(prefix = "SupportedPids[", postfix = "]") { "%02X".format(it) }

    companion object {
        val BITMAP_BASES = listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0)

        /** PIDs announced by one bitmap (excluding the next-bitmap bit). */
        fun decodeBitmap(base: Int, data: IntArray): Set<Int> {
            require(data.size >= 4) { "bitmap needs 4 bytes" }
            val bits = (data[0].toLong() shl 24) or (data[1].toLong() shl 16) or (data[2].toLong() shl 8) or data[3].toLong()
            return (1..32).filter { i -> bits and (1L shl (32 - i)) != 0L }.map { base + it }.toSet()
        }

        fun fromBitmaps(bitmaps: Map<Int, IntArray>): SupportedPids =
            SupportedPids(bitmaps.flatMap { (base, data) -> decodeBitmap(base, data) }.filterNot { it in BITMAP_BASES || it == 0xE0 }.toSet())
    }
}
