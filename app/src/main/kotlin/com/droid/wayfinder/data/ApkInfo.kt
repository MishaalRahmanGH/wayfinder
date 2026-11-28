// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.content.res.Configuration
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

/** Verification state of an APK's signing certificate(s). */
enum class ApkSignatureStatus {
    Verified,
    LegacyV1JarOnly,
    UnsignedOrUnverified,
    Unknown,
}

/** Signing certificate fingerprint and optional X.509 subject parsed from an APK signature block. */
data class ApkCertificateInfo(
    val sha256Fingerprint: String,
    val subject: String? = null,
)

/** Result of inspecting an APK's ZIP footer, APK Signing Block, and native ABI directory entries. */
data class ApkZipInspection(
    val zipValid: Boolean,
    val abis: List<String> = emptyList(),
    val truncated: Boolean = false,
    val hasV2OrNewerSigningBlock: Boolean = false,
)

/** Non-empty package, SDK, ABI, and certificate metadata extracted from an Android `.apk` file. */
data class ApkInfo(
    val appName: String? = null,
    val packageName: String? = null,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    val supportedAbis: List<String> = emptyList(),
    val zipInspected: Boolean = false,
    val zipTruncated: Boolean = false,
    val certificates: List<ApkCertificateInfo> = emptyList(),
    val signatureStatus: ApkSignatureStatus = ApkSignatureStatus.Unknown,
) {
    val isEmpty: Boolean
        get() = appName == null &&
            packageName == null &&
            versionName == null &&
            versionCode == null &&
            minSdk == null &&
            targetSdk == null &&
            !zipInspected &&
            certificates.isEmpty()
}

/**
 * Inspects Android `.apk` files using only platform [PackageManager], [ResourcesProvider],
 * [RandomAccessFile] / [ZipFile], and JDK [MessageDigest] / [CertificateFactory] APIs — no
 * third-party libraries or native binaries.
 */
object ApkInspector {
    private const val INSPECT_TIMEOUT_MS = 10_000L
    internal const val MAX_ZIP_ENTRIES = 50_000
    internal const val MAX_CEN_BYTES = 16L * 1024L * 1024L
    internal const val LOCAL_DOC_STAGE_MAX_BYTES = 128L * 1024L * 1024L
    private const val MAX_ABI_LEN = 32
    private const val MAX_TEXT_LEN = 256
    private const val MAX_SIG_BLOCK_PAIRS = 64

    private const val EOCD_MIN_SIZE = 22
    private const val EOCD_MAX_COMMENT = 0xFFFF
    private const val ZIP64_LOCATOR_SIZE = 20
    private const val ZIP64_EOCD_MIN_SIZE = 56
    private const val CEN_HEADER_FIXED_SIZE = 46

    // Little-endian ZIP signatures
    private const val EOCD_SIG = 0x06054b50
    private const val ZIP64_LOCATOR_SIG = 0x07064b50
    private const val ZIP64_EOCD_SIG = 0x06064b50
    private const val CEN_SIG = 0x02014b50

    // Official Android APK Signing Block IDs for v2, v3, and v3.1 schemes
    private val APK_SIG_BLOCK_MAGIC = "APK Sig Block 42".toByteArray(StandardCharsets.US_ASCII)
    internal const val APK_SIG_SCHEME_V2_ID = 0x7109871a
    internal const val APK_SIG_SCHEME_V3_ID = -0x0fac9740 // 0xf05368c0
    internal const val APK_SIG_SCHEME_V31_ID = 0x1b93ad61

    private val ABI_ORDER = listOf(
        "arm64-v8a",
        "armeabi-v7a",
        "armeabi",
        "x86_64",
        "x86",
        "riscv64",
        "mips64",
        "mips",
    )

    private val ABI_NAME_REGEX = Regex("^[A-Za-z0-9._-]+$")

    /** True when [entry] is a regular `.apk` file eligible for package inspection. */
    fun isInspectableApk(entry: FileEntry): Boolean {
        if (entry.isDir || entry.isServer || entry.isShare) return false
        return entry.extension.equals("apk", ignoreCase = true)
    }

