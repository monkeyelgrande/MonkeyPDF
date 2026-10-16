package com.monkeypdf.app

import android.util.Base64
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Formatos de Office que MonkeyPDF sabe mostrar (los modernos, basados en ZIP + XML). */
enum class OfficeFormat(val mime: String, val extension: String) {
    WORD("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
    EXCEL("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
    POWERPOINT("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx");

    companion object {
        private val EXTENSIONS = mapOf(
            "docx" to WORD, "docm" to WORD, "dotx" to WORD, "dotm" to WORD,
            "xlsx" to EXCEL, "xlsm" to EXCEL, "xltx" to EXCEL, "xltm" to EXCEL,
            "pptx" to POWERPOINT, "pptm" to POWERPOINT, "ppsx" to POWERPOINT,
            "ppsm" to POWERPOINT, "potx" to POWERPOINT, "potm" to POWERPOINT,
        )

        private val MIME_TYPES = mapOf(
            WORD.mime to WORD,
            "application/vnd.openxmlformats-officedocument.wordprocessingml.template" to WORD,
            "application/vnd.ms-word.document.macroenabled.12" to WORD,
            "application/vnd.ms-word.template.macroenabled.12" to WORD,
            EXCEL.mime to EXCEL,
            "application/vnd.openxmlformats-officedocument.spreadsheetml.template" to EXCEL,
            "application/vnd.ms-excel.sheet.macroenabled.12" to EXCEL,
            "application/vnd.ms-excel.template.macroenabled.12" to EXCEL,
            POWERPOINT.mime to POWERPOINT,
            "application/vnd.openxmlformats-officedocument.presentationml.slideshow" to POWERPOINT,
            "application/vnd.openxmlformats-officedocument.presentationml.template" to POWERPOINT,
            "application/vnd.ms-powerpoint.presentation.macroenabled.12" to POWERPOINT,
            "application/vnd.ms-powerpoint.slideshow.macroenabled.12" to POWERPOINT,
        )

        /** Todos los tipos MIME aceptados, para el selector de archivos. */
        val ALL_MIME_TYPES: List<String> get() = MIME_TYPES.keys.toList()

        fun fromName(name: String): OfficeFormat? =
            EXTENSIONS[name.substringAfterLast('.', "").lowercase()]

        fun fromMime(mime: String?): OfficeFormat? = mime?.let { MIME_TYPES[it.lowercase()] }
    }
}

/** El archivo es de Office antiguo (.doc, .xls, .ppt) o está cifrado con contraseña. */
class LegacyOfficeException : Exception()

/** El archivo no es un documento de Office que se pueda leer. */
class InvalidOfficeException(message: String) : Exception(message)

/** Relación entre partes del paquete: imágenes, hojas, diapositivas, enlaces... */
class Rel(val id: String, val type: String, val target: String, val external: Boolean)

/**
 * Contenido de un archivo de Office moderno: un ZIP con partes XML (Open Packaging Conventions).
 * Se lee completo en memoria; los vídeos y audios incrustados se descartan.
 */
class OfficePackage private constructor(private val entries: Map<String, ByteArray>) {

    fun bytes(path: String): ByteArray? = entries[path.trimStart('/').lowercase()]

    fun xml(path: String): XmlNode? = bytes(path)?.let { XmlNode.parse(it) }

    /** Relaciones de una parte: `dir/_rels/nombre.rels`, con los destinos ya resueltos. */
    fun rels(partPath: String): Map<String, Rel> {
        val dir = partPath.substringBeforeLast('/', "")
        val name = partPath.substringAfterLast('/')
        val relsPath = if (dir.isEmpty()) "_rels/$name.rels" else "$dir/_rels/$name.rels"
        val root = xml(relsPath) ?: return emptyMap()
        return root.children("Relationship").mapNotNull { r ->
            val id = r["Id"] ?: return@mapNotNull null
            val target = r["Target"] ?: return@mapNotNull null
            val external = r["TargetMode"] == "External"
            id to Rel(id, r["Type"].orEmpty(), if (external) target else resolve(dir, target), external)
        }.toMap()
    }

    /** Ruta de la parte principal del documento (p. ej. `word/document.xml`). */
    fun mainPart(): String? = rels("").values
        .firstOrNull { it.type.endsWith("/officeDocument") && !it.external }?.target

    companion object {
        private const val MAX_TOTAL_BYTES = 400L * 1024 * 1024
        private val SKIPPED = Regex(".*\\.(mp4|m4v|mov|avi|wmv|mpg|mpeg|mkv|webm|mp3|wav|wma|m4a|aac|ogg)$")

        fun read(input: InputStream): OfficePackage {
            val buffered = input.buffered()
            buffered.mark(8)
            val magic = ByteArray(4)
            val read = buffered.read(magic)
            buffered.reset()
            // Los .doc/.xls/.ppt y los documentos cifrados son archivos OLE (D0 CF 11 E0).
            if (read == 4 && magic.contentEquals(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte()))) {
                throw LegacyOfficeException()
            }
            if (read < 4 || magic[0] != 'P'.code.toByte() || magic[1] != 'K'.code.toByte()) {
                throw InvalidOfficeException("No es un archivo ZIP")
            }
            val entries = HashMap<String, ByteArray>()
            var total = 0L
            ZipInputStream(buffered).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.replace('\\', '/').trimStart('/').lowercase()
                    if (entry.isDirectory || SKIPPED.matches(name)) continue
                    val bytes = zip.readBytes()
                    total += bytes.size
                    if (total > MAX_TOTAL_BYTES) throw InvalidOfficeException("Documento demasiado grande")
                    entries[name] = bytes
                }
            }
            if ("[content_types].xml" !in entries) throw InvalidOfficeException("Falta [Content_Types].xml")
            return OfficePackage(entries)
        }

