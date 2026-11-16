package com.cleantv.app

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * The finished channel list (qualities merged, categories cleaned up) saved on the TV, so Live TV
 * opens straight from it instead of downloading and sorting thousands of channels each launch.
 * A compact binary file: reading it takes a fraction of a second even on a Fire TV Stick.
 */
object ChannelCache {
    private const val VERSION = 1
    private var file: File? = null

    fun init(context: Context) {
        if (file == null) file = File(context.filesDir, "channels.bin")
    }

    val exists: Boolean get() = file?.exists() == true

    /** When the saved list was made (0 if there is none). */
    val savedAt: Long get() = file?.takeIf { it.exists() }?.lastModified() ?: 0L

    fun clear() {
        file?.delete()
    }

    fun write(lib: GroupedLibrary) {
        val f = file ?: return
        val tmp = File(f.parentFile, f.name + ".tmp")
        runCatching {
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 64 * 1024)).use { out ->
                out.writeInt(VERSION)
                out.writeInt(lib.categories.size)
                for (c in lib.categories) {
                    out.writeUTF(c.key); out.writeUTF(c.name); out.writeInt(c.order)
                }
                out.writeInt(lib.channels.size)
                for (g in lib.channels) {
                    out.writeUTF(g.key); out.writeUTF(g.name); out.writeUTF(g.icon ?: ""); out.writeInt(g.order)
                    out.writeInt(g.categoryKeys.size)
                    g.categoryKeys.forEach { out.writeUTF(it) }
                    out.writeInt(g.variants.size)
                    for (v in g.variants) {
                        val s = v.stream
                        out.writeInt(s.id); out.writeUTF(s.name); out.writeUTF(s.categoryId); out.writeUTF(s.icon ?: ""); out.writeInt(s.order)
                        out.writeUTF(v.quality.name); out.writeUTF(v.label)
                        out.writeInt(v.tags.size)
                        v.tags.forEach { out.writeUTF(it) }
                    }
                }
            }
            if (!tmp.renameTo(f)) {
                f.delete(); tmp.renameTo(f)
            }
        }.onFailure { tmp.delete() }
    }

    fun read(): GroupedLibrary? {
        val f = file?.takeIf { it.exists() } ?: return null
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(f), 64 * 1024)).use { inp ->
                if (inp.readInt() != VERSION) return null
                val cats = ArrayList<MergedCategory>()
                repeat(inp.readInt()) { cats += MergedCategory(inp.readUTF(), inp.readUTF(), inp.readInt()) }
                val n = inp.readInt()
                val channels = ArrayList<ChannelGroup>(n)
                repeat(n) {
                    val key = inp.readUTF(); val name = inp.readUTF(); val icon = inp.readUTF().ifEmpty { null }; val order = inp.readInt()
                    val catKeys = LinkedHashSet<String>()
                    repeat(inp.readInt()) { catKeys += inp.readUTF() }
                    val variants = ArrayList<Variant>()
                    repeat(inp.readInt()) {
                        val stream = Stream(inp.readInt(), inp.readUTF(), inp.readUTF(), inp.readUTF().ifEmpty { null }, inp.readInt())
                        val quality = Quality.fromPref(inp.readUTF())
                        val label = inp.readUTF()
                        val tags = ArrayList<String>()
                        repeat(inp.readInt()) { tags += inp.readUTF() }
                        variants += Variant(stream, quality, tags, label)
                    }
                    channels += ChannelGroup(key, name, variants, catKeys, icon, order)
                }
                GroupedLibrary(cats, channels)
            }
        }.getOrNull()
    }

    /** A fingerprint of what you'd see, to tell whether a refresh actually changed anything. */
    fun fingerprint(lib: GroupedLibrary): Int {
        var h = lib.categories.joinToString("|") { it.key + "=" + it.name }.hashCode()
        for (g in lib.channels) h = 31 * h + (g.key + g.name + g.icon + g.variants.joinToString(",") { it.stream.id.toString() }).hashCode()
        return h
    }
}
