// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Metadata and optional embedded cover artwork extracted for an audio track in Quick Look / Details. */
data class AudioTrackMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artworkBytes: ByteArray? = null,
) {
    val subtitle: String?
        get() = listOfNotNull(
            artist?.trim()?.takeIf { it.isNotEmpty() },
            album?.trim()?.takeIf { it.isNotEmpty() },
        ).takeIf { it.isNotEmpty() }?.joinToString("  ·  ")

    val hasAnyMetadata: Boolean
        get() = !title.isNullOrBlank() || !artist.isNullOrBlank() || !album.isNullOrBlank() || artworkBytes != null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioTrackMetadata) return false
        return title == other.title &&
            artist == other.artist &&
            album == other.album &&
            artworkBytes.contentEquals(other.artworkBytes)
    }

    override fun hashCode(): Int {
        var result = title?.hashCode() ?: 0
        result = 31 * result + (artist?.hashCode() ?: 0)
        result = 31 * result + (album?.hashCode() ?: 0)
        result = 31 * result + (artworkBytes?.contentHashCode() ?: 0)
        return result
    }
}

/** Thrown inside [ImageDecoder.OnHeaderDecodedListener] before frame allocation when canvas dimensions are unsafe. */
internal class AnimatedCanvasTooLargeException(message: String) : IOException(message)

/** Outcome of decoding an animated GIF or WebP via [MediaPreviewLoader.decodeAnimatedResult]. */
sealed interface AnimatedDrawableResult {
    data class Decoded(val drawable: Drawable) : AnimatedDrawableResult
    data object RejectedBounds : AnimatedDrawableResult
    data object Unavailable : AnimatedDrawableResult
}

/** Pure, JVM-testable helpers for media preview playback math, formatting, and animated-image detection. */
object MediaPreviewSupport {
    const val SEEK_STEP_MS: Long = 10_000L
    const val SLIDER_KEY_STEP_MS: Long = 5_000L
    const val MAX_ARTWORK_BYTES: Int = 16 * 1024 * 1024
    const val MAX_ARTWORK_EDGE_PX: Int = 720
    const val MAX_ARTWORK_SOURCE_EDGE_PX: Int = 16_384
    const val MAX_ARTWORK_SOURCE_PIXELS: Long = 67_108_864L
    const val MAX_ANIMATED_IMAGE_EDGE_PX: Int = 2048
    const val MAX_ANIMATED_SOURCE_EDGE_PX: Int = 8192
    const val MAX_ANIMATED_SOURCE_PIXELS: Long = 16_777_216L
    const val MAX_ANIMATED_FILE_BYTES: Long = 64L * 1024L * 1024L

    val PLAYBACK_SPEEDS: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    private val ANIMATED_IMAGE_EXTS = setOf("gif", "webp")
    private const val MAX_WEBP_CHUNKS_TO_SCAN = 32

    /** True for file extensions whose files may contain multi-frame animations supported by [ImageDecoder]. */
    fun isAnimatedImageExtension(extension: String): Boolean =
        extension.trim().lowercase(Locale.ROOT) in ANIMATED_IMAGE_EXTS

