// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SystemFilesHandoffTest {

    @Test
    fun isApkFileDetectsApkPathsAndOpaqueDocDisplayNames() {
        assertTrue(SystemFilesHandoff.isApkFile("/storage/emulated/0/Download/app.apk"))
        assertTrue(SystemFilesHandoff.isApkFile("Wayfinder-debug.APK"))
        assertTrue(SystemFilesHandoff.isApkFile("smb://nas/share/tool.apk"))
        // Opaque doc:// IDs (e.g. DownloadsProvider msf:10492) match via displayName.
        assertTrue(
            SystemFilesHandoff.isApkFile(
                "doc://com.android.providers.downloads.documents/msf:10492",
                displayName = "wayfinder-release.apk",
            ),
        )
        assertFalse(
            SystemFilesHandoff.isApkFile(
                "doc://com.android.providers.downloads.documents/msf:10492",
                displayName = "report.pdf",
            ),
        )
        assertFalse(SystemFilesHandoff.isApkFile(".apk"))
        assertFalse(SystemFilesHandoff.isApkFile("/storage/emulated/0/.apk"))
        assertFalse(SystemFilesHandoff.isApkFile("bundle.apks"))
        assertFalse(SystemFilesHandoff.isApkFile("bundle.xapk"))
        assertFalse(SystemFilesHandoff.isApkFile("archive.zip"))
        assertFalse(SystemFilesHandoff.isApkFile(""))
        assertFalse(SystemFilesHandoff.isApkFile("app.apk\u0000"))
        assertFalse(SystemFilesHandoff.isApkFile("doc://auth/msf:1", displayName = "evil.apk\u0000"))
    }

    @Test
    fun eligibleParentFolderAcceptsOnlyLocalStorageAndLocalDocumentAuthorities() {
        val primary = "/storage/emulated/10"
        val localSaf = SafMount(
            id = "local_dl",
            label = "Downloads",
            uri = "content://com.android.externalstorage.documents/tree/primary%3ADownload",
        )
        val linuxSaf = SafMount(
            id = "linux_vm",
            label = "Linux",
            uri = "content://com.android.virtualization.terminal.documents/tree/root",
        )
        val driveSaf = SafMount(
            id = "gdrive",
            label = "Drive",
            uri = "content://com.google.android.apps.docs.storage/tree/acc%3Broot",
        )
        val oneDriveSaf = SafMount(
            id = "onedrive",
            label = "OneDrive",
            uri = "content://com.microsoft.skydrive.content.StorageAccessProvider/tree/root",
        )
        val phoneSaf = SafMount(
            id = "phone_qa",
            label = "Pixel",
            uri = "content://com.google.ambient.streaming.documents/tree/dev1",
        )
        val mounts = listOf(localSaf, linuxSaf, driveSaf, oneDriveSaf, phoneSaf)

        // Accepted local paths & local on-device SAF / doc:// authorities:
        assertEquals(
            "/storage/emulated/10/Download",
            SystemFilesHandoff.eligibleParentFolder("/storage/emulated/10/Download/app.apk", primary, mounts),
        )
        assertEquals(
            "/storage/emulated/10",
            SystemFilesHandoff.eligibleParentFolder("/storage/emulated/10/app.apk", primary, mounts),
        )
        assertEquals(
            "/storage/ABCD-1234/apks",
            SystemFilesHandoff.eligibleParentFolder("/storage/ABCD-1234/apks/app.apk", primary, mounts),
        )
        assertEquals(
            "saf://local_dl/builds",
            SystemFilesHandoff.eligibleParentFolder("saf://local_dl/builds/app.apk", primary, mounts),
        )
        assertEquals(
            "saf://linux_vm/out",
            SystemFilesHandoff.eligibleParentFolder("saf://linux_vm/out/app.apk", primary, mounts),
        )
        assertEquals(
            "doc://com.android.externalstorage.documents/primary:Download",
            SystemFilesHandoff.eligibleParentFolder(
                "doc://com.android.externalstorage.documents/primary:Download/app.apk",
                primary,
                mounts,
            ),
        )
        assertEquals(
            "doc://com.android.providers.downloads.documents/downloads",
            SystemFilesHandoff.eligibleParentFolder(
                "doc://com.android.providers.downloads.documents/downloads/app.apk",
                primary,
                mounts,
            ),
        )

        // Rejected cloud SAF/doc, connected phones (phone://, QuickAccess, MTP), network shares, archives, private paths:
        assertNull(SystemFilesHandoff.eligibleParentFolder("saf://gdrive/builds/app.apk", primary, mounts))
        assertNull(SystemFilesHandoff.eligibleParentFolder("saf://onedrive/builds/app.apk", primary, mounts))
        assertNull(SystemFilesHandoff.eligibleParentFolder("saf://phone_qa/Download/app.apk", primary, mounts))
        assertNull(SystemFilesHandoff.eligibleParentFolder("saf://unmounted/builds/app.apk", primary, mounts))
        assertNull(
            SystemFilesHandoff.eligibleParentFolder(
                "doc://com.google.android.apps.docs.storage/folder/app.apk",
                primary,
                mounts,
            ),
        )
        assertNull(
            SystemFilesHandoff.eligibleParentFolder(
                "doc://com.google.ambient.streaming.documents/dev1//Download/app.apk",
                primary,
                mounts,
            ),
        )
        assertNull(
            SystemFilesHandoff.eligibleParentFolder(
                "doc://com.android.mtp.documents/1/Download/app.apk",
                primary,
                mounts,
            ),
        )
        assertNull(SystemFilesHandoff.eligibleParentFolder("phone://pixel/Download/app.apk", primary, mounts))
        assertNull(SystemFilesHandoff.eligibleParentFolder("doc://com.example.cloud/folder/app.apk", primary, mounts))
        assertNull(SystemFilesHandoff.eligibleParentFolder("smb://nas/share/app.apk", primary, mounts))
        assertNull(SystemFilesHandoff.eligibleParentFolder("webdav://host/files/app.apk", primary, mounts))
        assertNull(SystemFilesHandoff.eligibleParentFolder("ftp://host/app.apk", primary, mounts))
        assertNull(SystemFilesHandoff.eligibleParentFolder("onedrive://acc/app.apk", primary, mounts))
        assertNull(SystemFilesHandoff.eligibleParentFolder("/storage/emulated/10/bundle.zip!/app.apk", primary, mounts))
        assertNull(SystemFilesHandoff.eligibleParentFolder("/data/user/10/com.droid.wayfinder/files/app.apk", primary, mounts))
        assertNull(SystemFilesHandoff.eligibleParentFolder("/storage/emulated/0/Download/app.apk", primary, mounts))
        assertNull(SystemFilesHandoff.eligibleParentFolder("/storage/self/primary/app.apk", primary, mounts))
        assertNull(SystemFilesHandoff.eligibleParentFolder("/storage/emulated/10/Download/app.apk\u0000", primary, mounts))
    }

    @Test
    fun selectPrivilegedFilesHandlerRequiresManageDocumentsExportedAndCallerPermitted() {
        val selfPkg = "com.droid.wayfinder"

        // Unprivileged 3P apps, non-exported activities, or signature-permission-gated activities are rejected.
        val ineligible = listOf(
            FilesActivityCandidate("com.thirdparty.explorer", "com.thirdparty.explorer.Main", holdsManageDocuments = false),
            FilesActivityCandidate("com.google.android.documentsui", "com.android.documentsui.PrivateActivity", holdsManageDocuments = true, exported = false),
            FilesActivityCandidate("com.google.android.documentsui", "com.android.documentsui.GatedAlias", holdsManageDocuments = true, exported = true, callerHasActivityPermission = false),
            FilesActivityCandidate(selfPkg, "com.droid.wayfinder.DocumentsBrowseAlias", holdsManageDocuments = true),
        )
        assertNull(SystemFilesHandoff.selectPrivilegedFilesHandler(ineligible, selfPkg))

        // Prefers exported, caller-callable com.google.android.documentsui over com.android.documentsui over OEM package.
        val mixed = ineligible + listOf(
            FilesActivityCandidate("com.oem.systemfiles", "com.oem.systemfiles.Main", holdsManageDocuments = true),
            FilesActivityCandidate("com.android.documentsui", "com.android.documentsui.files.FilesActivity", holdsManageDocuments = true),
            FilesActivityCandidate("com.google.android.documentsui", "com.android.documentsui.files.FilesActivity", holdsManageDocuments = true),
        )
        val chosen = SystemFilesHandoff.selectPrivilegedFilesHandler(mixed, selfPkg)
        assertNotNull(chosen)
        assertEquals("com.google.android.documentsui", chosen!!.packageName)
        assertEquals("com.android.documentsui.files.FilesActivity", chosen.activityName)

        // Without Google DocumentsUI, prefers AOSP com.android.documentsui over generic OEM package.
        val withoutGoogleDocUi = mixed.filterNot {
            it.packageName == "com.google.android.documentsui" && it.exported && it.callerHasActivityPermission
        }
        assertEquals(
            "com.android.documentsui",
            SystemFilesHandoff.selectPrivilegedFilesHandler(withoutGoogleDocUi, selfPkg)?.packageName,
        )

        // Blank or NUL-injected names are rejected even with MANAGE_DOCUMENTS.
        val malformed = listOf(
            FilesActivityCandidate("", "com.example.Main", holdsManageDocuments = true),
            FilesActivityCandidate("com.google.android.documentsui", "", holdsManageDocuments = true),
            FilesActivityCandidate("com.google.android.documentsui\u0000", "com.example.Main", holdsManageDocuments = true),
        )
        assertNull(SystemFilesHandoff.selectPrivilegedFilesHandler(malformed, selfPkg))
    }

    @Test
    fun isPackageInstallerPackageMatchesPlatformInstallersOnly() {
        assertTrue(SystemFilesHandoff.isPackageInstallerPackage("com.android.packageinstaller"))
        assertTrue(SystemFilesHandoff.isPackageInstallerPackage("com.google.android.packageinstaller"))
        assertTrue(SystemFilesHandoff.isPackageInstallerPackage("COM.GOOGLE.ANDROID.PACKAGEINSTALLER"))
        assertFalse(SystemFilesHandoff.isPackageInstallerPackage("com.droid.wayfinder"))
        assertFalse(SystemFilesHandoff.isPackageInstallerPackage("sk.styk.martin.apkanalyzer"))
        assertFalse(SystemFilesHandoff.isPackageInstallerPackage(null))
    }

    @Test
    fun fuzzPrivilegedHandlerSelectionAndPathEligibility() {
        val rng = Random(2026112802L)
        val selfPkg = "com.droid.wayfinder"
        val samplePkgs = listOf(
            selfPkg,
            "com.google.android.documentsui",
            "com.android.documentsui",
            "com.thirdparty.manager",
            "com.oem.files",
            "",
            "bad\u0000pkg",
        )
        repeat(1_000) {
            val candidates = List(rng.nextInt(0, 8)) {
                FilesActivityCandidate(
                    packageName = samplePkgs[rng.nextInt(samplePkgs.size)],
                    activityName = if (rng.nextInt(5) == 0) "" else "Activity${rng.nextInt(10)}",
                    holdsManageDocuments = rng.nextBoolean(),
                    exported = rng.nextBoolean(),
                    callerHasActivityPermission = rng.nextBoolean(),
                )
            }
            val selected = SystemFilesHandoff.selectPrivilegedFilesHandler(candidates, selfPkg)
            if (selected != null) {
                assertTrue(selected.holdsManageDocuments)
                assertTrue(selected.exported)
                assertTrue(selected.callerHasActivityPermission)
                assertTrue(selected.packageName != selfPkg)
                assertTrue(selected.packageName.isNotBlank())
                assertTrue(selected.activityName.isNotBlank())
                assertFalse(selected.packageName.contains('\u0000'))
            }
        }
    }
}
