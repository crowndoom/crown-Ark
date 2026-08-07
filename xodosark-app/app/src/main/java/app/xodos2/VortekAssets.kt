package app.xodos2

import android.content.Context
import android.os.Environment
import app.xodos2.ui.runtime.NativeInstallCoordinator
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

object VortekAssets {
    private const val ASSET_ROOT = "vortek"

    fun syncFromAssetsIfNeeded(context: Context) {
        synchronized(this) {
            val am = context.assets
            val filesDir = context.filesDir
            val vortekDir = File(filesDir, "vortek").apply { mkdirs() }

            try {
                val list = am.list(ASSET_ROOT) ?: emptyArray()
                val splitParts = mutableListOf<String>()

                for (file in list) {
                    val dest = File(vortekDir, file)
                    if (isSplitPart(file)) {
                        splitParts.add(file)
                    } else {
                        copyAssetFile(am, "$ASSET_ROOT/$file", dest)
                        // Auto-decompress if gz or zip
                        if (file.endsWith(".gz") && !file.endsWith(".tar.gz")) {
                            decompressGz(dest, File(vortekDir, file.removeSuffix(".gz")))
                        } else if (file.endsWith(".zip")) {
                            unzipFile(dest, vortekDir)
                        }
                    }
                }

                if (splitParts.isNotEmpty()) {
                    concatenateAssetParts(am, splitParts, vortekDir)
                }
            } catch (_: IOException) { }

            // Also check Download folder on device storage for user-supplied drivers
            importFromExternalStorage(vortekDir)
        }
    }

    private fun isSplitPart(filename: String): Boolean {
        return filename.contains(".so.part") ||
               filename.contains(".so.00") ||
               filename.matches(Regex(".*\\.so\\.[a-z]{2}$")) ||
               filename.matches(Regex(".*\\.so\\.[0-9]{3}$"))
    }

    private fun concatenateAssetParts(
        am: android.content.res.AssetManager,
        parts: List<String>,
        outputDir: File
    ) {
        val sortedParts = parts.sorted()
        val baseName = sortedParts.first().replace(Regex("\\.(part[0-9]+|00[0-9]|[a-z]{2})$"), "")
        val outputFile = File(outputDir, baseName)

        try {
            FileOutputStream(outputFile).use { out ->
                for (part in sortedParts) {
                    am.open("$ASSET_ROOT/$part").use { input ->
                        input.copyTo(out)
                    }
                }
            }
        } catch (_: Exception) { }
    }

    fun importFromExternalStorage(vortekDir: File) {
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val vortekDownloadsDir = File(downloadsDir, "vortek")
            val targetDirs = listOfNotNull(
                if (vortekDownloadsDir.isDirectory) vortekDownloadsDir else null,
                if (downloadsDir.isDirectory) downloadsDir else null
            )

            for (dir in targetDirs) {
                val files = dir.listFiles() ?: continue
                val parts = mutableListOf<File>()

                for (file in files) {
                    if (file.isFile) {
                        if (file.name.endsWith(".so") || file.name.endsWith(".json")) {
                            file.copyTo(File(vortekDir, file.name), overwrite = true)
                        } else if (file.name.endsWith(".so.gz")) {
                            decompressGz(file, File(vortekDir, file.name.removeSuffix(".gz")))
                        } else if (file.name.endsWith(".so.zip") || file.name == "vortek.zip") {
                            unzipFile(file, vortekDir)
                        } else if (isSplitPart(file.name)) {
                            parts.add(file)
                        }
                    }
                }

                if (parts.isNotEmpty()) {
                    val sorted = parts.sortedBy { it.name }
                    val baseName = sorted.first().name.replace(Regex("\\.(part[0-9]+|00[0-9]|[a-z]{2})$"), "")
                    val outputFile = File(vortekDir, baseName)
                    FileOutputStream(outputFile).use { out ->
                        for (partFile in sorted) {
                            FileInputStream(partFile).use { input ->
                                input.copyTo(out)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) { }
    }

    suspend fun downloadVortekDriver(context: Context, downloadUrl: String): Boolean {
        return try {
            val vortekDir = File(context.filesDir, "vortek").apply { mkdirs() }
            val url = URL(downloadUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.connect()

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val filename = downloadUrl.substringAfterLast("/").ifEmpty { "libvulkan_vortek.so" }
                val targetFile = File(vortekDir, filename)
                conn.inputStream.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (filename.endsWith(".gz")) {
                    decompressGz(targetFile, File(vortekDir, filename.removeSuffix(".gz")))
                } else if (filename.endsWith(".zip")) {
                    unzipFile(targetFile, vortekDir)
                }
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    private fun decompressGz(gzFile: File, outFile: File) {
        try {
            FileInputStream(gzFile).use { fis ->
                GZIPInputStream(fis).use { gzis ->
                    FileOutputStream(outFile).use { fos ->
                        gzis.copyTo(fos)
                    }
                }
            }
        } catch (_: Exception) { }
    }

    private fun unzipFile(zipFile: File, outDir: File) {
        try {
            FileInputStream(zipFile).use { fis ->
                ZipInputStream(fis).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val newFile = File(outDir, entry.name)
                        if (!entry.isDirectory) {
                            FileOutputStream(newFile).use { fos ->
                                zis.copyTo(fos)
                            }
                        }
                        entry = zis.nextEntry
                    }
                }
            }
        } catch (_: Exception) { }
    }

    fun installVortekToContainer(context: Context, containerId: Int) {
        syncFromAssetsIfNeeded(context)
        val filesDir = context.filesDir
        val vortekDir = File(filesDir, "vortek")
        if (!vortekDir.isDirectory) return

        val rootfs = NativeInstallCoordinator.containerPath(context, containerId)
        if (!rootfs.isDirectory) return

        val icdDir1 = File(rootfs, "usr/share/vulkan/icd.d").apply { mkdirs() }
        val icdDir2 = File(rootfs, "etc/vulkan/icd.d").apply { mkdirs() }
        val libDirs = listOf(
            File(rootfs, "usr/lib/aarch64-linux-gnu").apply { mkdirs() },
            File(rootfs, "usr/lib").apply { mkdirs() },
            File(rootfs, "usr/lib64").apply { mkdirs() },
            File(rootfs, "lib").apply { mkdirs() }
        )

        try {
            val soFiles = vortekDir.listFiles()?.filter { it.name.endsWith(".so") } ?: emptyList()
            var hasVortekSo = soFiles.any { it.name == "libvulkan_vortek.so" }

            // If libvulkan_vortek.so is missing but another .so is present (e.g. vulkan_samsung.so or vulkan.samsung.so), alias it!
            if (!hasVortekSo && soFiles.isNotEmpty()) {
                val primarySo = soFiles.first()
                val vortekTarget = File(vortekDir, "libvulkan_vortek.so")
                primarySo.copyTo(vortekTarget, overwrite = true)
                hasVortekSo = true
            }

            vortekDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".json")) {
                    file.copyTo(File(icdDir1, file.name), overwrite = true)
                    file.copyTo(File(icdDir2, file.name), overwrite = true)
                } else if (file.name.endsWith(".so")) {
                    for (dir in libDirs) {
                        file.copyTo(File(dir, file.name), overwrite = true)
                    }
                }
            }
        } catch (_: Exception) { }
    }

    private fun copyAssetFile(am: android.content.res.AssetManager, path: String, out: File) {
        out.parentFile?.mkdirs()
        am.open(path).use { input ->
            FileOutputStream(out).use { input.copyTo(it) }
        }
    }
}