    /** True when [bytes] starts with a standard GIF87a or GIF89a header. */
    fun isGifHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 6) return false
        return bytes[0] == 'G'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == '8'.code.toByte() &&
            (bytes[4] == '7'.code.toByte() || bytes[4] == '9'.code.toByte()) &&
            bytes[5] == 'a'.code.toByte()
    }

    /**
     * True when [bytes] is a RIFF/WEBP container advertising animation via a `VP8X` animation flag
     * (`0x02`) or an `ANIM` top-level chunk. Safely bounds-checked against truncated or adversarial input.
     */
    fun isAnimatedWebpHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        if (!matchesAscii(bytes, 0, "RIFF") || !matchesAscii(bytes, 8, "WEBP")) return false

        var offset = 12
        var chunksVisited = 0
        while (offset + 8 <= bytes.size && chunksVisited < MAX_WEBP_CHUNKS_TO_SCAN) {
            chunksVisited++
            val chunkSize = readUint32Le(bytes, offset + 4)
            if (matchesAscii(bytes, offset, "VP8X")) {
                if (chunkSize < 10L || offset + 9 > bytes.size) return false
                return (bytes[offset + 8].toInt() and 0x02) != 0
            }
            if (matchesAscii(bytes, offset, "ANIM")) {
                return true
            }
            val paddedSize = chunkSize + (chunkSize and 1L)
            val nextOffset = offset.toLong() + 8L + paddedSize
            if (paddedSize < 0L || nextOffset <= offset.toLong() || nextOffset > bytes.size.toLong()) {
                break
            }
            offset = nextOffset.toInt()
        }
        return false
    }

    /** True when [bytes] begins with a GIF header or an animated WebP header. */
    fun isAnimatedImageHeader(bytes: ByteArray): Boolean =
        isGifHeader(bytes) || isAnimatedWebpHeader(bytes)

    /**
     * Parses unscaled canvas dimensions from a GIF Logical Screen Descriptor or WebP `VP8X` chunk
     * header prefix when available, or returns `null` if the prefix does not carry canvas dimensions.
     */
    fun parseAnimatedHeaderDimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (isGifHeader(bytes)) {
            if (bytes.size < 10) return null
            val width = readUint16Le(bytes, 6)
            val height = readUint16Le(bytes, 8)
            return width to height
        }
        if (bytes.size >= 12 && matchesAscii(bytes, 0, "RIFF") && matchesAscii(bytes, 8, "WEBP")) {
            var offset = 12
            var chunksVisited = 0
            while (offset + 8 <= bytes.size && chunksVisited < MAX_WEBP_CHUNKS_TO_SCAN) {
                chunksVisited++
                val chunkSize = readUint32Le(bytes, offset + 4)
                if (matchesAscii(bytes, offset, "VP8X")) {
                    if (chunkSize < 10L || offset + 18 > bytes.size) return null
                    val width = readUint24Le(bytes, offset + 12) + 1
                    val height = readUint24Le(bytes, offset + 15) + 1
                    return width to height
                }
                val paddedSize = chunkSize + (chunkSize and 1L)
                val nextOffset = offset.toLong() + 8L + paddedSize
                if (paddedSize < 0L || nextOffset <= offset.toLong() || nextOffset > bytes.size.toLong()) {
                    break
                }
                offset = nextOffset.toInt()
            }
        }
        return null
    }

    /**
     * True when unscaled animated GIF/WebP canvas dimensions are safe to pass to Skia's native
     * multi-frame codecs (which allocate full-canvas frame buffers before or without downscaling).
     */
    fun isValidAnimatedCanvasSize(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        if (width > MAX_ANIMATED_SOURCE_EDGE_PX || height > MAX_ANIMATED_SOURCE_EDGE_PX) return false
        return width.toLong() * height.toLong() <= MAX_ANIMATED_SOURCE_PIXELS
    }

    /** True when [byteLength] is a positive animated-image byte size within [MAX_ANIMATED_FILE_BYTES]. */
    fun isAcceptableAnimatedFileSize(byteLength: Long): Boolean =
        byteLength in 1L..MAX_ANIMATED_FILE_BYTES

    /** True when embedded cover artwork source dimensions are within safe bounds for downsampled decode. */
    fun isValidArtworkSourceSize(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        if (width > MAX_ARTWORK_SOURCE_EDGE_PX || height > MAX_ARTWORK_SOURCE_EDGE_PX) return false
        return width.toLong() * height.toLong() <= MAX_ARTWORK_SOURCE_PIXELS
    }

    /**
     * Computes downscaled target dimensions that fit within `[maxEdge] x [maxEdge]` while preserving
     * aspect ratio. Never upscales images that are already within bounds.
     */
    fun computeScaledDimensions(srcWidth: Int, srcHeight: Int, maxEdge: Int): Pair<Int, Int> {
        if (srcWidth <= 0 || srcHeight <= 0) return 1 to 1
        if (maxEdge <= 0) return srcWidth to srcHeight
        val longest = maxOf(srcWidth, srcHeight)
        if (longest <= maxEdge) return srcWidth to srcHeight
        val scale = maxEdge.toDouble() / longest.toDouble()
        val dstW = (srcWidth * scale).roundToLong().toInt().coerceIn(1, maxEdge)
        val dstH = (srcHeight * scale).roundToLong().toInt().coerceIn(1, maxEdge)
        return dstW to dstH
    }

    /**
     * Computes the power-of-2 [BitmapFactory.Options.inSampleSize] ensuring neither decoded edge
     * exceeds [maxEdge] (including extreme panoramic aspect ratios).
     */
    fun computeInSampleSize(srcWidth: Int, srcHeight: Int, maxEdge: Int): Int {
        if (srcWidth <= 0 || srcHeight <= 0 || maxEdge <= 0) return 1
        var sample = 1
        while (((srcWidth / sample) > maxEdge || (srcHeight / sample) > maxEdge) && sample < (1 shl 20)) {
            sample *= 2
        }
        return sample
    }

    /** Formats a playback timestamp in milliseconds as `M:SS` or `H:MM:SS`. */
    fun formatPlaybackTime(timeMs: Long): String {
        if (timeMs <= 0L) return "0:00"
        val totalSec = timeMs / 1000L
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
        } else {
            "%d:%02d".format(Locale.US, minutes, seconds)
        }
    }

    /** Computes an overflow-safe clamped seek target in milliseconds. */
    fun clampSeekPosition(currentMs: Long, deltaMs: Long, durationMs: Long): Long {
        val base = currentMs.coerceAtLeast(0L)
        val target = when {
            deltaMs > 0L && base > Long.MAX_VALUE - deltaMs -> Long.MAX_VALUE
            deltaMs < 0L && base < Long.MIN_VALUE - deltaMs -> 0L
            else -> (base + deltaMs).coerceAtLeast(0L)
        }
        return if (durationMs > 0L) target.coerceIn(0L, durationMs) else target
    }

    /** Converts `[positionMs]` within `[durationMs]` to a normalized `[0f, 1f]` slider fraction. */
    fun positionToFraction(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L || positionMs <= 0L) return 0f
        if (positionMs >= durationMs) return 1f
        return (positionMs.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    /** Converts a normalized `[0f, 1f]` slider fraction back to milliseconds safely. */
    fun fractionToPositionMs(fraction: Float, durationMs: Long): Long {
        if (durationMs <= 0L || !fraction.isFinite()) return 0L
        val clamped = fraction.coerceIn(0f, 1f).toDouble()
        return (clamped * durationMs.toDouble()).roundToLong().coerceIn(0L, durationMs)
    }

    /** Cycles to the next standard playback speed in [PLAYBACK_SPEEDS], wrapping to `0.5x`. */
    fun nextPlaybackSpeed(current: Float): Float {
        if (!current.isFinite()) return 1f
        val idx = PLAYBACK_SPEEDS.indexOfFirst { abs(it - current) < 0.01f }
        return if (idx >= 0) PLAYBACK_SPEEDS[(idx + 1) % PLAYBACK_SPEEDS.size] else 1f
    }

    /** Formats a playback speed multiplier concisely (e.g., `1×`, `1.25×`, `0.5×`). */
    fun formatPlaybackSpeed(speed: Float): String {
        val safe = if (speed.isFinite() && speed > 0f) speed else 1f
        val formatted = "%.2f".format(Locale.US, safe).trimEnd('0').trimEnd('.')
        return "${formatted}×"
    }

    /** Merges primary (e.g. Media3) metadata with fallback (e.g. MediaMetadataRetriever) metadata. */
    fun mergeAudioMetadata(primary: AudioTrackMetadata, fallback: AudioTrackMetadata): AudioTrackMetadata =
        AudioTrackMetadata(
            title = primary.title?.trim()?.takeIf { it.isNotEmpty() }
                ?: fallback.title?.trim()?.takeIf { it.isNotEmpty() },
            artist = primary.artist?.trim()?.takeIf { it.isNotEmpty() }
                ?: fallback.artist?.trim()?.takeIf { it.isNotEmpty() },
            album = primary.album?.trim()?.takeIf { it.isNotEmpty() }
                ?: fallback.album?.trim()?.takeIf { it.isNotEmpty() },
            artworkBytes = primary.artworkBytes?.takeIf { it.isNotEmpty() && it.size <= MAX_ARTWORK_BYTES }
                ?: fallback.artworkBytes?.takeIf { it.isNotEmpty() && it.size <= MAX_ARTWORK_BYTES },
        )

    private fun matchesAscii(bytes: ByteArray, offset: Int, fourCc: String): Boolean {
        if (offset < 0 || offset + fourCc.length > bytes.size) return false
        for (i in fourCc.indices) {
            if (bytes[offset + i] != fourCc[i].code.toByte()) return false
        }
        return true
    }

    private fun readUint16Le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readUint24Le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16)

    private fun readUint32Le(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)
}