    /** True when [file] is a regular non-symlink file (`NOFOLLOW_LINKS`). */
    internal fun isRegularFileNoFollow(file: File): Boolean {
        if (FileInfo.isSymlink(file.path)) return false
        return runCatching {
            Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        }.getOrDefault(false)
    }

    /** True when [file] (raw or canonicalized through parent links) resolves inside the app's private `dataDir`. */
    internal fun isAppPrivateFile(context: Context, file: File): Boolean {
        val rawData = context.applicationInfo?.dataDir ?: return false
        val canonicalData = runCatching { File(rawData).canonicalPath }.getOrDefault(rawData)
        val canonicalTarget = runCatching { file.canonicalPath }.getOrNull() ?: return true
        return FsPath.isWithin(canonicalData, canonicalTarget) ||
            FsPath.isWithin(rawData, file.absolutePath)
    }

    /**
     * Inspects a local `.apk` [file] using [PackageManager.getPackageArchiveInfo] (for manifest
     * metadata and v1/v2/v3/v3.1 signing certificates), [ResourcesProvider] (for FD-safe label
     * lookup), and a bounded EOCD/ZIP scan (for native `lib/<abi>/<name>.so` ABIs and v2+ signing
     * block presence). Rejects symbolic links and app-private paths.
     */
    suspend fun inspectFile(context: Context, file: File): ApkInfo? = withTimeout(INSPECT_TIMEOUT_MS) {
        val appCtx = context.applicationContext
        if (!isRegularFileNoFollow(file) || !file.canRead()) return@withTimeout null
        if (isAppPrivateFile(appCtx, file)) return@withTimeout null
        inspectValidatedFile(appCtx, file)
    }

