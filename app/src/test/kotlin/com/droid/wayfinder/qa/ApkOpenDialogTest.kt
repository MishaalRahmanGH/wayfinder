// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import android.Manifest
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.MainActivity
import com.droid.wayfinder.PrivilegedDocumentsAlias
import com.droid.wayfinder.data.SafFileSystem
import com.droid.wayfinder.data.SafMount
import com.droid.wayfinder.data.SystemDocumentsBridge
import com.droid.wayfinder.data.SystemFilesHandoff
import com.droid.wayfinder.ui.AppRoot
import com.droid.wayfinder.ui.Dialog
import com.droid.wayfinder.ui.UiEvent
import com.droid.wayfinder.ui.theme.WayfinderTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-mdpi")
class ApkOpenDialogTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var harness: VmHarness? = null

    @Before fun setUp() {
        runCatching {
            val field = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
            (field.get(null) as? MutableMap<*, *>)?.clear()
        }
    }

    @After fun tearDown() { harness?.close(); harness = null }

    private fun launch(setup: VmHarness.() -> Unit = {}): VmHarness {
        val h = VmHarness().apply(setup); harness = h
        rule.setContent { WayfinderTheme(dark = false) { AppRoot(h.vm) } }
        rule.waitForIdle(); h.settle(); rule.waitForIdle()
        return h
    }

    private fun idle() { rule.waitForIdle(); harness?.settle(); rule.waitForIdle() }

    private fun registerDirHandler(
        pkg: String,
        activityName: String,
        grantManageDocuments: Boolean,
        action: String = Intent.ACTION_VIEW,
        mimeType: String? = DocumentsContract.Document.MIME_TYPE_DIR,
        exported: Boolean = true,
        requiredActivityPermission: String? = null,
    ) {
        val pm = shadowOf(rule.activity.packageManager)
        val appInfo = ApplicationInfo().apply { packageName = pkg }
        val actInfo = ActivityInfo().apply {
            packageName = pkg
            name = activityName
            this.exported = exported
            this.permission = requiredActivityPermission
            applicationInfo = appInfo
        }
        val pkgInfo = PackageInfo().apply {
            packageName = pkg
            applicationInfo = appInfo
            requestedPermissions = if (grantManageDocuments) arrayOf(Manifest.permission.MANAGE_DOCUMENTS) else emptyArray()
            requestedPermissionsFlags = if (grantManageDocuments) intArrayOf(PackageInfo.REQUESTED_PERMISSION_GRANTED) else IntArray(0)
        }
        pm.installPackage(pkgInfo)
        val component = ComponentName(pkg, activityName)
        pm.addOrUpdateActivity(actInfo)
        val filter = IntentFilter(action).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataScheme("content")
            if (mimeType != null) addDataType(mimeType)
        }
        pm.addIntentFilterForActivity(component, filter)
    }

    private fun registerApkViewerOrInstaller(pkg: String, activityName: String) {
        val pm = shadowOf(rule.activity.packageManager)
        val appInfo = ApplicationInfo().apply { packageName = pkg }
        val actInfo = ActivityInfo().apply {
            packageName = pkg
            name = activityName
            exported = true
            applicationInfo = appInfo
        }
        val pkgInfo = PackageInfo().apply {
            packageName = pkg
            applicationInfo = appInfo
        }
        pm.installPackage(pkgInfo)
        val component = ComponentName(pkg, activityName)
        pm.addOrUpdateActivity(actInfo)
        val filter = IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataScheme("content")
            addDataType(MainActivity.APK_MIME)
        }
        pm.addIntentFilterForActivity(component, filter)
    }

    @Test fun openingAndOpenWithOnLocalApkShowDisclosureLaunchNewDocumentFilesAndExcludeInstallersInChooser() {
        val h = launch {
            file("Download/app-release.apk", "apk")
            navigate(pathOf("Download"))
            select("Download/app-release.apk")
        }
        val apkEntry = h.s.active.entries.single { it.name == "app-release.apk" }

        // Double-clicking / pressing Enter on an APK in its folder opens the disclosure dialog (no silent OpenFile).
        h.vm.open(apkEntry); idle()
        val dialog = h.s.dialog as? Dialog.ApkInstallUnsupported
        assertNotNull("Expected ApkInstallUnsupported dialog", dialog)
        assertEquals("app-release.apk", dialog!!.fileName)
        assertNotNull("Local APK should provide containing folder URI", dialog.folderUri)
        assertTrue(h.events.none { it is UiEvent.OpenFile })

        rule.onNodeWithText("App installation isn\u2019t supported").assertIsDisplayed()
        rule.onNodeWithText("Open in Files").assertIsDisplayed()
        rule.onNodeWithText("Open with another app\u2026").assertIsDisplayed()

        // Clicking "Close" dismisses the dialog cleanly.
        rule.onNodeWithText("Close").performClick(); idle()
        assertNull(h.s.dialog)

        // "Open with..." on an APK also displays the disclosure dialog with both handoff and non-installer chooser options.
        h.vm.openWith(); idle()
        assertTrue(h.s.dialog is Dialog.ApkInstallUnsupported)
        assertTrue(h.events.none { it is UiEvent.OpenFile })

        // Register both an unprivileged 3P manager and privileged system Files app (DocumentsUI).
        registerDirHandler("com.thirdparty.files", "com.thirdparty.files.DirActivity", grantManageDocuments = false)
        registerDirHandler("com.google.android.documentsui", "com.android.documentsui.files.FilesActivity", grantManageDocuments = true)

        val shadowActivity = shadowOf(rule.activity)
        while (shadowActivity.nextStartedActivity != null) { /* clear previous intents */ }

        // Clicking "Open in Files" dismisses the dialog and launches ONLY the MANAGE_DOCUMENTS system Files app in a new document window.
        rule.onNodeWithText("Open in Files").performClick(); idle()
        assertNull(h.s.dialog)
        val launched = shadowActivity.nextStartedActivity
        assertNotNull("Expected system Files activity to launch", launched)
        assertEquals(Intent.ACTION_VIEW, launched!!.action)
        assertEquals(DocumentsContract.Document.MIME_TYPE_DIR, launched.type)
        assertEquals("com.google.android.documentsui", launched.component?.packageName)
        assertEquals("com.android.documentsui.files.FilesActivity", launched.component?.className)
        assertEquals(0, launched.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue((launched.flags and Intent.FLAG_ACTIVITY_NEW_DOCUMENT) != 0)

        // Clicking "Open with another app…" launches ACTION_CHOOSER excluding MainActivity and system PackageInstaller.
        registerApkViewerOrInstaller("com.google.android.packageinstaller", "com.android.packageinstaller.InstallStart")
        registerApkViewerOrInstaller("com.android.packageinstaller", "com.android.packageinstaller.PackageInstallerActivity")
        registerApkViewerOrInstaller("com.example.apkanalyzer", "com.example.apkanalyzer.InspectActivity")
        while (shadowActivity.nextStartedActivity != null) { /* clear */ }

        h.vm.openWith(); idle()
        rule.onNodeWithText("Open with another app\u2026").performClick(); idle()
        h.until(what = "non-installer chooser") { h.events.any { it is UiEvent.OpenFile && it.excludePackageInstallers } }
        idle()
        assertNull(h.s.dialog)
        val chooser = shadowActivity.nextStartedActivity
        assertNotNull("Expected ACTION_CHOOSER for non-installer APK tools", chooser)
        assertEquals(Intent.ACTION_CHOOSER, chooser!!.action)
        @Suppress("DEPRECATION")
        val excluded = (chooser.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS) ?: emptyArray())
            .filterIsInstance<ComponentName>()
            .toSet()
        assertTrue(ComponentName(rule.activity, MainActivity::class.java) in excluded)
        assertTrue(ComponentName("com.google.android.packageinstaller", "com.android.packageinstaller.InstallStart") in excluded)
        assertTrue(ComponentName("com.android.packageinstaller", "com.android.packageinstaller.PackageInstallerActivity") in excluded)
        assertFalse(ComponentName("com.example.apkanalyzer", "com.example.apkanalyzer.InspectActivity") in excluded)
    }

    @Test fun buildOpenFolderIntentSkipsUnexportedOrPermissionGatedActivitiesAndFallsBackCleanly() {
        launch()
        val folderUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload")

        // Only a 3P file manager without MANAGE_DOCUMENTS -> must return null.
        registerDirHandler("com.thirdparty.manager", "com.thirdparty.manager.Main", grantManageDocuments = false)
        assertNull(SystemFilesHandoff.buildOpenFolderIntent(rule.activity, folderUri))

        // Wayfinder itself with MANAGE_DOCUMENTS -> still excluded.
        shadowOf(rule.activity.application).grantPermissions(Manifest.permission.MANAGE_DOCUMENTS)
        PrivilegedDocumentsAlias.sync(rule.activity)
        assertNull(SystemFilesHandoff.buildOpenFolderIntent(rule.activity, folderUri))
        shadowOf(rule.activity.application).denyPermissions(Manifest.permission.MANAGE_DOCUMENTS)
        PrivilegedDocumentsAlias.sync(rule.activity)

        // Probe 1 (ACTION_VIEW) matches only a signature-permission-gated or unexported DocumentsUI activity,
        // while Probe 2/3 (ACTION_BROWSE) matches an exported callable DocumentsUI activity -> falls back cleanly!
        registerDirHandler(
            "com.google.android.documentsui",
            "com.android.documentsui.GatedViewActivity",
            grantManageDocuments = true,
            action = Intent.ACTION_VIEW,
            mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
            exported = true,
            requiredActivityPermission = "com.oem.permission.LAUNCH_FILE_MANAGER",
        )
        registerDirHandler(
            "com.oem.files",
            "com.oem.files.InternalViewActivity",
            grantManageDocuments = true,
            action = Intent.ACTION_VIEW,
            mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
            exported = false,
        )
        registerDirHandler(
            "com.google.android.documentsui",
            "com.android.documentsui.files.FilesActivity",
            grantManageDocuments = true,
            action = SystemFilesHandoff.ACTION_BROWSE,
            mimeType = null,
            exported = true,
            requiredActivityPermission = null,
        )
        val browseIntent = SystemFilesHandoff.buildOpenFolderIntent(rule.activity, folderUri)
        assertNotNull(browseIntent)
        assertEquals(SystemFilesHandoff.ACTION_BROWSE, browseIntent!!.action)
        assertEquals("com.google.android.documentsui", browseIntent.component?.packageName)
        assertEquals("com.android.documentsui.files.FilesActivity", browseIntent.component?.className)
        assertFalse((browseIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertTrue((browseIntent.flags and Intent.FLAG_ACTIVITY_NEW_DOCUMENT) != 0)
    }

    @Test fun pinnedOpaqueDocApkSafDocumentResolutionPrimaryColonFixAndCloudPhoneSuppression() {
        val downloadsAuth = "com.android.providers.downloads.documents"
        Robolectric.buildContentProvider(FakeOpaqueDownloadsProvider::class.java).create(
            ProviderInfo().apply {
                authority = downloadsAuth
                exported = true
                grantUriPermissions = true
            },
        )
        val h = launch()

        // 1. Pinned opaque doc://.../msf:10492 APK (where FsPath.name would be "msf:10492" without stat displayName)
        // must be intercepted by ApkInstallUnsupportedDialog and NEVER staged into cacheDir/remote-open.
        val opaqueApkPath = "doc://$downloadsAuth/msf:10492"
        h.vm.openPinned(opaqueApkPath)
        h.until(what = "pinned opaque apk disclosure") { h.s.dialog is Dialog.ApkInstallUnsupported }
        idle()
        val pinnedDialog = h.s.dialog as? Dialog.ApkInstallUnsupported
        assertNotNull("Opaque pinned doc:// APK must show disclosure dialog", pinnedDialog)
        assertEquals("pinned-update.apk", pinnedDialog!!.fileName)
        assertTrue(h.events.none { it is UiEvent.OpenFile })
        val remoteOpenDir = File(h.app.cacheDir, "remote-open")
        assertFalse(
            "Opaque pinned APK must not be staged before user chooses non-installer open",
            remoteOpenDir.exists() && remoteOpenDir.walkTopDown().any { it.isFile },
        )
        h.vm.dismissDialog(); idle()

        // 2. SystemDocumentsBridge.toDocumentsContractUri fixes baseDocId ending with ':' ("primary:" -> "primary:Download").
        val primaryTreeMount = SafMount(
            id = "prim",
            label = "Internal",
            uri = "content://com.android.externalstorage.documents/tree/primary%3A",
        )
        val fixedUri = SystemDocumentsBridge.toDocumentsContractUri(
            path = "saf://prim/Download",
            tree = false,
            safMounts = listOf(primaryTreeMount),
        )
        assertNotNull(fixedUri)
        assertEquals("primary:Download", DocumentsContract.getDocumentId(fixedUri))

        // 3. SAF folder handoff resolves opaque provider document IDs via SafFileSystem.documentUriOf (e.g. msd:42).
        val dlSafMount = SafMount(
            id = "dl_saf",
            label = "Downloads",
            uri = "content://$downloadsAuth/tree/downloads",
        )
        val safFs = SafFileSystem(h.app) { listOf(dlSafMount) }
        val resolvedSafFolder = SystemFilesHandoff.folderDocumentsUriForApk(
            apkPath = "saf://dl_saf/Releases/pinned-update.apk",
            safMounts = listOf(dlSafMount),
            primaryStorageRoot = h.root.path,
            resolveSafDocumentUri = safFs::documentUriOf,
        )
        assertNotNull(resolvedSafFolder)
        assertEquals("msd:42", DocumentsContract.getDocumentId(resolvedSafFolder))

        // 4. Cloud Drive & Cross-Device phone doc:// / saf:// APKs return null folderUri (no "Open in Files").
        val driveSafMount = SafMount(
            id = "drive_saf",
            label = "Drive",
            uri = "content://com.google.android.apps.docs.storage/tree/acc%3Broot",
        )
        assertNull(
            SystemFilesHandoff.folderDocumentsUriForApk(
                apkPath = "saf://drive_saf/Releases/app.apk",
                safMounts = listOf(driveSafMount),
                primaryStorageRoot = h.root.path,
            ),
        )
        assertNull(
            SystemFilesHandoff.folderDocumentsUriForApk(
                apkPath = "doc://com.google.ambient.streaming.documents/dev1//Download/app.apk",
                primaryStorageRoot = h.root.path,
            ),
        )

        h.vm.showDialog(Dialog.ApkInstallUnsupported(fileName = "remote.apk", folderUri = null))
        idle()
        rule.onNodeWithText("App installation isn\u2019t supported").assertIsDisplayed()
        rule.onNodeWithText("Open in Files").assertDoesNotExist()
        rule.onNodeWithText("Close").performClick(); idle()
        assertNull(h.s.dialog)
    }

    class FakeOpaqueDownloadsProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SUMMARY,
            )
            val cursor = MatrixCursor(cols)
            val pathStr = uri.toString()
            when {
                pathStr.contains("/children") && pathStr.contains("downloads") -> {
                    cursor.addRow(arrayOf<Any?>("msd:42", "Releases", DocumentsContract.Document.MIME_TYPE_DIR, 0L, 1000L, null))
                }
                pathStr.contains("/children") && pathStr.contains("msd%3A42") -> {
                    cursor.addRow(arrayOf<Any?>("msf:10492", "pinned-update.apk", MainActivity.APK_MIME, 4096L, 2000L, null))
                }
                else -> {
                    val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
                    when (docId) {
                        "msf:10492" -> cursor.addRow(arrayOf<Any?>("msf:10492", "pinned-update.apk", MainActivity.APK_MIME, 4096L, 2000L, null))
                        "msd:42" -> cursor.addRow(arrayOf<Any?>("msd:42", "Releases", DocumentsContract.Document.MIME_TYPE_DIR, 0L, 1000L, null))
                        "downloads" -> cursor.addRow(arrayOf<Any?>("downloads", "Downloads", DocumentsContract.Document.MIME_TYPE_DIR, 0L, 1000L, null))
                    }
                }
            }
            return cursor
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }
}