/** Off-main-thread Android SDK media preview loaders (zero third-party dependencies). */
object MediaPreviewLoader {
    private const val HEADER_PREFIX_BYTES = 512

    fun readHeaderPrefix(file: File, maxBytes: Int = HEADER_PREFIX_BYTES): ByteArray = runCatching {
        if (!FileInfo.isRegularFileNoFollow(file.path)) return@runCatching ByteArray(0)
        file.inputStream().use { inp ->
            val buf = ByteArray(maxBytes.coerceAtLeast(0))
            var total = 0
            while (total < buf.size) {
                val n = inp.read(buf, total, buf.size - total)
                if (n < 0) break
                total += n
            }
            if (total == buf.size) buf else buf.copyOf(total)
        }
    }.getOrDefault(ByteArray(0))

    fun readHeaderPrefix(context: Context, uri: Uri, maxBytes: Int = HEADER_PREFIX_BYTES): ByteArray = runCatching {
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return@runCatching ByteArray(0)
        when (scheme) {
            "file" -> {
                val path = uri.path ?: return@runCatching ByteArray(0)
                readHeaderPrefix(File(path), maxBytes)
            }
            "content" -> {
                context.contentResolver.openInputStream(uri)?.use { inp ->
                    val buf = ByteArray(maxBytes.coerceAtLeast(0))
                    var total = 0
                    while (total < buf.size) {
                        val n = inp.read(buf, total, buf.size - total)
                        if (n < 0) break
                        total += n
                    }
                    if (total == buf.size) buf else buf.copyOf(total)
                } ?: ByteArray(0)
            }
            else -> ByteArray(0)
        }
    }.getOrDefault(ByteArray(0))

