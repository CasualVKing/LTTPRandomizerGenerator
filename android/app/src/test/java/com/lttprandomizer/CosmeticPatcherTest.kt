package com.lttprandomizer

import org.junit.Assert.*
import org.junit.Test

class CosmeticPatcherTest {

    /** 2 MB ROM (post-BPS expansion size). */
    private fun make2MbRom(): ByteArray = ByteArray(2 * 1024 * 1024)

    // ── Heart beep ──────────────────────────────────────────────────────────

    @Test
    fun `heart beep off writes 0x00`() {
        val rom = make2MbRom()
        CosmeticPatcher.apply(rom, CustomizationSettings(heartBeepSpeed = "off"))
        assertEquals(0x00.toByte(), rom[0x180033])
    }

    @Test
    fun `heart beep double writes 0x10`() {
        val rom = make2MbRom()
        CosmeticPatcher.apply(rom, CustomizationSettings(heartBeepSpeed = "double"))
        assertEquals(0x10.toByte(), rom[0x180033])
    }

    @Test
    fun `heart beep normal writes 0x20`() {
        val rom = make2MbRom()
        CosmeticPatcher.apply(rom, CustomizationSettings(heartBeepSpeed = "normal"))
        assertEquals(0x20.toByte(), rom[0x180033])
    }

    @Test
    fun `heart beep half writes 0x40`() {
        val rom = make2MbRom()
        CosmeticPatcher.apply(rom, CustomizationSettings(heartBeepSpeed = "half"))
        assertEquals(0x40.toByte(), rom[0x180033])
    }

    @Test
    fun `heart beep quarter writes 0x80`() {
        val rom = make2MbRom()
        CosmeticPatcher.apply(rom, CustomizationSettings(heartBeepSpeed = "quarter"))
        assertEquals(0x80.toByte(), rom[0x180033])
    }

    // ── Heart color ─────────────────────────────────────────────────────────

    @Test
    fun `heart color red writes 0x00`() {
        val rom = make2MbRom()
        CosmeticPatcher.apply(rom, CustomizationSettings(heartColor = "red"))
        assertEquals(0x00.toByte(), rom[0x187020])
    }

    @Test
    fun `heart color blue writes 0x01`() {
        val rom = make2MbRom()
        CosmeticPatcher.apply(rom, CustomizationSettings(heartColor = "blue"))
        assertEquals(0x01.toByte(), rom[0x187020])
    }

    @Test
    fun `heart color green writes 0x02`() {
        val rom = make2MbRom()
        CosmeticPatcher.apply(rom, CustomizationSettings(heartColor = "green"))
        assertEquals(0x02.toByte(), rom[0x187020])
    }

    @Test
    fun `heart color yellow writes 0x03`() {
        val rom = make2MbRom()
        CosmeticPatcher.apply(rom, CustomizationSettings(heartColor = "yellow"))
        assertEquals(0x03.toByte(), rom[0x187020])
    }

    // ── Menu speed ──────────────────────────────────────────────────────────

    @Test
    fun `menu speed normal writes 0x08`() {
        val rom = make2MbRom()
        CosmeticPatcher.apply(rom, CustomizationSettings(menuSpeed = "normal"))
        assertEquals(0x08.toByte(), rom[0x180048])
    }

    @Test
    fun `menu speed instant writes 0xE8`() {
        val rom = make2MbRom()
        CosmeticPatcher.apply(rom, CustomizationSettings(menuSpeed = "instant"))
        assertEquals(0xE8.toByte(), rom[0x180048])
    }

    // ── Quick swap ──────────────────────────────────────────────────────────

    @Test
    fun `quick swap on writes 0x01`() {
        val rom = make2MbRom()
        CosmeticPatcher.apply(rom, CustomizationSettings(quickSwap = "on"))
        assertEquals(0x01.toByte(), rom[0x18004B])
    }

    @Test
    fun `quick swap off writes 0x00`() {
        val rom = make2MbRom()
        CosmeticPatcher.apply(rom, CustomizationSettings(quickSwap = "off"))
        assertEquals(0x00.toByte(), rom[0x18004B])
    }

    // ── Checksum recalculation ──────────────────────────────────────────────

    @Test
    fun `apply recalculates checksum`() {
        val rom = make2MbRom()
        CosmeticPatcher.apply(rom, CustomizationSettings())

        val complement = readU16LE(rom, 0x7FDC)
        val checksum = readU16LE(rom, 0x7FDE)
        assertEquals(0xFFFF, complement xor checksum)
    }

    @Test
    fun `apply returns the same array for chaining`() {
        val rom = make2MbRom()
        val result = CosmeticPatcher.apply(rom, CustomizationSettings())
        assertSame(rom, result)
    }

    private fun readU16LE(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}
