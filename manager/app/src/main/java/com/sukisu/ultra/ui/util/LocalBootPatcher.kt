package com.sukisu.ultra.ui.util

import android.system.Os
import android.util.Log
import com.sukisu.ultra.ksuApp
import java.io.File

private const val TAG = "SukigiskPatch"

/**
 * Magisk's own boot patcher, executed inside the app sandbox.
 *
 * The grafted backend can only patch an image on a device where Magisk is
 * already installed: its `boot-patch` command runs
 * `/data/adb/magisk/boot_patch.sh`, and that directory does not exist yet on a
 * stock, unrooted phone. Magisk's own app solves this by shipping the installer
 * with the APK and running it from app-private storage, never touching
 * `/data/adb`. This object does the same.
 *
 * Layout in [ksuApp.cacheDir]/bootpatch while patching:
 *   boot_patch.sh      copied from assets/magisk
 *   util_functions.sh  copied from assets/magisk
 *   magisk             libmagisk.so     (data: compressed into the ramdisk)
 *   magiskinit         libmagiskinit.so (data: replaces /init in the ramdisk)
 *   init-ld            libinit-ld.so    (data: LD_PRELOAD of /init)
 *
 * Only magiskboot has to be *executed*, and binaries under the app's
 * nativeLibraryDir are the one place Android still allows an app to exec, so
 * the script is pointed at libmagiskboot.so directly through $MAGISKBOOT.
 */
object LocalBootPatcher {

    private const val ASSET_DIR = "magisk"
    private const val WORK_DIR = "bootpatch"

    /** data files copied out of nativeLibraryDir: source name -> name in the work dir */
    private val DATA_FILES = listOf(
        "libmagisk.so" to "magisk",
        "libmagiskinit.so" to "magiskinit",
        "libinit-ld.so" to "init-ld",
    )

    private val SCRIPTS = listOf("boot_patch.sh", "util_functions.sh")

    fun isAvailable(): Boolean {
        val native = File(ksuApp.applicationInfo.nativeLibraryDir)
        if (!File(native, "libmagiskboot.so").isFile) return false
        if (!File(native, "libmagisk.so").isFile) return false
        if (!File(native, "libmagiskinit.so").isFile) return false
        return SCRIPTS.all { name ->
            runCatching { ksuApp.assets.open("$ASSET_DIR/$name").use { } }.isSuccess
        }
    }

    fun patch(
        bootImage: File,
        outDir: File,
        outName: String,
        onStdout: (String) -> Unit,
        onStderr: (String) -> Unit,
    ): FlashResult {
        val native = File(ksuApp.applicationInfo.nativeLibraryDir)
        val work = File(ksuApp.cacheDir, WORK_DIR)
        work.deleteRecursively()
        work.mkdirs()

        try {
            for ((src, dst) in DATA_FILES) {
                val from = File(native, src)
                if (!from.isFile) {
                    val msg = "! installer is incomplete: $src missing"
                    onStderr(msg)
                    return FlashResult(1, msg, false)
                }
                from.copyTo(File(work, dst), overwrite = true)
            }
            for (name in SCRIPTS) {
                ksuApp.assets.open("$ASSET_DIR/$name").use { input ->
                    File(work, name).outputStream().use { output -> input.copyTo(output) }
                }
            }

            onStdout("- Using bundled Magisk installer (no root required)")

            val script = File(work, "boot_patch.sh")
            val pb = ProcessBuilder("/system/bin/sh", script.absolutePath, bootImage.absolutePath)
            pb.directory(work)
            pb.redirectErrorStream(true)
            val env = pb.environment()
            env["MAGISKBOOT"] = File(native, "libmagiskboot.so").absolutePath
            env["MAGISK_EXE"] = File(native, "libmagisk.so").absolutePath
            env["BOOTMODE"] = "true"
            env["ASH_STANDALONE"] = "1"
            env["TMPDIR"] = work.absolutePath
            env["PATH"] = "/system/bin:/system/xbin" + (env["PATH"]?.let { ":$it" } ?: "")

            val log = StringBuilder()
            val proc = pb.start()
            proc.inputStream.bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    log.appendLine(line)
                    onStdout(line)
                }
            }
            val code = proc.waitFor()

            val produced = File(work, "new-boot.img")
            if (code != 0 || !produced.isFile) {
                val err = log.toString().trim().ifEmpty { "ERROR: boot_patch.sh exited with $code" }
                return FlashResult(if (code != 0) code else 1, err, false)
            }

            val written = writeOut(produced, outDir, outName, onStdout, onStderr)
            return FlashResult(0, "", false, written)
        } catch (e: Throwable) {
            Log.w(TAG, "patch failed", e)
            val msg = "! ${e.message ?: e.javaClass.simpleName}"
            onStderr(msg)
            return FlashResult(1, msg, false)
        } finally {
            work.deleteRecursively()
        }
    }

    /**
     * Copy the patched image where the user asked for it. /sdcard/Download is
     * FUSE-backed and may refuse an app without full storage access, so fall
     * back to the app's own external directory and report the real path.
     */
    private fun writeOut(
        produced: File,
        outDir: File,
        outName: String,
        onStdout: (String) -> Unit,
        onStderr: (String) -> Unit,
    ): File {
        val candidates = listOfNotNull(
            outDir,
            ksuApp.getExternalFilesDir(null),
        )
        for (dir in candidates) {
            val target = File(dir, outName)
            val ok = runCatching {
                dir.mkdirs()
                produced.copyTo(target, overwrite = true)
                true
            }.getOrDefault(false)
            if (ok) {
                onStdout("- Output: ${target.absolutePath}")
                return target
            }
        }
        val fallback = File(ksuApp.cacheDir, outName)
        produced.copyTo(fallback, overwrite = true)
        onStderr("! Could not write to ${outDir.absolutePath}, kept ${fallback.absolutePath}")
        onStdout("- Output: ${fallback.absolutePath}")
        return fallback
    }
}
