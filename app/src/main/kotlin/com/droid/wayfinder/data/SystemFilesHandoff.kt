// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.util.Locale

/** Candidate activity resolved for a system Files directory-browsing intent. */
data class FilesActivityCandidate(
    val packageName: String,
    val activityName: String,
    val holdsManageDocuments: Boolean,
    val exported: Boolean = true,
    val callerHasActivityPermission: Boolean = true,
)

/**
 * Resolves and constructs explicit intents targeting only the platform's system Files app
 * (which holds [Manifest.permission.MANAGE_DOCUMENTS]) to open the folder containing an APK,
 * and builds chooser exclusion lists for non-installer APK tools.
 *
 * Wayfinder intentionally omits `REQUEST_INSTALL_PACKAGES` and never installs packages. Rather than
 * letting Android's `PackageInstaller` silently abort when a user tries to open an `.apk`, Wayfinder
 * can hand off the containing folder's [DocumentsContract] URI directly to the privileged system
 * Files app. Targeting an exported, caller-callable `MANAGE_DOCUMENTS` component explicitly and
 * omitting [Intent.FLAG_GRANT_READ_URI_PERMISSION] prevents unprivileged third-party file managers
 * from intercepting the intent and avoids `UriGrantsManagerService` `SecurityException` crashes on
 * retail `user` builds.
 */
object SystemFilesHandoff {
    const val ACTION_BROWSE = "android.provider.action.BROWSE"
    const val GOOGLE_DOCUMENTS_UI_PACKAGE = "com.google.android.documentsui"
    const val AOSP_DOCUMENTS_UI_PACKAGE = "com.android.documentsui"