        /** Resuelve `../media/a.png` respecto a `ppt/slides` → `ppt/media/a.png`. */
        fun resolve(baseDir: String, target: String): String {
            val raw = if (target.startsWith("/")) target.drop(1) else if (baseDir.isEmpty()) target else "$baseDir/$target"
            val parts = ArrayDeque<String>()
            for (segment in raw.split('/')) {
                when (segment) {
                    "", "." -> Unit
                    ".." -> parts.removeLastOrNull()
                    else -> parts.addLast(segment)
                }
            }
            return parts.joinToString("/")
        }
    }
}

/** Elemento XML ya leído. Los nombres (de elementos y atributos) van sin prefijo de espacio de nombres. */
class XmlNode(val name: String, private val attrs: Map<String, String>) {
    val children = ArrayList<XmlNode>(2)
    private var textBuilder: StringBuilder? = null

    val text: String get() = textBuilder?.toString().orEmpty()

    operator fun get(attr: String): String? = attrs[attr]

    fun child(name: String): XmlNode? = children.firstOrNull { it.name == name }

    fun children(name: String): List<XmlNode> = children.filter { it.name == name }

    fun path(vararg names: String): XmlNode? {
        var node: XmlNode? = this
        for (n in names) node = node?.child(n) ?: return null
        return node
    }

    /** Primer descendiente con ese nombre (en profundidad). */
    fun find(name: String): XmlNode? {
        for (c in children) {
            if (c.name == name) return c
            c.find(name)?.let { return it }
        }
        return null
    }

    fun findAll(name: String, out: MutableList<XmlNode> = ArrayList()): List<XmlNode> {
        for (c in children) {
            if (c.name == name) out.add(c) else c.findAll(name, out)
        }
        return out
    }

    /** Texto de todos los elementos [tag] descendientes, concatenado. */
    fun allText(tag: String = "t"): String = buildString { findAll(tag).forEach { append(it.text) } }

    companion object {
        fun parse(bytes: ByteArray): XmlNode {
            val parser = android.util.Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(ByteArrayInputStream(bytes), null)
            val stack = ArrayList<XmlNode>()
            var root: XmlNode? = null
            while (true) {
                when (parser.next()) {
                    XmlPullParser.START_TAG -> {
                        val attrs = HashMap<String, String>(parser.attributeCount * 2)
                        for (i in 0 until parser.attributeCount) attrs[parser.getAttributeName(i)] = parser.getAttributeValue(i)
                        val node = XmlNode(parser.name, attrs)
                        stack.lastOrNull()?.children?.add(node) ?: run { root = node }
                        stack.add(node)
                    }
                    XmlPullParser.END_TAG -> stack.removeAt(stack.size - 1)
                    XmlPullParser.TEXT -> stack.lastOrNull()?.let { node ->
                        val builder = node.textBuilder ?: StringBuilder().also { node.textBuilder = it }
                        builder.append(parser.text)
                    }
                    XmlPullParser.END_DOCUMENT -> break
                }
            }
            return root ?: throw InvalidOfficeException("XML vacío")
        }
    }
}

