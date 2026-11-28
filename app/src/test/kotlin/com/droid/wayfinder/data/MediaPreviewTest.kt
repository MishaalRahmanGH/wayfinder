// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class MediaPreviewTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun animatedImageExtensionMatchesGifAndWebpCaseInsensitively() {
        assertTrue(MediaPreviewSupport.isAnimatedImageExtension("gif"))
        assertTrue(MediaPreviewSupport.isAnimatedImageExtension("GIF"))
        assertTrue(MediaPreviewSupport.isAnimatedImageExtension("webp"))
        assertTrue(MediaPreviewSupport.isAnimatedImageExtension(" WebP "))
        assertFalse(MediaPreviewSupport.isAnimatedImageExtension("png"))
        assertFalse(MediaPreviewSupport.isAnimatedImageExtension("jpg"))
        assertFalse(MediaPreviewSupport.isAnimatedImageExtension("mp4"))
        assertFalse(MediaPreviewSupport.isAnimatedImageExtension(""))
    }

    @Test
    fun gifHeaderSniffingDetectsGif87aAndGif89aOnly() {
        assertTrue(MediaPreviewSupport.isGifHeader("GIF87a\u0001\u0000".toByteArray(Charsets.US_ASCII)))
        assertTrue(MediaPreviewSupport.isGifHeader("GIF89a".toByteArray(Charsets.US_ASCII)))
        assertFalse(MediaPreviewSupport.isGifHeader("GIF88a".toByteArray(Charsets.US_ASCII)))
        assertFalse(MediaPreviewSupport.isGifHeader("GIF89".toByteArray(Charsets.US_ASCII)))
        assertFalse(MediaPreviewSupport.isGifHeader(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
        assertFalse(MediaPreviewSupport.isGifHeader(ByteArray(0)))
    }

    private fun buildWebp(vararg chunks: Pair<String, ByteArray>): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("WEBP".toByteArray(Charsets.US_ASCII))
        for ((fourCc, payload) in chunks) {
            body.write(fourCc.toByteArray(Charsets.US_ASCII))
            val lenBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size).array()
            body.write(lenBuf)
            body.write(payload)
            if ((payload.size and 1) != 0) body.write(0) // RIFF even-byte padding
        }
        val payloadBytes = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payloadBytes.size).array())
        out.write(payloadBytes)
        return out.toByteArray()
    }

    private fun buildGifHeader(width: Int, height: Int, version: String = "GIF89a"): ByteArray =
        ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(version.toByteArray(Charsets.US_ASCII))
            putShort(width.toShort())
            putShort(height.toShort())
            put(0)
            put(0)
            put(0)
        }.array()

    private fun buildVp8xPayload(animated: Boolean, width: Int, height: Int): ByteArray {
        val wMinus1 = (width - 1).coerceAtLeast(0)
        val hMinus1 = (height - 1).coerceAtLeast(0)
        return byteArrayOf(
            if (animated) 0x02 else 0x00,
            0, 0, 0,
            (wMinus1 and 0xFF).toByte(),
            ((wMinus1 shr 8) and 0xFF).toByte(),
            ((wMinus1 shr 16) and 0xFF).toByte(),
            (hMinus1 and 0xFF).toByte(),
            ((hMinus1 shr 8) and 0xFF).toByte(),
            ((hMinus1 shr 16) and 0xFF).toByte(),
        )
    }

    @Test
    fun animatedWebpHeaderDetectsVp8xFlagAndAnimChunkSafely() {
        val animatedVp8x = buildWebp("VP8X" to buildVp8xPayload(animated = true, width = 640, height = 480))
        assertTrue(MediaPreviewSupport.isAnimatedWebpHeader(animatedVp8x))
        assertTrue(MediaPreviewSupport.isAnimatedImageHeader(animatedVp8x))

        val staticVp8x = buildWebp("VP8X" to byteArrayOf(0x10, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        assertFalse(MediaPreviewSupport.isAnimatedWebpHeader(staticVp8x))

        val staticVp8 = buildWebp("VP8 " to ByteArray(16))
        assertFalse(MediaPreviewSupport.isAnimatedWebpHeader(staticVp8))

        // Odd-length leading chunk followed by ANIM chunk tests RIFF padding alignment.
        val animAfterOddChunk = buildWebp("ICCP" to byteArrayOf(1, 2, 3), "ANIM" to ByteArray(6))
        assertTrue(MediaPreviewSupport.isAnimatedWebpHeader(animAfterOddChunk))

        // Truncated VP8X chunk must return false without throwing.
        val truncatedVp8x = "RIFF\u0020\u0000\u0000\u0000WEBPVP8X\u000A\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
        assertFalse(MediaPreviewSupport.isAnimatedWebpHeader(truncatedVp8x))
    }

    @Test
    fun animatedCanvasAndFileSizeBoundsBlockDecompressionBombs() {
        // Safe dimensions within 8192 max edge and 16,777,216 max pixels
        assertTrue(MediaPreviewSupport.isValidAnimatedCanvasSize(640, 480))
        assertTrue(MediaPreviewSupport.isValidAnimatedCanvasSize(4096, 4096))
        assertTrue(MediaPreviewSupport.isValidAnimatedCanvasSize(8192, 2048))
        assertTrue(MediaPreviewSupport.isValidAnimatedCanvasSize(2048, 8192))

        // Decompression bombs and invalid dimensions rejected
        assertFalse(MediaPreviewSupport.isValidAnimatedCanvasSize(8193, 100))
        assertFalse(MediaPreviewSupport.isValidAnimatedCanvasSize(100, 8193))
        assertFalse(MediaPreviewSupport.isValidAnimatedCanvasSize(4097, 4096)) // > 16,777,216 pixels
        assertFalse(MediaPreviewSupport.isValidAnimatedCanvasSize(16_383, 16_383)) // Animated WebP 3.2 GB bomb
        assertFalse(MediaPreviewSupport.isValidAnimatedCanvasSize(32_768, 32_768)) // GIF89a 4 GB LSD bomb
        assertFalse(MediaPreviewSupport.isValidAnimatedCanvasSize(65_535, 65_535)) // GIF89a max 17 GB LSD bomb
        assertFalse(MediaPreviewSupport.isValidAnimatedCanvasSize(Int.MAX_VALUE, Int.MAX_VALUE))
        assertFalse(MediaPreviewSupport.isValidAnimatedCanvasSize(0, 512))
        assertFalse(MediaPreviewSupport.isValidAnimatedCanvasSize(512, -1))

        // File size bounds (<= 64 MB)
        assertTrue(MediaPreviewSupport.isAcceptableAnimatedFileSize(1L))
        assertTrue(MediaPreviewSupport.isAcceptableAnimatedFileSize(MediaPreviewSupport.MAX_ANIMATED_FILE_BYTES))
        assertFalse(MediaPreviewSupport.isAcceptableAnimatedFileSize(0L))
        assertFalse(MediaPreviewSupport.isAcceptableAnimatedFileSize(-1L))
        assertFalse(MediaPreviewSupport.isAcceptableAnimatedFileSize(MediaPreviewSupport.MAX_ANIMATED_FILE_BYTES + 1L))
        assertFalse(MediaPreviewSupport.isAcceptableAnimatedFileSize(Long.MAX_VALUE))

        // Artwork source dimension cap blocks 20,000 x 20,000 solid-PNG bombs
        assertTrue(MediaPreviewSupport.isValidArtworkSourceSize(1024, 1024))
        assertTrue(MediaPreviewSupport.isValidArtworkSourceSize(16_384, 600))
        assertFalse(MediaPreviewSupport.isValidArtworkSourceSize(20_000, 20_000))
        assertFalse(MediaPreviewSupport.isValidArtworkSourceSize(16_385, 64))
        assertFalse(MediaPreviewSupport.isValidArtworkSourceSize(9_000, 9_000))

        // Header dimension parser accurately extracts GIF LSD and WebP VP8X dimensions
        assertEquals(640 to 480, MediaPreviewSupport.parseAnimatedHeaderDimensions(buildGifHeader(640, 480)))
        val gifBomb = buildGifHeader(32_768, 32_768)
        assertEquals(32_768 to 32_768, MediaPreviewSupport.parseAnimatedHeaderDimensions(gifBomb))
        val (gifW, gifH) = MediaPreviewSupport.parseAnimatedHeaderDimensions(gifBomb)!!
        assertFalse(MediaPreviewSupport.isValidAnimatedCanvasSize(gifW, gifH))

        val webpBomb = buildWebp("VP8X" to buildVp8xPayload(animated = true, width = 16_383, height = 16_383))
        assertEquals(16_383 to 16_383, MediaPreviewSupport.parseAnimatedHeaderDimensions(webpBomb))
        val (webpW, webpH) = MediaPreviewSupport.parseAnimatedHeaderDimensions(webpBomb)!!
        assertFalse(MediaPreviewSupport.isValidAnimatedCanvasSize(webpW, webpH))
    }

    @Test
    fun animatedHeaderParsersResistFuzzedAndAdversarialInputs() {
        val rng = Random(42)
        // Adversarial RIFF/WEBP headers with max uint32 chunk sizes (0xFFFFFFFF) and zero-length loops.
        val overflowChunk = ByteArray(32).apply {
            "RIFF".toByteArray().copyInto(this, 0)
            "WEBP".toByteArray().copyInto(this, 8)
            "JUNK".toByteArray().copyInto(this, 12)
            this[16] = 0xFF.toByte()
            this[17] = 0xFF.toByte()
            this[18] = 0xFF.toByte()
            this[19] = 0xFF.toByte()
        }
        assertFalse(MediaPreviewSupport.isAnimatedWebpHeader(overflowChunk))
        assertNull(MediaPreviewSupport.parseAnimatedHeaderDimensions(overflowChunk))

        val zeroLoopChunks = ByteArray(12 + 40 * 8).apply {
            "RIFF".toByteArray().copyInto(this, 0)
            "WEBP".toByteArray().copyInto(this, 8)
            for (i in 0 until 40) {
                "JUNK".toByteArray().copyInto(this, 12 + i * 8)
            }
        }
        assertFalse(MediaPreviewSupport.isAnimatedWebpHeader(zeroLoopChunks))
        assertNull(MediaPreviewSupport.parseAnimatedHeaderDimensions(zeroLoopChunks))

        repeat(600) {
            val len = rng.nextInt(0, 128)
            val data = ByteArray(len)
            rng.nextBytes(data)
            if (len >= 12 && it % 3 == 0) {
                "RIFF".toByteArray().copyInto(data, 0)
                "WEBP".toByteArray().copyInto(data, 8)
            } else if (len >= 6 && it % 5 == 0) {
                "GIF89a".toByteArray().copyInto(data, 0)
            }
            MediaPreviewSupport.isAnimatedImageHeader(data)
            MediaPreviewSupport.parseAnimatedHeaderDimensions(data)?.let { (w, h) ->
                MediaPreviewSupport.isValidAnimatedCanvasSize(w, h)
            }
        }
    }

    @Test
    fun dimensionScalingAndSampleSizeAvoidUpscalingAndDownsamplePanoramicImages() {
        assertEquals(800 to 600, MediaPreviewSupport.computeScaledDimensions(800, 600, 2048))
        assertEquals(2048 to 1024, MediaPreviewSupport.computeScaledDimensions(4096, 2048, 2048))
        assertEquals(1024 to 2048, MediaPreviewSupport.computeScaledDimensions(2048, 4096, 2048))
        assertEquals(2048 to 1, MediaPreviewSupport.computeScaledDimensions(10_000, 1, 2048))
        assertEquals(1 to 1, MediaPreviewSupport.computeScaledDimensions(0, 100, 2048))
        assertEquals(1 to 1, MediaPreviewSupport.computeScaledDimensions(-5, -5, 2048))
        assertEquals(400 to 300, MediaPreviewSupport.computeScaledDimensions(400, 300, 0))

        assertEquals(1, MediaPreviewSupport.computeInSampleSize(500, 500, 720))
        assertEquals(1, MediaPreviewSupport.computeInSampleSize(720, 720, 720))
        assertEquals(2, MediaPreviewSupport.computeInSampleSize(1000, 1000, 720))
        assertEquals(2, MediaPreviewSupport.computeInSampleSize(1440, 1440, 720))
        assertEquals(4, MediaPreviewSupport.computeInSampleSize(2880, 2880, 720))
        assertEquals(8, MediaPreviewSupport.computeInSampleSize(5760, 5760, 720))
        // Panoramic cover art (32,000 x 600 = 77 MB raw) must be downsampled on its wide axis!
        assertEquals(64, MediaPreviewSupport.computeInSampleSize(32_000, 600, 720))
        assertEquals(64, MediaPreviewSupport.computeInSampleSize(600, 32_000, 720))
        assertEquals(1, MediaPreviewSupport.computeInSampleSize(0, 0, 720))
    }

    @Test
    fun playbackTimeAndSeekMathHandleBoundariesAndOverflow() {
        assertEquals("0:00", MediaPreviewSupport.formatPlaybackTime(-5_000L))
        assertEquals("0:00", MediaPreviewSupport.formatPlaybackTime(0L))
        assertEquals("0:00", MediaPreviewSupport.formatPlaybackTime(999L))
        assertEquals("0:05", MediaPreviewSupport.formatPlaybackTime(5_000L))
        assertEquals("1:05", MediaPreviewSupport.formatPlaybackTime(65_000L))
        assertEquals("1:01:01", MediaPreviewSupport.formatPlaybackTime(3_661_000L))
        assertEquals("10:00:00", MediaPreviewSupport.formatPlaybackTime(36_000_000L))

        assertEquals(15_000L, MediaPreviewSupport.clampSeekPosition(5_000L, 10_000L, 60_000L))
        assertEquals(0L, MediaPreviewSupport.clampSeekPosition(5_000L, -10_000L, 60_000L))
        assertEquals(60_000L, MediaPreviewSupport.clampSeekPosition(55_000L, 10_000L, 60_000L))
        assertEquals(60_000L, MediaPreviewSupport.clampSeekPosition(Long.MAX_VALUE - 5L, 10_000L, 60_000L))
        assertEquals(0L, MediaPreviewSupport.clampSeekPosition(100L, Long.MIN_VALUE, 60_000L))
        assertEquals(20_000L, MediaPreviewSupport.clampSeekPosition(10_000L, 10_000L, -1L))

        assertEquals(0f, MediaPreviewSupport.positionToFraction(10_000L, 0L), 0.0001f)
        assertEquals(0.5f, MediaPreviewSupport.positionToFraction(30_000L, 60_000L), 0.0001f)
        assertEquals(1f, MediaPreviewSupport.positionToFraction(90_000L, 60_000L), 0.0001f)

        assertEquals(30_000L, MediaPreviewSupport.fractionToPositionMs(0.5f, 60_000L))
        assertEquals(0L, MediaPreviewSupport.fractionToPositionMs(Float.NaN, 60_000L))
        assertEquals(0L, MediaPreviewSupport.fractionToPositionMs(Float.POSITIVE_INFINITY, 60_000L))
        assertEquals(60_000L, MediaPreviewSupport.fractionToPositionMs(2.0f, 60_000L))
        assertEquals(0L, MediaPreviewSupport.fractionToPositionMs(0.5f, -1L))
    }

    @Test
    fun playbackSpeedCycleAndFormatting() {
        var speed = 1f
        val visited = mutableListOf<Float>()
        repeat(MediaPreviewSupport.PLAYBACK_SPEEDS.size) {
            visited += speed
            speed = MediaPreviewSupport.nextPlaybackSpeed(speed)
        }
        assertEquals(listOf(1f, 1.25f, 1.5f, 2f, 0.5f, 0.75f), visited)
        assertEquals(1f, speed, 0.001f)
        assertEquals(1f, MediaPreviewSupport.nextPlaybackSpeed(Float.NaN), 0.001f)
        assertEquals("0.5×", MediaPreviewSupport.formatPlaybackSpeed(0.5f))
        assertEquals("0.75×", MediaPreviewSupport.formatPlaybackSpeed(0.75f))
        assertEquals("1×", MediaPreviewSupport.formatPlaybackSpeed(1f))
        assertEquals("1.25×", MediaPreviewSupport.formatPlaybackSpeed(1.25f))
        assertEquals("2×", MediaPreviewSupport.formatPlaybackSpeed(2f))
    }

    @Test
    fun audioMetadataMergeAndEqualityHandleArtworkBytes() {
        val artA = byteArrayOf(1, 2, 3, 4)
        val artB = byteArrayOf(1, 2, 3, 4)
        val artC = byteArrayOf(9, 8, 7)

        val meta1 = AudioTrackMetadata(title = "Song", artist = "Artist", album = "Album", artworkBytes = artA)
        val meta2 = AudioTrackMetadata(title = "Song", artist = "Artist", album = "Album", artworkBytes = artB)
        val meta3 = AudioTrackMetadata(title = "Song", artist = "Artist", album = "Album", artworkBytes = artC)

        assertEquals(meta1, meta2)
        assertEquals(meta1.hashCode(), meta2.hashCode())
        assertNotEquals(meta1, meta3)
        assertEquals("Artist  ·  Album", meta1.subtitle)

        val merged = MediaPreviewSupport.mergeAudioMetadata(
            primary = AudioTrackMetadata(title = "  Primary Title ", artist = "  ", album = null, artworkBytes = null),
            fallback = AudioTrackMetadata(title = "Fallback Title", artist = "Fallback Artist", album = "Fallback Album", artworkBytes = artC),
        )
        assertEquals("Primary Title", merged.title)
        assertEquals("Fallback Artist", merged.artist)
        assertEquals("Fallback Album", merged.album)
        assertArrayEquals(artC, merged.artworkBytes)
    }

    @Test
    fun regularFileChecksRejectSymlinksFifosDirectoriesAndMissingPaths() {
        val regular = tmp.newFile("anim.gif").apply {
            writeBytes(buildGifHeader(32, 32))
        }
        assertTrue(FileInfo.isRegularFileNoFollow(regular.path))
        assertEquals(regular.length(), FileInfo.regularFileSizeNoFollow(regular.path))
        assertTrue(MediaPreviewSupport.isGifHeader(MediaPreviewLoader.readHeaderPrefix(regular)))

        val subDir = tmp.newFolder("sub")
        assertFalse(FileInfo.isRegularFileNoFollow(subDir.path))
        assertFalse(FileInfo.isRegularFileNoFollow(File(subDir, "missing.gif").path))

        val sym = File(subDir, "sym.gif")
        val symCreated = runCatching { Files.createSymbolicLink(sym.toPath(), regular.toPath()); true }.getOrDefault(false)
        if (symCreated) {
            assertFalse("Symlink must be rejected by isRegularFileNoFollow", FileInfo.isRegularFileNoFollow(sym.path))
            assertEquals(-1L, FileInfo.regularFileSizeNoFollow(sym.path))
            assertEquals(0, MediaPreviewLoader.readHeaderPrefix(sym).size)
        }

        val fifo = File(subDir, "pipe.mp3")
        val fifoCreated = runCatching {
            val proc = ProcessBuilder("mkfifo", fifo.path).start()
            proc.waitFor(2, TimeUnit.SECONDS) && proc.exitValue() == 0 && fifo.exists()
        }.getOrDefault(false)
        if (fifoCreated) {
            assertFalse("Named FIFO must be rejected without opening", FileInfo.isRegularFileNoFollow(fifo.path))
            assertEquals(-1L, FileInfo.regularFileSizeNoFollow(fifo.path))
            // Must return immediately without blocking on open(O_RDONLY)
            assertEquals(0, MediaPreviewLoader.readHeaderPrefix(fifo).size)
        }

        // Null / empty bytes return null safely
        assertNull(MediaPreviewLoader.decodeArtworkBitmap(null))
        assertNull(MediaPreviewLoader.decodeArtworkBitmap(ByteArray(0)))
    }
}
