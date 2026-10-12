package com.landroverdash.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.widget.Toast
import java.io.File

/**
 * A minimal file browser for picking a firmware .bin, built in because
 * head-unit ROMs often strip Android's system picker (ATOTO does) and their
 * file-manager apps don't answer the standard "pick a file" request.
 *
 * Lists every storage root the unit has mounted — internal storage, USB
 * drives, SD cards — then folders and .bin files. Needs storage permission,
 * which the caller obtains first.
 */
class StorageBrowser(
    private val activity: Activity,
    private val onPicked: (File) -> Unit
) {
    private data class Root(val label: String, val dir: File)

    fun showRoots() {
        val roots = findRoots()
        if (roots.isEmpty()) {
            toast("No readable storage found. Check the USB drive is plugged in.")
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("Choose storage")
            .setItems(roots.map { "▸  ${it.label}" }.toTypedArray()) { _, i ->
                showDir(roots[i].dir, roots[i])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDir(dir: File, root: Root) {
        val entries = dir.listFiles()
        if (entries == null) {
            toast("Can't read ${dir.path} — storage permission may be missing")
            showRoots()
            return
        }
        val dirs = entries.filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }
        // Newest first: the build you just exported is almost always the one.
        val bins = entries.filter { it.isFile && it.name.endsWith(".bin", ignoreCase = true) }
            .sortedByDescending { it.lastModified() }

        val labels  = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        labels += "⬆  Up"
        actions += {
            if (dir.canonicalPath == root.dir.canonicalPath) showRoots()
            else dir.parentFile?.let { showDir(it, root) } ?: showRoots()
        }
        dirs.forEach { d ->
            labels += "📁  ${d.name}"
            actions += { showDir(d, root) }
        }
        bins.forEach { f ->
            labels += "⚙  ${f.name}    ${f.length() / 1024} KB"
            actions += { onPicked(f) }
        }
        if (dirs.isEmpty() && bins.isEmpty()) {
            labels += "(no folders or .bin files here)"
            actions += { showDir(dir, root) }
        }

        val where = dir.path.removePrefix(root.dir.path).ifEmpty { "/" }
        AlertDialog.Builder(activity)
            .setTitle("${root.label}  $where")
            .setItems(labels.toTypedArray()) { _, i -> actions[i]() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Storage roots, found three ways because head units don't agree on where
     * a USB stick lands:
     *  1. StorageManager volumes (the official list, with proper names);
     *  2. anything else mounted directly under /storage;
     *  3. mount points common on Chinese head-unit ROMs.
     */
    private fun findRoots(): List<Root> {
        val seen = LinkedHashMap<String, Root>()
        fun add(label: String, dir: File?) {
            if (dir == null || !dir.isDirectory || !dir.canRead()) return
            val key = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
            if (key !in seen) seen[key] = Root(label, dir)
        }

        val sm = activity.getSystemService(StorageManager::class.java)
        sm?.storageVolumes?.forEach { v -> add(v.getDescription(activity), volumeDir(v)) }

        // The primary volume, in case the lookup above came back empty.
        @Suppress("DEPRECATION")
        add("Internal storage", Environment.getExternalStorageDirectory())

        File("/storage").listFiles()?.forEach { f ->
            if (f.name != "self" && f.name != "emulated") add("Storage: ${f.name}", f)
        }

        listOf("/mnt/usb", "/mnt/udisk", "/mnt/usbhost", "/mnt/usbotg",
               "/storage/usbotg", "/storage/udisk", "/mnt/media_rw").forEach { p ->
            val d = File(p)
            val kids = d.listFiles()?.filter { it.isDirectory }
            if (!kids.isNullOrEmpty()) kids.forEach { add("USB: ${it.name}", it) }
            else add("USB: ${d.name}", d)
        }
        return seen.values.toList()
    }

    /** A volume's mount directory. Public from API 30; on Android 10 the same
     *  information is only reachable through hidden getters. */
    private fun volumeDir(v: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return v.directory
        return runCatching { v.javaClass.getMethod("getPathFile").invoke(v) as? File }.getOrNull()
            ?: runCatching {
                (v.javaClass.getMethod("getPath").invoke(v) as? String)?.let { File(it) }
            }.getOrNull()
    }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
}
