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
    private val ASSET_ROOTS = listOf("Vortek_Layer", "vortek")

    fun syncFromAssetsIfNeeded(context: Context) {
        synchronized(this) {
            val am = context.assets
            val filesDir = context.filesDir
            val vortekDir = File(filesDir, "vortek").apply { mkdirs() }

            try {
                for (assetRoot in ASSET_ROOTS) {
                    val list = am.list(assetRoot) ?: continue
                    val splitParts = mutableListOf<String>()

                    for (file in list) {
                        val dest = File(vortekDir, file)
                        if (isSplitPart(file)) {
                            splitParts.add(file)
                        } else {
                            copyAssetFile(am, "$assetRoot/$file", dest)
                            // Auto-decompress if gz or zip
                            if (file.endsWith(".gz") && !file.endsWith(".tar.gz")) {
                                decompressGz(dest, File(vortekDir, file.removeSuffix(".gz")))
                            } else if (file.endsWith(".zip")) {
                                unzipFile(dest, vortekDir)
                            }
                        }
                    }

                    if (splitParts.isNotEmpty()) {
                        concatenateAssetParts(am, assetRoot, splitParts, vortekDir)
                    }
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
        assetRoot: String,
        parts: List<String>,
        outputDir: File
    ) {
        val sortedParts = parts.sorted()
        val baseName = sortedParts.first().replace(Regex("\\.(part[0-9]+|00[0-9]|[a-z]{2})$"), "")
        val outputFile = File(outputDir, baseName)

        try {
            FileOutputStream(outputFile).use { out ->
                for (part in sortedParts) {
                    am.open("$assetRoot/$part").use { input ->
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
        val layerDir1 = File(rootfs, "usr/share/vulkan/explicit_layer.d").apply { mkdirs() }
        val layerDir2 = File(rootfs, "etc/vulkan/explicit_layer.d").apply { mkdirs() }
        val libDirs = listOf(
            File(rootfs, "usr/lib/aarch64-linux-gnu").apply { mkdirs() },
            File(rootfs, "usr/lib").apply { mkdirs() },
            File(rootfs, "usr/lib64").apply { mkdirs() },
            File(rootfs, "lib").apply { mkdirs() }
        )

        try {
            val allFiles = vortekDir.listFiles() ?: emptyArray()
            val soFiles = allFiles.filter { it.name.endsWith(".so") }

            // Ensure vulkan.samsung.so is also available as libvulkan_vortek.so
            val samsungSo = soFiles.find { it.name == "vulkan.samsung.so" }
            if (samsungSo != null) {
                val vortekTarget = File(vortekDir, "libvulkan_vortek.so")
                if (!vortekTarget.exists()) {
                    samsungSo.copyTo(vortekTarget, overwrite = true)
                }
            } else if (soFiles.isNotEmpty()) {
                val primarySo = soFiles.first()
                val vortekTarget = File(vortekDir, "libvulkan_vortek.so")
                if (!vortekTarget.exists()) {
                    primarySo.copyTo(vortekTarget, overwrite = true)
                }
            }

            // Generate vortek_icd.aarch64.json pointing to the Vulkan driver .so
            val driverSoName = if (File(vortekDir, "vulkan.samsung.so").exists()) "vulkan.samsung.so" else "libvulkan_vortek.so"
            val icdJsonContent = """
                {
                    "file_format_version": "1.0.0",
                    "ICD": {
                        "library_path": "$driverSoName",
                        "api_version": "1.3.0"
                    }
                }
            """.trimIndent()

            File(icdDir1, "vortek_icd.aarch64.json").writeText(icdJsonContent)
            File(icdDir2, "vortek_icd.aarch64.json").writeText(icdJsonContent)

            // Delete any stock/conflicting samsung ICD json files across all ICD directories in rootfs
            listOf(icdDir1, icdDir2, File(rootfs, "vendor/etc/vulkan/icd.d"), File(rootfs, "system/etc/vulkan/icd.d")).forEach { dir ->
                if (dir.exists()) {
                    dir.listFiles()?.filter { it.name.contains("samsung", ignoreCase = true) }?.forEach { it.delete() }
                }
            }

            // Copy layer json and so files
            vortekDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".json") && file.name != "meta.json") {
                    if (file.name.contains("layer", ignoreCase = true)) {
                        file.copyTo(File(layerDir1, file.name), overwrite = true)
                        file.copyTo(File(layerDir2, file.name), overwrite = true)
                    } else if (!file.name.contains("samsung", ignoreCase = true)) {
                        file.copyTo(File(icdDir1, file.name), overwrite = true)
                        file.copyTo(File(icdDir2, file.name), overwrite = true)
                    }
                } else if (file.name.endsWith(".so")) {
                    for (dir in libDirs) {
                        file.copyTo(File(dir, file.name), overwrite = true)
                    }
                }
            }

            // Copy libsbwchelper.so, liblog.so and essential Android system/vendor libraries if available to resolve driver dependencies
            val sysVendorSearchPaths = listOf(
                "/vendor/lib64", "/vendor/lib",
                "/system/lib64", "/system/lib",
                "/vendor/lib64/egl", "/vendor/lib64/hw",
                "/system/lib64/hw", "/apex/com.android.runtime/lib64"
            )
            val neededLibs = listOf(
                "libsbwchelper.so", "liblog.so", "libhardware.so", "libcutils.so",
                "libutils.so", "libvndksupport.so", "libion.so", "libsync.so",
                "libandroid.so", "libui.so", "libgui.so", "libbase.so"
            )

            for (searchPath in sysVendorSearchPaths) {
                val dir = File(searchPath)
                if (!dir.exists() || !dir.isDirectory) continue
                dir.listFiles()?.forEach { sysFile ->
                    val name = sysFile.name
                    if (neededLibs.contains(name) || name.startsWith("libsbwc") || name.startsWith("libsec")) {
                        for (destDir in libDirs) {
                            try {
                                sysFile.copyTo(File(destDir, name), overwrite = true)
                            } catch (_: Exception) { }
                        }
                    }
                }
            }

            // Ensure bash.bashrc enforces Vortek environment in every interactive shell session
            val bashrc = File(rootfs, "etc/bash.bashrc")
            if (bashrc.exists()) {
                val vortekEnvBlock = """
                    # VORTEK DRIVER CONFIGURATION
                    rm -f /usr/share/vulkan/icd.d/*samsung*.json /etc/vulkan/icd.d/*samsung*.json 2>/dev/null || true
                    export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/vortek_icd.aarch64.json
                    export VK_DRIVER_FILES=/usr/share/vulkan/icd.d/vortek_icd.aarch64.json
                    export VK_INSTANCE_LAYERS=VK_LAYER_VORTEK_XCLIPSE
                    export VK_LAYER_PATH=/usr/share/vulkan/explicit_layer.d:/etc/vulkan/explicit_layer.d
                    export LD_LIBRARY_PATH=/usr/lib/aarch64-linux-gnu:/usr/lib:/lib:/system/lib64:/vendor/lib64:/vendor/lib64/egl:/vendor/lib64/hw:${'$'}LD_LIBRARY_PATH
                """.trimIndent()
                var currentText = bashrc.readText()
                if (currentText.contains("VK_ICD_FILENAMES")) {
                    currentText = currentText.replace("VK_ICD_FILENAMES", "OLD_VK_ICD_FILENAMES_REMOVED")
                }
                currentText += "\n" + vortekEnvBlock + "\n"
                bashrc.writeText(currentText)
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


