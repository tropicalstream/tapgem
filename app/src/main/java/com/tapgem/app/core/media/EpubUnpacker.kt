package com.tapgem.app.core.media

import android.content.Context
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipFile

/**
 * Minimal EPUB reader backend: unzip once into cache, read the OPF spine,
 * return the chapter XHTML files in reading order. Rendered by a WebView.
 */
object EpubUnpacker {

    private const val TAG = "EpubUnpacker"

    fun chapters(context: Context, epub: File): List<File> {
        val root = File(context.cacheDir, "epub/${epub.name.hashCode().toUInt()}_${epub.length()}")
        if (!root.exists()) {
            root.mkdirs()
            runCatching {
                ZipFile(epub).use { zip ->
                    zip.entries().asSequence().forEach { e ->
                        val out = File(root, e.name)
                        if (!out.canonicalPath.startsWith(root.canonicalPath)) return@forEach
                        if (e.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            zip.getInputStream(e).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "unzip failed: ${it.message}"); root.deleteRecursively(); return emptyList() }
        }
        val spine = runCatching { readSpine(root) }.getOrNull().orEmpty()
        if (spine.isNotEmpty()) return spine
        // Fallback: any html in reading-ish order.
        return root.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("xhtml", "html", "htm") }
            .sortedBy { it.path }.toList()
    }

    private fun readSpine(root: File): List<File> {
        val container = File(root, "META-INF/container.xml")
        var opfPath: String? = null
        if (container.exists()) {
            val p = Xml.newPullParser()
            container.inputStream().use { i ->
                p.setInput(i, null)
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType == XmlPullParser.START_TAG && p.name == "rootfile") {
                        opfPath = p.getAttributeValue(null, "full-path"); break
                    }
                }
            }
        }
        val opf = opfPath?.let { File(root, it) }?.takeIf { it.exists() }
            ?: root.walkTopDown().firstOrNull { it.extension == "opf" } ?: return emptyList()
        val base = opf.parentFile ?: root
        val manifest = HashMap<String, String>()
        val spine = ArrayList<String>()
        val p = Xml.newPullParser()
        opf.inputStream().use { i ->
            p.setInput(i, null)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "item" -> {
                        val id = p.getAttributeValue(null, "id"); val href = p.getAttributeValue(null, "href")
                        if (id != null && href != null) manifest[id] = href
                    }
                    "itemref" -> p.getAttributeValue(null, "idref")?.let(spine::add)
                }
            }
        }
        return spine.mapNotNull { id -> manifest[id]?.let { File(base, java.net.URLDecoder.decode(it, "UTF-8")) } }
            .filter { it.exists() }
    }
}