    /**
     * Stages a local-device `DocumentsProvider` stream (capped at [maxBytes]) to a temporary cache
     * file for [PackageManager.getPackageArchiveInfo], deleting the temporary file immediately
     * afterwards. Returns null if the stream exceeds [maxBytes] or is not a valid APK.
     */
    suspend fun inspectStagedStream(
        context: Context,
        openInput: () -> InputStream,
        maxBytes: Long = LOCAL_DOC_STAGE_MAX_BYTES,
    ): ApkInfo? = withTimeout(INSPECT_TIMEOUT_MS) {
        val appCtx = context.applicationContext
        val dir = File(appCtx.cacheDir, "apk-inspect").apply { mkdirs() }
        val tmp = File.createTempFile("inspect-", ".apk", dir)
        try {
            if (!isRegularFileNoFollow(tmp)) return@withTimeout null
            val copied = openInput().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) return@use false
                        out.write(buf, 0, n)
                    }
                    total > 0L
                }
            }
            if (!copied || !isRegularFileNoFollow(tmp)) return@withTimeout null
            inspectValidatedFile(appCtx, tmp)
        } finally {
            tmp.delete()
        }
    }

    private suspend fun inspectValidatedFile(appCtx: Context, file: File): ApkInfo? {
        val pm = appCtx.packageManager
        val path = file.path

        val zipInspection = inspectZipStructure(file)
        coroutineContext.ensureActive()

        val pkgWithSig = queryPackageArchiveInfo(pm, path, withSignatures = true)
        val pkgInfo: PackageInfo
        val certs: List<ApkCertificateInfo>
        val sigStatus: ApkSignatureStatus

        if (pkgWithSig != null) {
            pkgInfo = pkgWithSig
            val signingInfo = pkgWithSig.signingInfo
            val rawSigners = extractSignerByteArrays(signingInfo)
            certs = rawSigners.mapNotNull { parseCertificateInfo(it) }.distinctBy { it.sha256Fingerprint }
            val schemeVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && signingInfo != null) {
                signingInfo.schemeVersion
            } else {
                null
            }
            sigStatus = resolveSignatureStatus(
                certsNotEmpty = certs.isNotEmpty(),
                schemeVersion = schemeVersion,
                zipValid = zipInspection.zipValid,
                hasV2OrNewerSigningBlock = zipInspection.hasV2OrNewerSigningBlock,
            )
        } else {
            val fallback = queryPackageArchiveInfo(pm, path, withSignatures = false)
                ?: return null
            pkgInfo = fallback
            certs = emptyList()
            sigStatus = ApkSignatureStatus.UnsignedOrUnverified
        }

        val appInfo = pkgInfo.applicationInfo
        val pkgName = sanitizeText(pkgInfo.packageName)
        val appLabel = loadApkLabelSafely(appCtx, file, pkgInfo, pkgName)
            ?.let(::sanitizeText)
            ?.takeUnless { it == pkgName }

        val versionName = sanitizeText(pkgInfo.versionName)
        val versionCode = pkgInfo.longVersionCode.takeIf { it > 0L }
        val minSdk = appInfo?.minSdkVersion?.takeIf { it > 0 }
        val targetSdk = appInfo?.targetSdkVersion?.takeIf { it > 0 }

        return ApkInfo(
            appName = appLabel,
            packageName = pkgName,
            versionName = versionName,
            versionCode = versionCode,
            minSdk = minSdk,
            targetSdk = targetSdk,
            supportedAbis = zipInspection.abis,
            zipInspected = zipInspection.zipValid,
            zipTruncated = zipInspection.truncated,
            certificates = certs,
            signatureStatus = sigStatus,
        ).takeUnless { it.isEmpty }
    }

    /**
     * Loads the APK's application label without calling `ApplicationInfo.loadLabel(PackageManager)`
     * (which caches an open `ApkAssets` mmap/FD in `ResourcesManager` until GC, pinning unlinked
     * staged temp files or risking `vold` `SIGKILL` on removable USB unmount).
     */
    private fun loadApkLabelSafely(
        context: Context,
        file: File,
        pkgInfo: PackageInfo,
        expectedPkg: String?,
    ): String? {
        val ai = pkgInfo.applicationInfo ?: return null
        ai.nonLocalizedLabel?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        val labelRes = ai.labelRes
        if (labelRes == 0) return null
        return runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)?.use { pfd ->
                ResourcesProvider.loadFromApk(pfd).use { provider ->
                    val loader = ResourcesLoader().apply { addProvider(provider) }
                    val isolatedRes = context.createConfigurationContext(
                        Configuration(context.resources.configuration),
                    ).resources
                    isolatedRes.addLoaders(loader)
                    try {
                        val resPkg = runCatching { isolatedRes.getResourcePackageName(labelRes) }.getOrNull()
                        if (resPkg != null && expectedPkg != null &&
                            resPkg != expectedPkg && resPkg != "android"
                        ) {
                            null
                        } else {
                            isolatedRes.getText(labelRes).toString()
                        }
                    } finally {
                        runCatching { isolatedRes.removeLoaders(loader) }
                        loader.clearProviders()
                    }
                }
            }
        }.getOrNull()
    }

    /**
     * Validates EOCD bounds, checks for a v2/v3/v3.1 `"APK Sig Block 42"` before `cenOffset`, and
     * extracts native library ABIs (`lib/<abi>/<name>.so`).
     *
     * Pre-checks EOCD entry count and Central Directory byte size *before* constructing [ZipFile]
     * so crafted archives with millions of empty CEN headers cannot trigger `ZipFile.initCEN` heap
     * floods; falls back to bounded raw CEN streaming when limits are exceeded, and uses
     * [StandardCharsets.ISO_8859_1] so non-UTF-8 obfuscated entry names never abort ABI discovery.
     */
    fun inspectZipStructure(
        file: File,
        maxEntries: Int = MAX_ZIP_ENTRIES,
        maxCenBytes: Long = MAX_CEN_BYTES,
    ): ApkZipInspection {
        if (!isRegularFileNoFollow(file)) return ApkZipInspection(zipValid = false)
        return runCatching {
            val footer = RandomAccessFile(file, "r").use { raf ->
                val eocd = findZipFooter(raf) ?: return@runCatching ApkZipInspection(zipValid = false)
                val hasV2 = detectV2OrNewerSigningBlock(raf, eocd.cenOffset)
                if (eocd.totalEntries > maxEntries || eocd.cenSize > maxCenBytes) {
                    val abis = scanRawCentralDirectoryAbis(raf, eocd.cenOffset, eocd.cenSize, maxEntries)
                    return@runCatching ApkZipInspection(
                        zipValid = true,
                        abis = abis,
                        truncated = true,
                        hasV2OrNewerSigningBlock = hasV2,
                    )
                }
                eocd to hasV2
            }
            val (_, hasV2) = footer
            ZipFile(file, StandardCharsets.ISO_8859_1).use { zip ->
                val enum = zip.entries()
                val names = ArrayList<String>()
                var truncated = false
                while (true) {
                    val hasMore = runCatching { enum.hasMoreElements() }.getOrDefault(false)
                    if (!hasMore) break
                    if (names.size >= maxEntries) {
                        truncated = true
                        break
                    }
                    val entryName = runCatching { enum.nextElement()?.name }.getOrNull() ?: continue
                    names.add(entryName)
                }
                val (abis, _) = extractAbisWithTruncation(names.asSequence(), maxEntries)
                ApkZipInspection(
                    zipValid = true,
                    abis = abis,
                    truncated = truncated,
                    hasV2OrNewerSigningBlock = hasV2,
                )
            }
        }.getOrElse { ApkZipInspection(zipValid = false) }
    }

    /** Backwards-compatible `(zipValid, sortedAbis)` helper wrapping [inspectZipStructure]. */
    fun inspectZipAbis(file: File, maxEntries: Int = MAX_ZIP_ENTRIES): Pair<Boolean, List<String>> {
        val res = inspectZipStructure(file, maxEntries = maxEntries)
        return res.zipValid to res.abis
    }

    /**
     * Pure JVM extraction of Android ABIs from ZIP entry paths (`lib/<abi>/<file>.so`).
     * Caps iteration at [maxEntries] to guard against zip-directory flooding.
     */
    fun extractAbisFromEntryNames(
        entryNames: Sequence<String>,
        maxEntries: Int = MAX_ZIP_ENTRIES,
    ): List<String> = extractAbisWithTruncation(entryNames, maxEntries).first

    /**
     * Extracts `(sortedAbis, truncated)` from [entryNames], setting `truncated = true` when
     * [entryNames] contains more than [maxEntries] items.
     */
    fun extractAbisWithTruncation(
        entryNames: Sequence<String>,
        maxEntries: Int = MAX_ZIP_ENTRIES,
    ): Pair<List<String>, Boolean> {
        val found = LinkedHashSet<String>()
        var count = 0
        var truncated = false
        val iter = entryNames.iterator()
        while (iter.hasNext()) {
            if (count >= maxEntries) {
                truncated = true
                break
            }
            val raw = iter.next()
            count++
            parseAbiFromEntryName(raw)?.let { found.add(it) }
        }
        return sortAbis(found) to truncated
    }

    private fun parseAbiFromEntryName(raw: String): String? {
        if (!raw.startsWith("lib/") || !raw.endsWith(".so") || raw.endsWith("/")) return null
        if (raw.contains('\u0000') || raw.contains('\\')) return null
        val firstSlash = raw.indexOf('/')
        val secondSlash = raw.indexOf('/', firstSlash + 1)
        if (secondSlash <= firstSlash + 1) return null
        // Must be strictly 3 segments: lib/<abi>/<libname>.so
        if (raw.indexOf('/', secondSlash + 1) >= 0) return null
        val abi = raw.substring(firstSlash + 1, secondSlash)
        val libName = raw.substring(secondSlash + 1)
        if (abi.isEmpty() || abi.length > MAX_ABI_LEN || abi == "." || abi == "..") return null
        if (!ABI_NAME_REGEX.matches(abi)) return null
        if (libName.length <= 3 || libName == ".so" || libName == "..") return null
        return abi
    }

    private data class ZipFooter(
        val totalEntries: Long,
        val cenSize: Long,
        val cenOffset: Long,
    )

    private fun findZipFooter(raf: RandomAccessFile): ZipFooter? {
        val fileLen = raf.length()
        if (fileLen < EOCD_MIN_SIZE) return null
        val tailLen = minOf(fileLen, (EOCD_MIN_SIZE + EOCD_MAX_COMMENT).toLong()).toInt()
        val tailStart = fileLen - tailLen
        val buf = ByteArray(tailLen)
        raf.seek(tailStart)
        raf.readFully(buf)

        var eocdRel = -1
        for (i in (tailLen - EOCD_MIN_SIZE) downTo 0) {
            if (readInt32Le(buf, i) == EOCD_SIG) {
                val commentLen = readUint16Le(buf, i + 20)
                if (i + EOCD_MIN_SIZE + commentLen == tailLen) {
                    eocdRel = i
                    break
                }
            }
        }
        if (eocdRel < 0) return null

        val eocdPos = tailStart + eocdRel
        var totalEntries = readUint16Le(buf, eocdRel + 10).toLong()
        var cenSize = readUint32Le(buf, eocdRel + 12)
        var cenOffset = readUint32Le(buf, eocdRel + 16)
        var cenEndLimit = eocdPos

        val needsZip64 = totalEntries == 0xFFFFL ||
            cenSize == 0xFFFFFFFFL ||
            cenOffset == 0xFFFFFFFFL
        if (needsZip64 && eocdPos >= ZIP64_LOCATOR_SIZE) {
            val locBuf = ByteArray(ZIP64_LOCATOR_SIZE)
            raf.seek(eocdPos - ZIP64_LOCATOR_SIZE)
            raf.readFully(locBuf)
            if (readInt32Le(locBuf, 0) == ZIP64_LOCATOR_SIG) {
                val z64Offset = readInt64Le(locBuf, 8)
                if (z64Offset in 0..(eocdPos - ZIP64_LOCATOR_SIZE - ZIP64_EOCD_MIN_SIZE)) {
                    val z64Buf = ByteArray(ZIP64_EOCD_MIN_SIZE)
                    raf.seek(z64Offset)
                    raf.readFully(z64Buf)
                    if (readInt32Le(z64Buf, 0) == ZIP64_EOCD_SIG) {
                        totalEntries = readInt64Le(z64Buf, 32)
                        cenSize = readInt64Le(z64Buf, 40)
                        cenOffset = readInt64Le(z64Buf, 48)
                        cenEndLimit = z64Offset
                    }
                }
            }
        }

        if (totalEntries < 0L || cenSize < 0L || cenOffset < 0L) return null
        if (cenOffset > cenEndLimit || cenSize > cenEndLimit - cenOffset) return null
        return ZipFooter(totalEntries = totalEntries, cenSize = cenSize, cenOffset = cenOffset)
    }

    /**
     * Checks whether a valid `"APK Sig Block 42"` immediately precedes [cenOffset] and contains at
     * least one v2 (`0x7109871a`), v3 (`0xf05368c0`), or v3.1 (`0x1b93ad61`) signature ID-value pair.
     */
    internal fun detectV2OrNewerSigningBlock(raf: RandomAccessFile, cenOffset: Long): Boolean {
        if (cenOffset < 32L) return false
        return runCatching {
            val footer = ByteArray(24)
            raf.seek(cenOffset - 24L)
            raf.readFully(footer)
            for (i in APK_SIG_BLOCK_MAGIC.indices) {
                if (footer[8 + i] != APK_SIG_BLOCK_MAGIC[i]) return@runCatching false
            }
            val blockSizeFooter = readInt64Le(footer, 0)
            if (blockSizeFooter < 24L || blockSizeFooter > cenOffset - 8L) return@runCatching false

            val blockStart = cenOffset - 8L - blockSizeFooter
            val header = ByteArray(8)
            raf.seek(blockStart)
            raf.readFully(header)
            val blockSizeHeader = readInt64Le(header, 0)
            if (blockSizeHeader != blockSizeFooter) return@runCatching false

            var pos = blockStart + 8L
            val pairsEnd = cenOffset - 24L
            val pairHeader = ByteArray(12)
            var pairsSeen = 0
            while (pos < pairsEnd && pairsSeen < MAX_SIG_BLOCK_PAIRS) {
                if (pairsEnd - pos < 12L) return@runCatching false
                raf.seek(pos)
                raf.readFully(pairHeader)
                val pairLen = readInt64Le(pairHeader, 0)
                if (pairLen < 4L || pairLen > pairsEnd - (pos + 8L)) return@runCatching false
                val id = readInt32Le(pairHeader, 8)
                if (id == APK_SIG_SCHEME_V2_ID ||
                    id == APK_SIG_SCHEME_V3_ID ||
                    id == APK_SIG_SCHEME_V31_ID
                ) {
                    return@runCatching true
                }
                pos += 8L + pairLen
                pairsSeen++
            }
            false
        }.getOrDefault(false)
    }

    /**
     * Streams up to [maxEntries] Central Directory file headers directly from [raf] without
     * instantiating [ZipFile], preventing OOM/heap spikes on archives with flooded CEN tables.
     */
    private fun scanRawCentralDirectoryAbis(
        raf: RandomAccessFile,
        cenOffset: Long,
        cenSize: Long,
        maxEntries: Int,
    ): List<String> {
        val found = LinkedHashSet<String>()
        val header = ByteArray(CEN_HEADER_FIXED_SIZE)
        var pos = cenOffset
        val end = cenOffset + cenSize
        var count = 0
        while (count < maxEntries && end - pos >= CEN_HEADER_FIXED_SIZE) {
            raf.seek(pos)
            raf.readFully(header)
            if (readInt32Le(header, 0) != CEN_SIG) break
            val nameLen = readUint16Le(header, 28)
            val extraLen = readUint16Le(header, 30)
            val commentLen = readUint16Le(header, 32)
            val nextPos = pos + CEN_HEADER_FIXED_SIZE + nameLen + extraLen + commentLen
            if (nextPos > end) break
            if (nameLen in 8..(4 + MAX_ABI_LEN + 256)) {
                val nameBytes = ByteArray(nameLen)
                raf.readFully(nameBytes)
                val name = String(nameBytes, StandardCharsets.ISO_8859_1)
                parseAbiFromEntryName(name)?.let { found.add(it) }
            }
            pos = nextPos
            count++
        }
        return sortAbis(found)
    }

    /** Formats the SHA-256 digest of DER-encoded certificate bytes as colon-separated uppercase hex. */
    fun sha256Fingerprint(certBytes: ByteArray): String? {
        if (certBytes.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(certBytes)
        return buildString(digest.size * 3 - 1) {
            for (i in digest.indices) {
                if (i > 0) append(':')
                val b = digest[i].toInt() and 0xFF
                append(HEX_CHARS[b ushr 4])
                append(HEX_CHARS[b and 0x0F])
            }
        }
    }

    /**
     * Validates and extracts an [ApkCertificateInfo] from DER-encoded X.509 certificate bytes.
     * Returns `null` if [certBytes] does not parse cleanly as a single valid X.509 certificate.
     */
    fun parseCertificateInfo(certBytes: ByteArray): ApkCertificateInfo? {
        if (certBytes.isEmpty()) return null
        val cert = runCatching {
            val inp = ByteArrayInputStream(certBytes)
            val factory = CertificateFactory.getInstance("X.509")
            val parsed = factory.generateCertificate(inp) as? X509Certificate
            if (parsed != null && inp.available() == 0) parsed else null
        }.getOrNull() ?: return null
        val fingerprint = sha256Fingerprint(cert.encoded) ?: return null
        val subject = cert.subjectX500Principal?.name?.let(::sanitizeText)
        return ApkCertificateInfo(sha256Fingerprint = fingerprint, subject = subject)
    }

    /**
     * Follows [SigningInfo]'s official contract: uses [apkContentsSigners] when
     * [hasMultipleSigners] is true, and [signingCertificateHistory] (preserving v3 key-rotation
     * lineage) otherwise, falling back to [apkContentsSigners] only if history is empty.
     */
    internal fun selectSignerByteArrays(
        hasMultipleSigners: Boolean,
        apkContentsSigners: List<ByteArray>,
        signingCertificateHistory: List<ByteArray>,
    ): List<ByteArray> = if (hasMultipleSigners) {
        apkContentsSigners
    } else {
        signingCertificateHistory.ifEmpty { apkContentsSigners }
    }

    /**
     * Determines [ApkSignatureStatus], distinguishing v2/v3+ block-signed APKs ([ApkSignatureStatus.Verified])
     * from legacy v1 JAR-only signed APKs ([ApkSignatureStatus.LegacyV1JarOnly]).
     */
    internal fun resolveSignatureStatus(
        certsNotEmpty: Boolean,
        schemeVersion: Int?,
        zipValid: Boolean,
        hasV2OrNewerSigningBlock: Boolean,
    ): ApkSignatureStatus {
        if (!certsNotEmpty) return ApkSignatureStatus.UnsignedOrUnverified
        if (schemeVersion != null && schemeVersion >= SigningInfo.VERSION_JAR) {
            return if (schemeVersion < SigningInfo.VERSION_SIGNING_BLOCK_V2) {
                ApkSignatureStatus.LegacyV1JarOnly
            } else {
                ApkSignatureStatus.Verified
            }
        }
        return if (zipValid && !hasV2OrNewerSigningBlock) {
            ApkSignatureStatus.LegacyV1JarOnly
        } else {
            ApkSignatureStatus.Verified
        }
    }

    private fun queryPackageArchiveInfo(
        pm: PackageManager,
        archivePath: String,
        withSignatures: Boolean,
    ): PackageInfo? = runCatching {
        val flags = if (withSignatures) PackageManager.GET_SIGNING_CERTIFICATES else 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(archivePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(archivePath, flags)
        }
    }.getOrNull()

    private fun extractSignerByteArrays(signingInfo: SigningInfo?): List<ByteArray> {
        if (signingInfo == null) return emptyList()
        val contents = signingInfo.apkContentsSigners.orEmpty()
            .mapNotNull { sig -> runCatching { sig.toByteArray() }.getOrNull() }
        val history = signingInfo.signingCertificateHistory.orEmpty()
            .mapNotNull { sig -> runCatching { sig.toByteArray() }.getOrNull() }
        return selectSignerByteArrays(
            hasMultipleSigners = signingInfo.hasMultipleSigners(),
            apkContentsSigners = contents,
            signingCertificateHistory = history,
        )
    }

    private fun sortAbis(abis: Collection<String>): List<String> =
        abis.sortedWith(
            compareBy<String>(
                { ABI_ORDER.indexOf(it).let { idx -> if (idx >= 0) idx else Int.MAX_VALUE } },
                { it.lowercase(Locale.US) },
            ),
        )

    /**
     * Sanitizes untrusted APK metadata strings: strips all Unicode `FORMAT` (`Cf`, including BiDi
     * overrides/isolates `U+202A..U+202E`, `U+2066..U+2069`, BOM, ZWSP) and collapses Unicode
     * `CONTROL` (`Cc`, including C0/C1 controls) and whitespace to single ASCII spaces without
     * splitting UTF-16 surrogate pairs.
     */
    internal fun sanitizeText(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = buildString(raw.length.coerceAtMost(MAX_TEXT_LEN)) {
            var prevSpace = false
            var i = 0
            while (i < raw.length && length < MAX_TEXT_LEN) {
                val cp = raw.codePointAt(i)
                val charCount = Character.charCount(cp)
                i += charCount
                val type = Character.getType(cp).toByte()
                if (type == Character.FORMAT) {
                    continue
                }
                if (type == Character.CONTROL || Character.isWhitespace(cp)) {
                    if (isNotEmpty() && !prevSpace) {
                        append(' ')
                        prevSpace = true
                    }
                } else {
                    if (length + charCount > MAX_TEXT_LEN) break
                    appendCodePoint(cp)
                    prevSpace = false
                }
            }
        }.trim()
        return cleaned.takeIf { it.isNotEmpty() }
    }

    private fun readUint16Le(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun readInt32Le(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun readUint32Le(b: ByteArray, off: Int): Long =
        readInt32Le(b, off).toLong() and 0xFFFFFFFFL

    private fun readInt64Le(b: ByteArray, off: Int): Long =
        (readInt32Le(b, off).toLong() and 0xFFFFFFFFL) or
            (readInt32Le(b, off + 4).toLong() shl 32)

    private val HEX_CHARS = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'A', 'B', 'C', 'D', 'E', 'F',
    )
}

/** Pure JVM formatters for [ApkInfo] display rows and clipboard summary export. */
object ApkFormatters {
    private const val MAX_REASONABLE_SDK = 1000

    /** Formats version name and version code, e.g. `"3.0 (20)"`. */
    fun formatVersion(versionName: String?, versionCode: Long?): String? {
        val name = ApkInspector.sanitizeText(versionName)
        val code = versionCode?.takeIf { it > 0L }
        return when {
            name != null && code != null -> "$name ($code)"
            name != null -> name
            code != null -> code.toString()
            else -> null
        }
    }

    /** Formats an Android API level with its platform release name when known, e.g. `"API 36 (Android 16)"`. */
    fun formatSdk(apiLevel: Int?): String? {
        if (apiLevel == null || apiLevel <= 0 || apiLevel > MAX_REASONABLE_SDK) return null
        val release = when (apiLevel) {
            1 -> "Android 1.0"
            2 -> "Android 1.1"
            3 -> "Android 1.5"
            4 -> "Android 1.6"
            5 -> "Android 2.0"
            6 -> "Android 2.0.1"
            7 -> "Android 2.1"
            8 -> "Android 2.2"
            9 -> "Android 2.3"
            10 -> "Android 2.3.3"
            11 -> "Android 3.0"
            12 -> "Android 3.1"
            13 -> "Android 3.2"
            14 -> "Android 4.0"
            15 -> "Android 4.0.3"
            16 -> "Android 4.1"
            17 -> "Android 4.2"
            18 -> "Android 4.3"
            19 -> "Android 4.4"
            20 -> "Android 4.4W"
            21 -> "Android 5.0"
            22 -> "Android 5.1"
            23 -> "Android 6.0"
            24 -> "Android 7.0"
            25 -> "Android 7.1"
            26 -> "Android 8.0"
            27 -> "Android 8.1"
            28 -> "Android 9"
            29 -> "Android 10"
            30 -> "Android 11"
            31 -> "Android 12"
            32 -> "Android 12L"
            33 -> "Android 13"
            34 -> "Android 14"
            35 -> "Android 15"
            36 -> "Android 16"
            37 -> "Android 17"
            else -> null
        }
        return if (release != null) "API $apiLevel ($release)" else "API $apiLevel"
    }

    /**
     * Formats the list of supported native ABIs, or `"Universal (no native libs)"` when the full
     * ZIP directory was inspected without truncation and contained zero native `.so` libraries.
     * Returns `null` if the archive was not inspected or was truncated before observing any `.so`.
     */
    fun formatAbis(
        abis: List<String>,
        zipInspected: Boolean,
        zipTruncated: Boolean = false,
    ): String? = when {
        abis.isNotEmpty() -> abis.joinToString(", ")
        zipInspected && !zipTruncated -> "Universal (no native libs)"
        else -> null
    }

    /** Formats a plain-text multi-line summary of populated APK fields for clipboard copy. */
    fun formatSummary(info: ApkInfo): String = buildString {
        info.appName?.let { appendLine("App: $it") }
        info.packageName?.let { appendLine("Package: $it") }
        formatVersion(info.versionName, info.versionCode)?.let { appendLine("Version: $it") }
        formatSdk(info.targetSdk)?.let { appendLine("Target SDK: $it") }
        formatSdk(info.minSdk)?.let { appendLine("Min SDK: $it") }
        formatAbis(info.supportedAbis, info.zipInspected, info.zipTruncated)?.let { appendLine("ABIs: $it") }
        if (info.signatureStatus == ApkSignatureStatus.LegacyV1JarOnly) {
            appendLine("Signature: Legacy v1 (JAR only)")
        } else if (info.certificates.isEmpty() && info.signatureStatus == ApkSignatureStatus.UnsignedOrUnverified) {
            appendLine("Signature: Unsigned or unverified")
        }
        if (info.certificates.isNotEmpty()) {
            info.certificates.forEachIndexed { idx, cert ->
                val suffix = if (info.certificates.size > 1) " #${idx + 1}" else ""
                appendLine("Cert$suffix SHA-256: ${cert.sha256Fingerprint}")
                cert.subject?.let { appendLine("Signer$suffix: $it") }
            }
        }
    }.trimEnd()
}
