package com.lttprandomizer

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.zip.CRC32

class BpsPatcherTest {

    // ROM must be >= 0x8000 bytes so RomUtils.writeChecksum() can access 0x7FDC–0x7FDF
    private val MIN_ROM_SIZE = 0x8000

    // ── Dict patches ────────────────────────────────────────────────────────

    @Test
    fun `apply writes dictionary patches at correct offsets`() {
        val source = ByteArray(MIN_ROM_SIZE)
        val bps = buildIdentityBps(source)
        val dictPatches = listOf(
            mapOf("0" to listOf(0xAA, 0xBB)),
            mapOf("10" to listOf(0xCC)),
        )

        val result = BpsPatcher.apply(source, bps, dictPatches, 0)

        assertEquals(0xAA.toByte(), result[0])
        assertEquals(0xBB.toByte(), result[1])
        assertEquals(0xCC.toByte(), result[10])
    }

    // ── ROM expansion ───────────────────────────────────────────────────────

    @Test
    fun `apply expands ROM to target size`() {
        val source = ByteArray(MIN_ROM_SIZE)
        val bps = buildIdentityBps(source)

        val result = BpsPatcher.apply(source, bps, emptyList(), 2)

        assertEquals(2 * 1024 * 1024, result.size)
    }

    @Test
    fun `apply with zero target size keeps original size`() {
        val source = ByteArray(MIN_ROM_SIZE)
        val bps = buildIdentityBps(source)

        val result = BpsPatcher.apply(source, bps, emptyList(), 0)

        assertEquals(MIN_ROM_SIZE, result.size)
    }

    // ── Checksum written after patching ─────────────────────────────────────

    @Test
    fun `apply writes valid SNES checksum`() {
        val source = ByteArray(0x8000) // 32 KB minimum for checksum area
        val bps = buildIdentityBps(source)

        val result = BpsPatcher.apply(source, bps, emptyList(), 0)

        val complement = readU16LE(result, 0x7FDC)
        val checksum = readU16LE(result, 0x7FDE)
        assertEquals(0xFFFF, complement xor checksum)
    }

    // ── Invalid BPS magic ───────────────────────────────────────────────────

    @Test(expected = IOException::class)
    fun `apply throws on invalid BPS magic`() {
        BpsPatcher.apply(ByteArray(16), byteArrayOf(0, 0, 0, 0), emptyList(), 0)
    }

    // ── BPS with TARGET_READ action ─────────────────────────────────────────

    @Test
    fun `apply handles TARGET_READ action`() {
        val source = ByteArray(MIN_ROM_SIZE)
        // Build a BPS patch that writes specific bytes via TARGET_READ into a MIN_ROM_SIZE target
        val targetData = ByteArray(MIN_ROM_SIZE)
        targetData[0] = 0x11; targetData[1] = 0x22; targetData[2] = 0x33; targetData[3] = 0x44
        val bps = buildTargetReadBps(sourceSize = MIN_ROM_SIZE, targetData = targetData)

        val result = BpsPatcher.apply(source, bps, emptyList(), 0)

        assertEquals(0x11.toByte(), result[0])
        assertEquals(0x22.toByte(), result[1])
        assertEquals(0x33.toByte(), result[2])
        assertEquals(0x44.toByte(), result[3])
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Builds a minimal BPS patch that uses SOURCE_READ to copy the source unchanged.
     * Format: "BPS1" + VLI(sourceSize) + VLI(targetSize) + VLI(0 meta)
     *       + action(SOURCE_READ, length=sourceSize)
     *       + CRC32(source) + CRC32(target) + CRC32(patch)
     */
    private fun buildIdentityBps(source: ByteArray): ByteArray {
        val buf = mutableListOf<Byte>()
        // Magic
        buf.addAll("BPS1".toByteArray().toList())
        // Source size
        buf.addAll(encodeVli(source.size.toLong()))
        // Target size (same as source)
        buf.addAll(encodeVli(source.size.toLong()))
        // Metadata length
        buf.addAll(encodeVli(0))
        // Single SOURCE_READ action: length = source.size
        // action = ((length - 1) << 2) | type, type=0 for SOURCE_READ
        buf.addAll(encodeVli(((source.size - 1).toLong() shl 2) or 0))
        // Footer: source CRC32, target CRC32 (same as source), patch CRC32
        val srcCrc = crc32(source)
        buf.addAll(uint32LeBytes(srcCrc))
        buf.addAll(uint32LeBytes(srcCrc)) // target = source for identity
        // Patch CRC32 (over everything so far)
        val patchSoFar = buf.toByteArray()
        buf.addAll(uint32LeBytes(crc32(patchSoFar)))
        return buf.toByteArray()
    }

    /**
     * Builds a BPS patch that writes [targetData] via a single TARGET_READ action.
     */
    private fun buildTargetReadBps(sourceSize: Int, targetData: ByteArray): ByteArray {
        val buf = mutableListOf<Byte>()
        buf.addAll("BPS1".toByteArray().toList())
        buf.addAll(encodeVli(sourceSize.toLong()))
        buf.addAll(encodeVli(targetData.size.toLong()))
        buf.addAll(encodeVli(0)) // no metadata
        // TARGET_READ action: type=1, length=targetData.size
        buf.addAll(encodeVli(((targetData.size - 1).toLong() shl 2) or 1))
        buf.addAll(targetData.toList())
        // Footer
        val srcCrc = crc32(ByteArray(sourceSize))
        buf.addAll(uint32LeBytes(srcCrc))
        buf.addAll(uint32LeBytes(crc32(targetData)))
        val patchSoFar = buf.toByteArray()
        buf.addAll(uint32LeBytes(crc32(patchSoFar)))
        return buf.toByteArray()
    }

    private fun encodeVli(value: Long): List<Byte> {
        val bytes = mutableListOf<Byte>()
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = (v shr 7) - 1
            if (v < 0) {
                bytes.add((b or 0x80).toByte())
                break
            }
            bytes.add(b.toByte())
        }
        return bytes
    }

    private fun crc32(data: ByteArray): Long {
        val crc = CRC32()
        crc.update(data)
        return crc.value
    }

    private fun uint32LeBytes(value: Long): List<Byte> = listOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun readU16LE(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}