    val PACKAGE_INSTALLER_PACKAGES: Set<String> = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
    )

    /** True when [packageName] belongs to Android's system `PackageInstaller`. */
    fun isPackageInstallerPackage(packageName: String?): Boolean {
        val clean = packageName?.trim()?.lowercase(Locale.US).orEmpty()
        return clean in PACKAGE_INSTALLER_PACKAGES
    }

    /**
     * True when [nameOrPath] or optional [displayName] (e.g. `COLUMN_DISPLAY_NAME` for an opaque
     * `doc://.../msf:10492` document ID) refers to an Android `.apk` package archive.
     */
    fun isApkFile(nameOrPath: String, displayName: String? = null): Boolean {
        if (nameOrPath.contains('\u0000') || displayName?.contains('\u0000') == true) return false
        if (!displayName.isNullOrBlank()) {
            val cleanDisplay = displayName.trim()
            if (cleanDisplay.lowercase(Locale.US).endsWith(".apk") && cleanDisplay.length > 4) {
                return true
            }
        }
        if (nameOrPath.isBlank()) return false
        val name = FsPath.name(nameOrPath).ifEmpty { nameOrPath }.trim()
        return name.lowercase(Locale.US).endsWith(".apk") && name.length > 4
    }

    /** Extracts the authority segment from a `content://<authority>/...` URI string without requiring Android `Uri.parse`. */
    internal fun extractContentAuthority(uriString: String): String? {
        val trimmed = uriString.trim()
        if (!trimmed.startsWith("content://", ignoreCase = true)) return null
        val afterScheme = trimmed.substring("content://".length)
        val rawAuth = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        return rawAuth.takeIf { it.isNotBlank() }
    }

    /** True when [authority] is a local on-device `DocumentsProvider` (excluding Cross-Device / MTP phone authorities and cloud providers). */
    internal fun isEligibleDocumentAuthority(authority: String?): Boolean {
        if (authority.isNullOrBlank()) return false
        val cleanAuth = authority.substringAfter('@').trim()
        return ImageInspector.isLocalDocumentAuthority(cleanAuth) &&
            !SystemDocumentsBridge.isPhoneAuthority(cleanAuth)
    }

    /**
     * Returns the enclosing folder path of [apkPath] when it lives on a local on-device location
     * representable by `DocumentsContract` (shared storage, removable storage, or local on-device
     * `saf://` / `doc://` mount), or `null` for cloud providers, connected phones, network shares,
     * archives, virtual roots, or malformed paths.
     */
    fun eligibleParentFolder(
        apkPath: String,
        primaryStorageRoot: String = runCatching { Environment.getExternalStorageDirectory().path }
            .getOrDefault("/storage/emulated/0"),
        safMounts: List<SafMount> = emptyList(),
    ): String? {
        if (apkPath.isBlank() || apkPath.contains('\u0000')) return null
        val norm = FsPath.normalize(apkPath)
        if (FsPath.isZip(norm) || FsPath.isVirtual(norm) || SystemDocumentsBridge.isPhonePath(norm)) {
            return null
        }
        val parent = FsPath.parent(norm) ?: return null
        if (FsPath.isDoc(norm)) {
            val auth = SystemDocumentsBridge.authorityOf(norm)
            return parent.takeIf { isEligibleDocumentAuthority(auth) }
        }
        if (FsPath.isSaf(norm)) {
            val mountId = norm.removePrefix("saf://").substringBefore('/')
            val mount = safMounts.firstOrNull { it.id == mountId } ?: return null
            val auth = extractContentAuthority(mount.uri)
            return parent.takeIf { isEligibleDocumentAuthority(auth) }
        }
        if (!FsPath.isLocalFile(norm)) return null
        val primaryNorm = FsPath.normalize(primaryStorageRoot)
        if (parent == primaryNorm || parent.startsWith("$primaryNorm/")) return parent
        if (parent.startsWith("/storage/")) {
            val afterStorage = parent.removePrefix("/storage/")
            val volume = afterStorage.substringBefore('/')
            if (volume.isNotBlank() && volume != "emulated" && volume != "self") return parent
        }
        return null
    }

    /**
     * Converts the enclosing folder of [apkPath] into a local on-device [DocumentsContract] document
     * URI suitable for opening in the system Files app (e.g.
     * `content://com.android.externalstorage.documents/document/primary%3ADownload`), resolving
     * `saf://` folders via [resolveSafDocumentUri] (`SafFileSystem.documentUriOf`) so opaque
     * provider document IDs are preserved. Returns `null` when the APK is on cloud/remote/phone
     * storage or cannot be resolved.
     */
    fun folderDocumentsUriForApk(
        apkPath: String,
        safMounts: List<SafMount> = emptyList(),
        primaryStorageRoot: String = runCatching { Environment.getExternalStorageDirectory().path }
            .getOrDefault("/storage/emulated/0"),
        resolveSafDocumentUri: ((String) -> Uri?)? = null,
    ): Uri? {
        val parent = eligibleParentFolder(apkPath, primaryStorageRoot, safMounts) ?: return null
        val uri = if (FsPath.isSaf(parent)) {
            if (resolveSafDocumentUri != null) {
                resolveSafDocumentUri(parent)
            } else {
                SystemDocumentsBridge.toDocumentsContractUri(
                    path = parent,
                    tree = false,
                    safMounts = safMounts,
                    primaryStorageRoot = primaryStorageRoot,
                )
            }
        } else {
            SystemDocumentsBridge.toDocumentsContractUri(
                path = parent,
                tree = false,
                safMounts = safMounts,
                primaryStorageRoot = primaryStorageRoot,
            )
        } ?: return null
        return uri.takeIf {
            it.scheme == "content" && isEligibleDocumentAuthority(it.authority)
        }
    }

    /**
     * Selects the highest-priority exported, caller-callable candidate activity whose package holds
     * [Manifest.permission.MANAGE_DOCUMENTS] and does not belong to [selfPackageName].
     * Pure JVM logic for deterministic unit testing.
     */
    fun selectPrivilegedFilesHandler(
        candidates: List<FilesActivityCandidate>,
        selfPackageName: String,
    ): FilesActivityCandidate? {
        val eligible = candidates.filter { c ->
            c.holdsManageDocuments &&
                c.exported &&
                c.callerHasActivityPermission &&
                c.packageName.isNotBlank() &&
                c.activityName.isNotBlank() &&
                !c.packageName.contains('\u0000') &&
                !c.activityName.contains('\u0000') &&
                c.packageName != selfPackageName
        }
        if (eligible.isEmpty()) return null
        return eligible.minByOrNull { priorityRank(it.packageName) }
    }

    internal fun priorityRank(packageName: String): Int {
        val pkg = packageName.lowercase(Locale.US)
        return when {
            pkg == GOOGLE_DOCUMENTS_UI_PACKAGE -> 0
            pkg == AOSP_DOCUMENTS_UI_PACKAGE -> 1
            pkg.contains("documentsui") -> 2
            else -> 3
        }
    }

    /**
     * Builds an explicit [Intent] targeting only an exported, caller-callable system Files /
     * DocumentsUI activity whose package holds [Manifest.permission.MANAGE_DOCUMENTS] to open
     * [folderUri] in its own document window ([Intent.FLAG_ACTIVITY_NEW_DOCUMENT]).
     *
     * Deliberately omits [Intent.FLAG_GRANT_READ_URI_PERMISSION]: the target holds
     * `MANAGE_DOCUMENTS` natively, and requesting a URI grant on an un-owned `DocumentsProvider`
     * URI would risk `SecurityException` on retail `user` builds.
     */
    fun buildOpenFolderIntent(context: Context, folderUri: Uri): Intent? {
        if (folderUri.scheme != "content") return null
        val pm = context.packageManager
        val selfPkg = context.packageName
        val probes = listOf(
            Intent(Intent.ACTION_VIEW).setDataAndType(folderUri, DocumentsContract.Document.MIME_TYPE_DIR),
            Intent(ACTION_BROWSE).setDataAndType(folderUri, DocumentsContract.Document.MIME_TYPE_DIR),
            Intent(ACTION_BROWSE, folderUri),
        )
        for (probe in probes) {
            val resolved = runCatching { pm.queryIntentActivities(probe, 0) }.getOrDefault(emptyList())
            val candidates = resolved.mapNotNull { ri ->
                val ai = ri.activityInfo ?: return@mapNotNull null
                val pkg = ai.packageName.orEmpty()
                val cls = ai.name.orEmpty()
                if (pkg.isBlank() || cls.isBlank()) return@mapNotNull null
                val holdsPerm = runCatching {
                    pm.checkPermission(Manifest.permission.MANAGE_DOCUMENTS, pkg) ==
                        PackageManager.PERMISSION_GRANTED
                }.getOrDefault(false)
                val reqActPerm = ai.permission
                val callerPermitted = reqActPerm.isNullOrEmpty() || runCatching {
                    pm.checkPermission(reqActPerm, selfPkg) == PackageManager.PERMISSION_GRANTED
                }.getOrDefault(false)
                FilesActivityCandidate(
                    packageName = pkg,
                    activityName = cls,
                    holdsManageDocuments = holdsPerm,
                    exported = ai.exported,
                    callerHasActivityPermission = callerPermitted,
                )
            }
            val chosen = selectPrivilegedFilesHandler(candidates, selfPkg)
            if (chosen != null) {
                return Intent(probe)
                    .setClassName(chosen.packageName, chosen.activityName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            }
        }
        return null
    }

    /**
     * Collects [ComponentName]s to pass in [Intent.EXTRA_EXCLUDE_COMPONENTS] when opening an `.apk`
     * with non-installer tools (e.g. APK Analyzer, Jadx, or archive viewers), excluding Wayfinder's
     * own activities and system `PackageInstaller` packages (`com.android.packageinstaller`,
     * `com.google.android.packageinstaller`).
     */
    fun excludedApkChooserComponents(
        context: Context,
        targetIntent: Intent,
        selfActivity: ComponentName,
    ): Array<ComponentName> {
        val pm = context.packageManager
        val selfPkg = context.packageName
        val out = LinkedHashSet<ComponentName>()
        out.add(selfActivity)
        val resolved = runCatching { pm.queryIntentActivities(targetIntent, 0) }.getOrDefault(emptyList())
        for (ri in resolved) {
            val ai = ri.activityInfo ?: continue
            val pkg = ai.packageName.orEmpty()
            val cls = ai.name.orEmpty()
            if (pkg.isBlank() || cls.isBlank()) continue
            if (pkg == selfPkg || isPackageInstallerPackage(pkg)) {
                out.add(ComponentName(pkg, cls))
            }
        }
        for (installerPkg in PACKAGE_INSTALLER_PACKAGES) {
            val pkgInfo = runCatching {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(installerPkg, PackageManager.GET_ACTIVITIES)
            }.getOrNull()
            pkgInfo?.activities?.forEach { ai ->
                val cls = ai?.name.orEmpty()
                if (cls.isNotBlank()) {
                    out.add(ComponentName(installerPkg, cls))
                }
            }
        }
        return out.toTypedArray()
    }
}
