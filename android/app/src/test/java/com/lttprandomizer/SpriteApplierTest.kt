package com.lttprandomizer

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SpriteApplierTest {

    private val rom2Mb = ByteArray(2 * 1024 * 1024)

    // ── ZSPR format ─────────────────────────────────────────────────────────

    @Test
    fun `apply writes ZSPR pixel data to ROM at 0x80000`() {
        val gfxData = ByteArray(16) { (it + 1).toByte() }
        val palData = ByteArray(8) { (0xA0 + it).toByte() } // 4 main + 4 gloves
        val zspr = buildZspr(gfxData, palData)
        val file = tempFile("test.zspr", zspr)
        val rom = rom2Mb.clone()

        val err = SpriteApplier.apply(file.absolutePath, rom)

        assertNull(err)
        for (i in gfxData.indices) {
            assertEquals("gfx byte $i", gfxData[i], rom[0x80000 + i])
        }
    }

    @Test
    fun `apply writes ZSPR main palette to 0xDD308 and gloves to 0xDEDF5`() {
        val gfxData = ByteArray(16)
        val palData = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
        val zspr = buildZspr(gfxData, palData)
        val file = tempFile("test.zspr", zspr)
        val rom = rom2Mb.clone()

        SpriteApplier.apply(file.absolutePath, rom)

        // Main palette = first 4 bytes
        assertEquals(0x01.toByte(), rom[0xDD308])
        assertEquals(0x02.toByte(), rom[0xDD309])
        assertEquals(0x03.toByte(), rom[0xDD30A])
        assertEquals(0x04.toByte(), rom[0xDD30B])
        // Gloves = last 4 bytes
        assertEquals(0x05.toByte(), rom[0xDEDF5])
        assertEquals(0x06.toByte(), rom[0xDEDF6])
        assertEquals(0x07.toByte(), rom[0xDEDF7])
        assertEquals(0x08.toByte(), rom[0xDEDF8])
    }

    @Test
    fun `apply rejects ZSPR with bad magic`() {
        val data = ByteArray(21) // min header size
        data[0] = 'X'.code.toByte()
        val file = tempFile("bad.zspr", data)

        val err = SpriteApplier.apply(file.absolutePath, rom2Mb.clone())

        assertNotNull(err)
        assertTrue(err!!.contains("magic"))
    }

    @Test
    fun `apply rejects ZSPR that is too small`() {
        val file = tempFile("tiny.zspr", ByteArray(10))

        val err = SpriteApplier.apply(file.absolutePath, rom2Mb.clone())

        assertNotNull(err)
        assertTrue(err!!.contains("too small"))
    }

    @Test
    fun `apply rejects ZSPR with gfx exceeding file size`() {
        val zspr = buildZspr(ByteArray(16), ByteArray(8))
        // Corrupt the gfx length to be huge
        zspr[13] = 0xFF.toByte()
        zspr[14] = 0xFF.toByte()
        val file = tempFile("corrupt.zspr", zspr)

        val err = SpriteApplier.apply(file.absolutePath, rom2Mb.clone())

        assertNotNull(err)
        assertTrue(err!!.contains("exceeds"))
    }

    // ── Legacy .spr format ──────────────────────────────────────────────────

    @Test
    fun `apply writes legacy SPR pixel data to ROM at 0x80000`() {
        val sprData = ByteArray(0x7000) { (it % 256).toByte() }
        val file = tempFile("test.spr", sprData)
        val rom = rom2Mb.clone()

        val err = SpriteApplier.apply(file.absolutePath, rom)

        assertNull(err)
        for (i in 0 until 0x7000) {
            assertEquals("spr byte $i", sprData[i], rom[0x80000 + i])
        }
    }

    @Test
    fun `apply rejects SPR file smaller than 0x7000 bytes`() {
        val file = tempFile("small.spr", ByteArray(100))

        val err = SpriteApplier.apply(file.absolutePath, rom2Mb.clone())

        assertNotNull(err)
        assertTrue(err!!.contains("at least"))
    }

    // ── Unsupported format ──────────────────────────────────────────────────

    @Test
    fun `apply rejects unsupported file extension`() {
        val file = tempFile("test.png", ByteArray(100))

        val err = SpriteApplier.apply(file.absolutePath, rom2Mb.clone())

        assertNotNull(err)
        assertTrue(err!!.contains("Unsupported"))
    }

    // ── Missing file ────────────────────────────────────────────────────────

    @Test
    fun `apply returns error for missing file`() {
        val err = SpriteApplier.apply("/nonexistent/sprite.zspr", rom2Mb.clone())

        assertNotNull(err)
        assertTrue(err!!.contains("not found"))
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Builds a minimal valid ZSPR file with the given gfx and palette data.
     */
    private fun buildZspr(gfxData: ByteArray, palData: ByteArray): ByteArray {
        val headerSize = 21 // minimum header
        val gfxOffset = headerSize
        val palOffset = gfxOffset + gfxData.size
        val total = palOffset + palData.size

        val buf = ByteArray(total)
        // Magic "ZSPR"
        buf[0] = 0x5A; buf[1] = 0x53; buf[2] = 0x50; buf[3] = 0x52
        // bytes 4-8: version/type info (zeroes fine for testing)
        // gfx offset at byte 9 (uint32 LE)
        writeU32LE(buf, 9, gfxOffset)
        // gfx length at byte 13 (uint16 LE)
        writeU16LE(buf, 13, gfxData.size)
        // palette offset at byte 15 (uint32 LE)
        writeU32LE(buf, 15, palOffset)
        // palette length at byte 19 (uint16 LE)
        writeU16LE(buf, 19, palData.size)
        // Copy data
        System.arraycopy(gfxData, 0, buf, gfxOffset, gfxData.size)
        System.arraycopy(palData, 0, buf, palOffset, palData.size)
        return buf
    }

    private fun writeU16LE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun writeU32LE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun tempFile(name: String, content: ByteArray): File {
        val f = File.createTempFile(name.substringBeforeLast('.'), ".${name.substringAfterLast('.')}")
        f.deleteOnExit()
        f.writeBytes(content)
        return f
    }
}
