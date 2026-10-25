package com.hextool.mtkflash

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Standard GPT (GUID Partition Table) parser -- this part is NOT
 * chipset-specific, the format is an official spec that's identical
 * across all devices (UEFI spec). Used to read the partition list
 * (boot, init_boot, vendor_boot, etc.) from an LBA1 + partition
 * entries dump.
 */
object GptParser {

    data class PartitionEntry(
        val name: String,
        val typeGuid: UUID,
        val startLba: Long,
        val endLba: Long
    ) {
        val sizeBytes: Long get() = (endLba - startLba + 1) * 512
    }

    data class GptHeader(
        val currentLba: Long,
        val partitionEntryLba: Long,
        val numPartitionEntries: Int,
        val sizeOfPartitionEntry: Int
    )

    /** buf = 512-byte LBA1 sector (GPT header, right after the protective MBR at LBA0). */
    fun parseHeader(buf: ByteArray): GptHeader? {
        if (buf.size < 512) return null
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)

        val signature = ByteArray(8)
        bb.get(signature)
        if (String(signature, Charsets.US_ASCII) != "EFI PART") {
            return null // not a valid GPT
        }

        bb.position(24) // skip revision(4) + headerSize(4) + crc32(4) + reserved(4) = offset 24
        val currentLba = bb.long          // offset 24
        bb.long                            // backupLba, skip
        bb.long                            // firstUsableLba, skip
        bb.long                            // lastUsableLba, skip
        bb.position(bb.position() + 16)    // disk guid, skip
        val partitionEntryLba = bb.long
        val numEntries = bb.int
        val entrySize = bb.int

        return GptHeader(currentLba, partitionEntryLba, numEntries, entrySize)
    }

    /** entriesBuf = the full partition-entry array block (numEntries * entrySize bytes). */
    fun parseEntries(header: GptHeader, entriesBuf: ByteArray): List<PartitionEntry> {
        val result = mutableListOf<PartitionEntry>()
        val bb = ByteBuffer.wrap(entriesBuf).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until header.numPartitionEntries) {
            val base = i * header.sizeOfPartitionEntry
            if (base + header.sizeOfPartitionEntry > entriesBuf.size) break
            bb.position(base)

            val typeGuidBytes = ByteArray(16)
            bb.get(typeGuidBytes)
            val typeGuid = guidFromBytes(typeGuidBytes)

            bb.position(base + 16 + 16) // skip the unique partition guid (16 bytes)
            val startLba = bb.long
            val endLba = bb.long
            bb.long // attributes, skip

            val nameBytes = ByteArray(72) // 36 UTF-16LE chars
            bb.get(nameBytes)
            val name = String(nameBytes, Charsets.UTF_16LE).trimEnd('\u0000')

            if (name.isNotBlank()) {
                result.add(PartitionEntry(name, typeGuid, startLba, endLba))
            }
        }
        return result
    }

    private fun guidFromBytes(b: ByteArray): UUID {
        // GPT GUIDs are stored mixed-endian (the first part is little-endian)
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val mostSigBits = (bb.int.toLong() shl 32) or
                ((bb.short.toLong() and 0xFFFF) shl 16) or
                (bb.short.toLong() and 0xFFFF)
        bb.order(ByteOrder.BIG_ENDIAN)
        val leastSigBits = bb.long
        return UUID(mostSigBits, leastSigBits)
    }

    fun findPartition(entries: List<PartitionEntry>, name: String): PartitionEntry? =
        entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
}