/**
 * Imágenes incrustadas en el HTML. Cada imagen se escribe una sola vez, como clase CSS con un
 * `data:` URI, aunque aparezca muchas veces (p. ej. el logotipo de cada diapositiva).
 */
class ImageRegistry(private val pkg: OfficePackage) {
    private val classes = HashMap<String, String?>()
    private val css = StringBuilder()

    /** Clase CSS para la imagen en [path], o null si no existe o el formato no se puede mostrar. */
    fun classFor(path: String): String? = classes.getOrPut(path) {
        val bytes = pkg.bytes(path) ?: return@getOrPut null
        val mime = mimeOf(path, bytes) ?: return@getOrPut null
        val name = "im${classes.size}"
        css.append('.').append(name).append("{background-image:url(data:").append(mime).append(";base64,")
            .append(Base64.encodeToString(bytes, Base64.NO_WRAP)).append(")}\n")
        name
    }

    fun css(): String = css.toString()

    private fun mimeOf(path: String, bytes: ByteArray): String? {
        when (path.substringAfterLast('.').lowercase()) {
            "png" -> return "image/png"
            "jpg", "jpeg", "jpe", "jfif" -> return "image/jpeg"
            "gif" -> return "image/gif"
            "bmp", "dib" -> return "image/bmp"
            "webp" -> return "image/webp"
            "svg" -> return "image/svg+xml"
            "ico" -> return "image/x-icon"
        }
        if (bytes.size < 4) return null
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        return when {
            b0 == 0x89 && b1 == 0x50 -> "image/png"
            b0 == 0xFF && b1 == 0xD8 -> "image/jpeg"
            b0 == 0x47 && b1 == 0x49 -> "image/gif"
            b0 == 0x42 && b1 == 0x4D -> "image/bmp"
            else -> null // EMF, WMF, TIFF...: el navegador no los sabe dibujar.
        }
    }
}

/** Escapa texto para insertarlo en HTML (contenido o atributo). */
fun String.html(): String {
    if (none { it == '&' || it == '<' || it == '>' || it == '"' || it == '\'' }) return this
    val sb = StringBuilder(length + 16)
    for (c in this) {
        when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&#39;")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

/** Color `RRGGBB` (o `AARRGGBB`) de Office a `#rrggbb`; null si no es válido. */
fun officeHex(value: String?): String? {
    val v = value?.trim() ?: return null
    val hex = when (v.length) {
        6 -> v
        8 -> v.substring(2)
        else -> return null
    }
    return if (hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) "#${hex.lowercase()}" else null
}

/** Familia CSS aproximada para una fuente de Office (el teléfono no tiene Calibri, Cambria...). */
fun cssFontFamily(font: String?): String? {
    val f = font?.lowercase() ?: return null
    return when {
        f.contains("courier") || f.contains("consolas") || f.contains("mono") || f.contains("lucida console") ->
            "monospace"
        f.contains("times") || f.contains("cambria") || f.contains("georgia") || f.contains("garamond") ||
            f.contains("book antiqua") || f.contains("palatino") || f.contains("serif") && !f.contains("sans") ->
            "serif"
        else -> null
    }
}

/** Página HTML completa con el estilo común de los visores de Office. */
fun officeHtmlPage(viewport: String, css: String, body: String): String = buildString(body.length + css.length + 512) {
    append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
    append("<meta name=\"viewport\" content=\"").append(viewport).append("\">")
    append("<style>")
    append("html.night{filter:invert(1) hue-rotate(180deg)}")
    append("html.night .img{filter:invert(1) hue-rotate(180deg)}")
    append("@media print{html.night,html.night .img{filter:none}}")
    append("*{-webkit-print-color-adjust:exact;print-color-adjust:exact}")
    append(".img{display:inline-block;background-size:100% 100%;background-repeat:no-repeat}")
    append(css)
    append("</style></head><body>")
    append(body)
    append("</body></html>")
}
