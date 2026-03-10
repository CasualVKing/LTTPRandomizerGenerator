package com.lttprandomizer

import org.junit.Assert.*
import org.junit.Test

class RomUtilsTest {

    /** Build a minimal ROM (>= 0x7FE0 bytes) filled with a given value. */
    private fun romOfSize(size: Int, fill: Byte = 0): ByteArray = ByteArray(size) { fill }

    @Test
    fun `writeChecksum zeroes checksum area then writes correct values`() {
        val rom = romOfSize(0x8000) // 32 KB - minimum to cover checksum area

        RomUtils.writeChecksum(rom)

        // For an all-zero ROM the sum is 0, complement is 0xFFFF
        val complement = readU16LE(rom, 0x7FDC)
        val checksum = readU16LE(rom, 0x7FDE)
        assertEquals(0xFFFF, complement)
        assertEquals(0x0000, checksum)
    }

    @Test
    fun `writeChecksum is correct for non-zero ROM`() {
        val rom = romOfSize(0x8000)
        // Write some non-zero bytes (outside the checksum area)
        rom[0] = 0x10
        rom[1] = 0x20

        RomUtils.writeChecksum(rom)

        val checksum = readU16LE(rom, 0x7FDE)
        val complement = readU16LE(rom, 0x7FDC)
        // sum = 0x10 + 0x20 = 0x30
        assertEquals(0x0030, checksum)
        assertEquals(0x0030 xor 0xFFFF, complement)
    }

    @Test
    fun `writeChecksum complement and checksum XOR to 0xFFFF`() {
        val rom = romOfSize(1 * 1024 * 1024) // 1 MB
        // Scatter some data
        for (i in 0 until 256) rom[i * 100] = i.toByte()

        RomUtils.writeChecksum(rom)

        val complement = readU16LE(rom, 0x7FDC)
        val checksum = readU16LE(rom, 0x7FDE)
        assertEquals(0xFFFF, complement xor checksum)
    }

    @Test
    fun `writeChecksum clears previous checksum before recalculating`() {
        val rom = romOfSize(0x8000)
        rom[0] = 0x42

        // Write checksum twice; result should be identical both times
        RomUtils.writeChecksum(rom)
        val first = rom.copyOfRange(0x7FDC, 0x7FE0)

        RomUtils.writeChecksum(rom)
        val second = rom.copyOfRange(0x7FDC, 0x7FE0)

        assertArrayEquals(first, second)
    }

    private fun readU16LE(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}