    /**
     * Verifies off-main-thread that [uri] resolves to a regular, bounded-length resource within
     * [MediaPreviewSupport.MAX_ANIMATED_FILE_BYTES] before passing it to [ImageDecoder].
     */
    fun isWithinAnimatedSizeLimit(
        context: Context,
        uri: Uri,
        declaredEntrySize: Long = -1L,
    ): Boolean {
        if (declaredEntrySize > MediaPreviewSupport.MAX_ANIMATED_FILE_BYTES) return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        return runCatching {
            when (scheme) {
                "file" -> {
                    val path = uri.path ?: return@runCatching false
                    val actual = FileInfo.regularFileSizeNoFollow(path)
                    MediaPreviewSupport.isAcceptableAnimatedFileSize(actual)
                }
                "content" -> {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                        val declared = afd.length
                        if (declared != AssetFileDescriptor.UNKNOWN_LENGTH) {
                            MediaPreviewSupport.isAcceptableAnimatedFileSize(declared)
                        } else {
                            val st = runCatching { Os.fstat(afd.fileDescriptor) }.getOrNull()
                            if (st != null && st.st_mode != 0) {
                                OsConstants.S_ISREG(st.st_mode) &&
                                    MediaPreviewSupport.isAcceptableAnimatedFileSize(st.st_size)
                            } else {
                                false
                            }
                        }
                    } ?: false
                }
                else -> false
            }
        }.getOrDefault(false)
    }

    /**
     * Extracts embedded album art and basic ID3/MP4/FLAC/Vorbis tags using the official Android SDK
     * [MediaMetadataRetriever] via explicit read-only file descriptors (`file` and `content` schemes
     * only — never passing raw URI strings to Stagefright). Must run on a background IO dispatcher.
     */
    fun extractFallbackAudioMetadata(context: Context, uri: Uri): AudioTrackMetadata {
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return AudioTrackMetadata()
        if (scheme != "file" && scheme != "content") return AudioTrackMetadata()

        val retriever = MediaMetadataRetriever()
        return try {
            when (scheme) {
                "file" -> {
                    val path = uri.path ?: return AudioTrackMetadata()
                    if (!FileInfo.isRegularFileNoFollow(path)) return AudioTrackMetadata()
                    ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                        retriever.setDataSource(pfd.fileDescriptor)
                        readRetrieverTagsAndArtwork(retriever)
                    }
                }
                "content" -> {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                        val declaredLen = afd.declaredLength
                        if (declaredLen < 0L) {
                            retriever.setDataSource(afd.fileDescriptor)
                        } else {
                            retriever.setDataSource(afd.fileDescriptor, afd.startOffset, declaredLen)
                        }
                        readRetrieverTagsAndArtwork(retriever)
                    } ?: AudioTrackMetadata()
                }
                else -> AudioTrackMetadata()
            }
        } catch (_: Throwable) {
            AudioTrackMetadata()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun readRetrieverTagsAndArtwork(retriever: MediaMetadataRetriever): AudioTrackMetadata {
        val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            ?.trim()?.takeIf { it.isNotEmpty() }
        val artist = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))
            ?.trim()?.takeIf { it.isNotEmpty() }
        val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            ?.trim()?.takeIf { it.isNotEmpty() }
        val artwork = retriever.embeddedPicture
            ?.takeIf { it.isNotEmpty() && it.size <= MediaPreviewSupport.MAX_ARTWORK_BYTES }
        return AudioTrackMetadata(title = title, artist = artist, album = album, artworkBytes = artwork)
    }

    /** Safely decodes embedded artwork bytes into a downsampled [Bitmap] bounded to [maxEdgePx]. */
    fun decodeArtworkBitmap(bytes: ByteArray?, maxEdgePx: Int = MediaPreviewSupport.MAX_ARTWORK_EDGE_PX): Bitmap? {
        if (bytes == null || bytes.isEmpty() || bytes.size > MediaPreviewSupport.MAX_ARTWORK_BYTES) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (!MediaPreviewSupport.isValidArtworkSourceSize(bounds.outWidth, bounds.outHeight)) {
                return@runCatching null
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = MediaPreviewSupport.computeInSampleSize(bounds.outWidth, bounds.outHeight, maxEdgePx)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }.getOrNull()
    }

    /**
     * Decodes a GIF or animated WebP using Android's native [ImageDecoder] (API 28+, minSdk 30),
     * rejecting oversized unscaled canvas dimensions *before* Skia allocates full-canvas frame buffers.
     */
    fun decodeAnimatedResult(
        source: ImageDecoder.Source,
        maxEdgePx: Int = MediaPreviewSupport.MAX_ANIMATED_IMAGE_EDGE_PX,
    ): AnimatedDrawableResult = try {
        val drawable = ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
            val srcW = info.size.width
            val srcH = info.size.height
            if (!MediaPreviewSupport.isValidAnimatedCanvasSize(srcW, srcH)) {
                throw AnimatedCanvasTooLargeException(
                    "Animated image canvas exceeds safety bounds: ${srcW}x${srcH}",
                )
            }
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
            val (w, h) = MediaPreviewSupport.computeScaledDimensions(
                srcWidth = srcW,
                srcHeight = srcH,
                maxEdge = maxEdgePx,
            )
            if (w != srcW || h != srcH) {
                decoder.setTargetSize(w, h)
            }
        }
        AnimatedDrawableResult.Decoded(drawable)
    } catch (_: AnimatedCanvasTooLargeException) {
        AnimatedDrawableResult.RejectedBounds
    } catch (_: OutOfMemoryError) {
        AnimatedDrawableResult.RejectedBounds
    } catch (_: Throwable) {
        AnimatedDrawableResult.Unavailable
    }

    /** Convenience wrapper returning the decoded [Drawable] when [decodeAnimatedResult] succeeds. */
    fun decodeAnimatedDrawable(
        source: ImageDecoder.Source,
        maxEdgePx: Int = MediaPreviewSupport.MAX_ANIMATED_IMAGE_EDGE_PX,
    ): Drawable? = (decodeAnimatedResult(source, maxEdgePx) as? AnimatedDrawableResult.Decoded)?.drawable
}
