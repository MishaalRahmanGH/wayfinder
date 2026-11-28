// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class ApkInfoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun isInspectableApkMatchesApkFilesOnly() {
        assertTrue(ApkInspector.isInspectableApk(FileEntry("/a/app.apk", "app.apk", isDir = false)))
        assertTrue(ApkInspector.isInspectableApk(FileEntry("/a/APP-RELEASE.APK", "APP-RELEASE.APK", isDir = false)))
        assertFalse(ApkInspector.isInspectableApk(FileEntry("/a/bundle.apks", "bundle.apks", isDir = false)))
        assertFalse(ApkInspector.isInspectableApk(FileEntry("/a/bundle.xapk", "bundle.xapk", isDir = false)))
        assertFalse(ApkInspector.isInspectableApk(FileEntry("/a/archive.zip", "archive.zip", isDir = false)))
        assertFalse(ApkInspector.isInspectableApk(FileEntry("/a/folder.apk", "folder.apk", isDir = true)))
        assertFalse(ApkInspector.isInspectableApk(FileEntry("smb://host", "host.apk", isDir = false, isServer = true)))
        assertFalse(ApkInspector.isInspectableApk(FileEntry("smb://host/share", "share.apk", isDir = false, isShare = true)))
    }

    @Test
    fun extractAbisFromEntryNamesSortsDeduplicatesAndRejectsMalformedEntries() {
        val entries = sequenceOf(
            "AndroidManifest.xml",
            "classes.dex",
            "lib/x86_64/libfoo.so",
            "lib/arm64-v8a/libfoo.so",
            "lib/arm64-v8a/libbar.so",
            "lib/armeabi-v7a/libfoo.so",
            "lib/riscv64/libfoo.so",
            // Must be ignored:
            "lib/x86/nested/libbad.so",
            "lib/x86/not_a_lib.txt",
            "lib/x86/.so",
            "lib/x86/",
            "lib/../libevil.so",
            "lib/./libevil.so",
            "lib/bad abi/libfoo.so",
            "lib/bad\u0000abi/libfoo.so",
            "lib/bad\\abi/libfoo.so",
            "lib/${"a".repeat(33)}/libfoo.so",
            "assets/lib/mips/libasset.so",
        )
        assertEquals(
            listOf("arm64-v8a", "armeabi-v7a", "x86_64", "riscv64"),
            ApkInspector.extractAbisFromEntryNames(entries),
        )
    }

    @Test
    fun extractAbisCapsScannedEntryCountAndTracksTruncation() {
        val flooded = (1..200).asSequence().map { "res/drawable/img_$it.png" } +
            sequenceOf("lib/arm64-v8a/liblate.so")
        val (cappedAbis, cappedTruncated) = ApkInspector.extractAbisWithTruncation(flooded, maxEntries = 50)
        assertEquals(emptyList<String>(), cappedAbis)
        assertTrue(cappedTruncated)
        // Capped archives with zero observed .so must NOT falsely claim "Universal (no native libs)".
        assertNull(ApkFormatters.formatAbis(cappedAbis, zipInspected = true, zipTruncated = cappedTruncated))

        val (fullAbis, fullTruncated) = ApkInspector.extractAbisWithTruncation(flooded, maxEntries = 250)
        assertEquals(listOf("arm64-v8a"), fullAbis)
        assertFalse(fullTruncated)
    }

    @Test
    fun inspectZipStructureRejectsSymlinksHandlesNonUtf8NamesAndPreChecksCenCaps() {
        val nativeApk = tmp.newFile("native.apk")
        ZipOutputStream(nativeApk.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml")); zos.write(byteArrayOf(1, 2)); zos.closeEntry()
            zos.putNextEntry(ZipEntry("lib/x86_64/libnative.so")); zos.write(byteArrayOf(0)); zos.closeEntry()
            zos.putNextEntry(ZipEntry("lib/arm64-v8a/libnative.so")); zos.write(byteArrayOf(0)); zos.closeEntry()
        }
        val nativeRes = ApkInspector.inspectZipStructure(nativeApk)
        assertTrue(nativeRes.zipValid)
        assertFalse(nativeRes.truncated)
        assertFalse(nativeRes.hasV2OrNewerSigningBlock)
        assertEquals(listOf("arm64-v8a", "x86_64"), nativeRes.abis)

        // Symbolic links must be rejected without following them.
        val symlinkPath = tmp.root.toPath().resolve("symlink.apk")
        val createdLink = runCatching { Files.createSymbolicLink(symlinkPath, nativeApk.toPath()) }.isSuccess
        if (createdLink) {
            assertFalse(ApkInspector.isRegularFileNoFollow(symlinkPath.toFile()))
            assertFalse(ApkInspector.inspectZipStructure(symlinkPath.toFile()).zipValid)
        }

        // Pre-cap raw CEN fallback when totalEntries > maxEntries (avoids ZipFile.initCEN flood).
        val cappedRes = ApkInspector.inspectZipStructure(nativeApk, maxEntries = 2)
        assertTrue(cappedRes.zipValid)
        assertTrue(cappedRes.truncated)
        assertEquals(listOf("x86_64"), cappedRes.abis)

        // Pre-cap raw CEN fallback when no .so appears before cap -> formatAbis returns null, never "Universal".
        val floodedResourcesApk = tmp.newFile("flooded.apk")
        ZipOutputStream(floodedResourcesApk.outputStream()).use { zos ->
            for (i in 1..5) {
                zos.putNextEntry(ZipEntry("res/raw/item_$i.bin")); zos.write(byteArrayOf(0)); zos.closeEntry()
            }
            zos.putNextEntry(ZipEntry("lib/arm64-v8a/libhidden.so")); zos.write(byteArrayOf(0)); zos.closeEntry()
        }
        val floodedRes = ApkInspector.inspectZipStructure(floodedResourcesApk, maxEntries = 3)
        assertTrue(floodedRes.zipValid)
        assertTrue(floodedRes.truncated)
        assertTrue(floodedRes.abis.isEmpty())
        assertNull(ApkFormatters.formatAbis(floodedRes.abis, floodedRes.zipInspectedOrValid(), floodedRes.truncated))

        // Obfuscated non-UTF-8 ZIP entry bytes must not abort ABI discovery.
        val nonUtf8Apk = tmp.newFile("obfuscated.apk")
        nonUtf8Apk.writeBytes(
            buildRawZipWithSigningBlock(
                entries = listOf(
                    byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x80.toByte(), '.'.code.toByte(), 'x'.code.toByte()) to byteArrayOf(1),
                    "lib/arm64-v8a/libjni.so".toByteArray(StandardCharsets.US_ASCII) to byteArrayOf(2),
                ),
                signingBlockId = null,
            ),
        )
        val nonUtf8Res = ApkInspector.inspectZipStructure(nonUtf8Apk)
        assertTrue(nonUtf8Res.zipValid)
        assertEquals(listOf("arm64-v8a"), nonUtf8Res.abis)

        val corrupt = tmp.newFile("corrupt.apk").apply { writeBytes("not a zip archive".toByteArray()) }
        assertFalse(ApkInspector.inspectZipStructure(corrupt).zipValid)
        assertFalse(ApkInspector.inspectZipStructure(File(tmp.root, "missing.apk")).zipValid)
    }

    @Test
    fun detectV2OrNewerSigningBlockAndSignatureStatusDistinguishLegacyV1FromV2AndV3() {
        val entryList = listOf("AndroidManifest.xml".toByteArray(StandardCharsets.US_ASCII) to byteArrayOf(0x01))

        // 1. Plain ZIP (v1 JAR-only APK without APK Signing Block) -> LegacyV1JarOnly
        val v1OnlyFile = tmp.newFile("v1-only.apk").apply {
            writeBytes(buildRawZipWithSigningBlock(entryList, signingBlockId = null))
        }
        val v1Inspect = ApkInspector.inspectZipStructure(v1OnlyFile)
        assertTrue(v1Inspect.zipValid)
        assertFalse(v1Inspect.hasV2OrNewerSigningBlock)
        assertEquals(
            ApkSignatureStatus.LegacyV1JarOnly,
            ApkInspector.resolveSignatureStatus(
                certsNotEmpty = true,
                schemeVersion = null,
                zipValid = v1Inspect.zipValid,
                hasV2OrNewerSigningBlock = v1Inspect.hasV2OrNewerSigningBlock,
            ),
        )

        // 2. APK Signing Block containing only non-signature metadata/padding ID (0x42726577) -> still LegacyV1JarOnly
        val paddingOnlyFile = tmp.newFile("padding-block.apk").apply {
            writeBytes(buildRawZipWithSigningBlock(entryList, signingBlockId = 0x42726577))
        }
        val padInspect = ApkInspector.inspectZipStructure(paddingOnlyFile)
        assertTrue(padInspect.zipValid)
        assertFalse(padInspect.hasV2OrNewerSigningBlock)

        // 3. APK Signing Block with v2 ID (0x7109871a) or v3 ID (0xf05368c0) -> Verified
        for (schemeId in listOf(ApkInspector.APK_SIG_SCHEME_V2_ID, ApkInspector.APK_SIG_SCHEME_V3_ID, ApkInspector.APK_SIG_SCHEME_V31_ID)) {
            val signedFile = tmp.newFile("scheme-$schemeId.apk").apply {
                writeBytes(buildRawZipWithSigningBlock(entryList, signingBlockId = schemeId))
            }
            val inspect = ApkInspector.inspectZipStructure(signedFile)
            assertTrue(inspect.zipValid)
            assertTrue("Expected v2+ block detected for schemeId=$schemeId", inspect.hasV2OrNewerSigningBlock)
            assertEquals(
                ApkSignatureStatus.Verified,
                ApkInspector.resolveSignatureStatus(
                    certsNotEmpty = true,
                    schemeVersion = null,
                    zipValid = inspect.zipValid,
                    hasV2OrNewerSigningBlock = inspect.hasV2OrNewerSigningBlock,
                ),
            )
        }

        // 4. Explicit API 35+ schemeVersion takes precedence when >= 1.
        assertEquals(
            ApkSignatureStatus.LegacyV1JarOnly,
            ApkInspector.resolveSignatureStatus(certsNotEmpty = true, schemeVersion = 1, zipValid = true, hasV2OrNewerSigningBlock = true),
        )
        assertEquals(
            ApkSignatureStatus.Verified,
            ApkInspector.resolveSignatureStatus(certsNotEmpty = true, schemeVersion = 3, zipValid = true, hasV2OrNewerSigningBlock = false),
        )
        assertEquals(
            ApkSignatureStatus.UnsignedOrUnverified,
            ApkInspector.resolveSignatureStatus(certsNotEmpty = false, schemeVersion = 3, zipValid = true, hasV2OrNewerSigningBlock = true),
        )
    }

    @Test
    fun selectSignerByteArraysPreservesKeyRotationLineageAndMultiSigners() {
        val origCert = byteArrayOf(1, 2, 3)
        val rotatedCert = byteArrayOf(4, 5, 6)

        // Single-signer v3 key-rotated APK: signingCertificateHistory holds [origCert, rotatedCert].
        assertEquals(
            listOf(origCert, rotatedCert),
            ApkInspector.selectSignerByteArrays(
                hasMultipleSigners = false,
                apkContentsSigners = listOf(rotatedCert),
                signingCertificateHistory = listOf(origCert, rotatedCert),
            ),
        )

        // Multiple active co-signers: uses apkContentsSigners.
        assertEquals(
            listOf(origCert, rotatedCert),
            ApkInspector.selectSignerByteArrays(
                hasMultipleSigners = true,
                apkContentsSigners = listOf(origCert, rotatedCert),
                signingCertificateHistory = emptyList(),
            ),
        )
    }

    @Test
    fun sha256FingerprintAndStrictCertificateParsingRejectMalformedAndTrailingBytes() {
        assertNull(ApkInspector.sha256Fingerprint(ByteArray(0)))
        assertNull(ApkInspector.parseCertificateInfo(ByteArray(0)))

        // Known SHA-256 test vector for ASCII "abc".
        val abcFp = ApkInspector.sha256Fingerprint("abc".toByteArray(Charsets.UTF_8))
        assertEquals(
            "BA:78:16:BF:8F:01:CF:EA:41:41:40:DE:5D:AE:22:23:B0:03:61:A3:96:17:7A:9C:B4:10:FF:61:F2:00:15:AD",
            abcFp,
        )

        // Malformed non-X.509 DER bytes must return null (never a fake ApkCertificateInfo).
        assertNull(ApkInspector.parseCertificateInfo("abc".toByteArray(Charsets.UTF_8)))

        // Public self-signed test X.509 DER certificate (CN=Wayfinder Test, O=Wayfinder).
        val derBytes = Base64.getMimeDecoder().decode(TEST_X509_CERT_BASE64)
        val certInfo = ApkInspector.parseCertificateInfo(derBytes)
        assertNotNull(certInfo)
        assertEquals(TEST_X509_CERT_SHA256, certInfo!!.sha256Fingerprint)
        assertEquals("CN=Wayfinder Test,O=Wayfinder", certInfo.subject)

        // Valid DER certificate followed by trailing garbage bytes must be rejected.
        assertNull(ApkInspector.parseCertificateInfo(derBytes + byteArrayOf(0x00, 0x01)))
    }

    @Test
    fun sanitizeTextStripsUnicodeBidiFormatControlsAndPreservesSurrogatePairs() {
        // Strips U+202E (RLO), U+202C (PDF), U+2066..U+2069 isolates, ZWSP (U+200B), and BOM (U+FEFF).
        assertEquals(
            "Safeapk.exe",
            ApkInspector.sanitizeText("\uFEFFSafe\u202Eapk.exe\u202C\u200B\u2066\u2069"),
        )
        // Collapses C0 & C1 control characters (U+0085, U+009F) and whitespace to single ASCII spaces.
        assertEquals(
            "CN=Test\u0020Dev, O=Org",
            ApkInspector.sanitizeText("CN=Test\u0085\u009F\n\tDev,   O=Org"),
        )
        // Never splits a supplementary UTF-16 surrogate pair at MAX_TEXT_LEN (256).
        val prefix255 = "A".repeat(255)
        val withEmojiAtBoundary = prefix255 + "\uD83D\uDE80"
        val sanitized = ApkInspector.sanitizeText(withEmojiAtBoundary)!!
        assertEquals(255, sanitized.length)
        assertFalse(sanitized.last().isHighSurrogate())
    }

    @Test
    fun formattersFormatVersionSdkAbisLegacyV1AndSummary() {
        assertEquals("3.0 (20)", ApkFormatters.formatVersion("3.0", 20L))
        assertEquals("3.0", ApkFormatters.formatVersion("3.0", null))
        assertEquals("20", ApkFormatters.formatVersion("   ", 20L))
        assertNull(ApkFormatters.formatVersion("", -1L))

        assertEquals("API 30 (Android 11)", ApkFormatters.formatSdk(30))
        assertEquals("API 36 (Android 16)", ApkFormatters.formatSdk(36))
        assertEquals("API 99", ApkFormatters.formatSdk(99))
        assertNull(ApkFormatters.formatSdk(0))
        assertNull(ApkFormatters.formatSdk(1001))

        assertEquals("arm64-v8a, x86_64", ApkFormatters.formatAbis(listOf("arm64-v8a", "x86_64"), zipInspected = true))
        assertEquals("Universal (no native libs)", ApkFormatters.formatAbis(emptyList(), zipInspected = true, zipTruncated = false))
        assertNull(ApkFormatters.formatAbis(emptyList(), zipInspected = true, zipTruncated = true))
        assertNull(ApkFormatters.formatAbis(emptyList(), zipInspected = false))

        val legacyV1 = ApkInfo(
            packageName = "com.example.legacy",
            targetSdk = 28,
            certificates = listOf(ApkCertificateInfo(TEST_X509_CERT_SHA256, "CN=Legacy")),
            signatureStatus = ApkSignatureStatus.LegacyV1JarOnly,
        )
        val v1Summary = ApkFormatters.formatSummary(legacyV1)
        assertTrue(v1Summary.contains("Signature: Legacy v1 (JAR only)"))
        assertTrue(v1Summary.contains("Cert SHA-256: $TEST_X509_CERT_SHA256"))

        val unsigned = ApkInfo(
            packageName = "com.example.unsigned",
            signatureStatus = ApkSignatureStatus.UnsignedOrUnverified,
        )
        assertTrue(ApkFormatters.formatSummary(unsigned).contains("Signature: Unsigned or unverified"))
    }

    @Test
    fun fuzzUntrustedApkStructuresBidiStringsAndCertificates() {
        val rng = Random(20261128L)
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789/._-\\\u0000\r\n\t\u202E\u202A\u2066\u200B\u0085\u009F :!"

        fun randString(maxLen: Int): String {
            val len = rng.nextInt(0, maxLen + 1)
            return buildString(len) { repeat(len) { append(alphabet[rng.nextInt(alphabet.length)]) } }
        }

        // Fuzz ABI extraction.
        repeat(1_500) {
            val entries = List(rng.nextInt(0, 12)) { "lib/${randString(36)}/${randString(20)}.so" }
            val abis = ApkInspector.extractAbisFromEntryNames(entries.asSequence())
            for (abi in abis) {
                assertTrue(abi.length in 1..32)
                assertFalse(abi.contains('/'))
                assertFalse(abi.contains('\\'))
                assertFalse(abi.contains('\u0000'))
                assertTrue(abi != "." && abi != "..")
            }
        }

        // Fuzz sanitizeText, certificate parsing, and random binary files in inspectZipStructure.
        repeat(600) { idx ->
            val clean = ApkInspector.sanitizeText(randString(350))
            if (clean != null) {
                assertTrue(clean.length <= 256)
                var i = 0
                while (i < clean.length) {
                    val cp = clean.codePointAt(i)
                    i += Character.charCount(cp)
                    val type = Character.getType(cp).toByte()
                    assertTrue("FORMAT char leaked: U+${cp.toString(16)}", type != Character.FORMAT)
                    assertTrue("CONTROL char leaked: U+${cp.toString(16)}", type != Character.CONTROL)
                }
            }

            val randomBytes = ByteArray(rng.nextInt(0, 256)).also { rng.nextBytes(it) }
            // Random bytes should virtually never parse as X.509; if null, safe.
            val cert = ApkInspector.parseCertificateInfo(randomBytes)
            if (cert != null) {
                assertEquals(95, cert.sha256Fingerprint.length)
            }

            if (idx < 150) {
                val fuzzFile = File(tmp.root, "fuzz-$idx.apk").apply { writeBytes(randomBytes) }
                ApkInspector.inspectZipStructure(fuzzFile)
                fuzzFile.delete()
            }
        }
    }

    private fun ApkZipInspection.zipInspectedOrValid(): Boolean = zipValid

    /**
     * Builds a minimal valid uncompressed ZIP in memory, optionally inserting an `"APK Sig Block 42"`
     * with a single `[pairLen, signingBlockId, payload]` entry immediately before `cenOffset`.
     */
    private fun buildRawZipWithSigningBlock(
        entries: List<Pair<ByteArray, ByteArray>>,
        signingBlockId: Int?,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val offsets = IntArray(entries.size)
        val crcs = IntArray(entries.size)

        fun writeLe16(v: Int) {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
        }
        fun writeLe32(v: Int) {
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        }
        fun writeLe64(v: Long) {
            out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
        }

        // Local file headers + uncompressed payloads
        for (i in entries.indices) {
            val (name, data) = entries[i]
            offsets[i] = out.size()
            val crc = CRC32().apply { update(data) }.value.toInt()
            crcs[i] = crc
            writeLe32(0x04034b50)
            writeLe16(20)
            writeLe16(0)
            writeLe16(0)
            writeLe16(0)
            writeLe16(0)
            writeLe32(crc)
            writeLe32(data.size)
            writeLe32(data.size)
            writeLe16(name.size)
            writeLe16(0)
            out.write(name)
            out.write(data)
        }

        // Optional APK Signing Block right before Central Directory
        if (signingBlockId != null) {
            val dummyValue = byteArrayOf(0x01, 0x02, 0x03, 0x04)
            val pairLen = 4L + dummyValue.size // 8
            val blockSize = (8L + pairLen) + 8L + 16L // pairs + trailing size + 16-byte magic
            writeLe64(blockSize)
            writeLe64(pairLen)
            writeLe32(signingBlockId)
            out.write(dummyValue)
            writeLe64(blockSize)
            out.write("APK Sig Block 42".toByteArray(StandardCharsets.US_ASCII))
        }

        val cenOffset = out.size()
        for (i in entries.indices) {
            val (name, data) = entries[i]
            writeLe32(0x02014b50)
            writeLe16(20)
            writeLe16(20)
            writeLe16(0)
            writeLe16(0)
            writeLe16(0)
            writeLe16(0)
            writeLe32(crcs[i])
            writeLe32(data.size)
            writeLe32(data.size)
            writeLe16(name.size)
            writeLe16(0)
            writeLe16(0)
            writeLe16(0)
            writeLe16(0)
            writeLe32(0)
            writeLe32(offsets[i])
            out.write(name)
        }
        val cenSize = out.size() - cenOffset

        // EOCD
        writeLe32(0x06054b50)
        writeLe16(0)
        writeLe16(0)
        writeLe16(entries.size)
        writeLe16(entries.size)
        writeLe32(cenSize)
        writeLe32(cenOffset)
        writeLe16(0)
        return out.toByteArray()
    }

    companion object {
        // Public self-signed test X.509 certificate only (no private key).
        private const val TEST_X509_CERT_SHA256 =
            "C5:16:82:AA:CF:D3:89:3D:37:0A:0E:C4:61:66:4C:96:69:39:1C:58:ED:4A:20:47:B5:BB:AB:3E:B6:3B:2C:CF"
        private const val TEST_X509_CERT_BASE64 =
            "MIIC/TCCAeWgAwIBAgIIGhZFR/6W1jcwDQYJKoZIhvcNAQEMBQAwLTESMBAGA1UEChMJV2F5Zmlu" +
                "ZGVyMRcwFQYDVQQDEw5XYXlmaW5kZXIgVGVzdDAeFw0yNjExMjcwMTQ2MzhaFw0zNjExMjQwMTQ2" +
                "MzhaMC0xEjAQBgNVBAoTCVdheWZpbmRlcjEXMBUGA1UEAxMOV2F5ZmluZGVyIFRlc3QwggEiMA0G" +
                "CSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQDeYPm09CI0Y9O4NPmeA2nwHSH65XtGiKUUBHIabVuZ" +
                "K2NkNkxXtoejh4Hbqz4Zas9A2AnTLIHjy0pg08RsjZjngw7nKf3yjY4hYZkzL/IndMlgcSAdJ6Ks" +
                "NaQj1mEdrit5NPkZJ/A57OG/ov0Cy771E3OXC5dne9Prq/5Oqdqucf6M1i6uJa5Bm3EfSs/nm/zO" +
                "aEdt75AKPntrd+Vqz/jJEnZGGoX3aTV+fJ2MnDyrNe8QMetlLMzXM0Sc6dowO6GQ7JIzAukK8dtP" +
                "ZzypKYbEvl8XRt0kmJuRp2QtpHSUyrSn/uzJxgepfBQ9nY7qQhDiF5DIudNyFTC5DMveOBS5AgMB" +
                "AAGjITAfMB0GA1UdDgQWBBRX+vbyb5ycUUZw2pMKcK6hn7QzqDANBgkqhkiG9w0BAQwFAAOCAQEA" +
                "w5m+8HnsDaMRT7f9809v00tLTpZVn67dLtfufCrHSVrb3yMzutNOn7eqoWXFCOC6Lq7FQWd4FYZL" +
                "TUFa26H2zpL+52lq3V0hKvDTqghYulfoIz6ay4vcBB8cwS5xI5P+NCPVoNlKWJG+jkm8XNGz8vzR" +
                "lHHhcNYLviU1I3ZZL9OuR2XNaBg7f+wcb9VPSinJDK0mfOGOwn1UKbscxLd2QCQH+hYnqOc3SxW5" +
                "YxrEG/N/JV+WWJ5Lao/rqq3+W05De4JoGGiPfHPgHHLw4y3/uv1z6NLZgdWVug2anBN1CkOjzFJq" +
                "2ahQeOerfjWJkiRSpttUpyA1GUEJAAAMwUpOEQ=="
    }
}
