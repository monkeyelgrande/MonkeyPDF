package com.monkeypdf.app

import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.math.MathContext
import java.text.DateFormatSymbols
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Convierte un libro de Excel (.xlsx) a HTML: una tabla por hoja con encabezados de fila y
 * columna, pestañas para cambiar de hoja, celdas combinadas, colores, bordes y formatos de
 * número y fecha. Se muestran los valores calculados que Excel guardó (no se recalculan fórmulas).
 */
class XlsxToHtml(private val pkg: OfficePackage, private val labels: OfficeLabels) {

    private val workbookPath = pkg.mainPart() ?: "xl/workbook.xml"
    private val workbookRels = pkg.rels(workbookPath)
    private val locale = Locale.getDefault()
    private val symbols = DecimalFormatSymbols.getInstance(locale)
    private val dateSymbols = DateFormatSymbols.getInstance(locale)

    private var sharedStrings: List<String> = emptyList()
    private val themeColors = ArrayList<String>()
    private var date1904 = false

    private class Font(val bold: Boolean, val italic: Boolean, val underline: Boolean, val strike: Boolean, val size: Float?, val color: String?)
    private class Xf(val numFmt: Int, val font: Int, val fill: Int, val border: Int, val hAlign: String?, val vAlign: String?, val wrap: Boolean, val indent: Int)

    private val fonts = ArrayList<Font>()
    private val fills = ArrayList<String?>()
    private val borders = ArrayList<String>()
    private val xfs = ArrayList<Xf>()
    private val numFmts = HashMap<Int, String>()

    fun convert(): String {
        val workbook = pkg.xml(workbookPath) ?: throw InvalidOfficeException("Falta el libro")
        date1904 = workbook.child("workbookPr")?.get("date1904").let { it == "1" || it == "true" }
        relTarget("/theme")?.let { pkg.xml(it) }?.let(::loadTheme)
        relTarget("/sharedStrings")?.let { pkg.bytes(it) }?.let { sharedStrings = readSharedStrings(it) }
        relTarget("/styles")?.let { pkg.xml(it) }?.let(::loadStyles)

        class Sheet(val name: String, val html: String)
        val sheets = ArrayList<Sheet>()
        workbook.child("sheets")?.children("sheet")?.forEach { s ->
            if (s["state"] == "hidden" || s["state"] == "veryHidden") return@forEach
            val name = s["name"] ?: return@forEach
            val rel = s["id"]?.let { workbookRels[it] } ?: return@forEach
            val html = if (rel.type.endsWith("/chartsheet")) {
                "<p class=\"msg\">${labels.chart.html()}</p>"
            } else {
                pkg.bytes(rel.target)?.let { sheetHtml(it) } ?: return@forEach
            }
            sheets.add(Sheet(name, html))
        }
        if (sheets.isEmpty()) throw InvalidOfficeException("Libro sin hojas")

        val body = StringBuilder()
        val css = StringBuilder(BASE_CSS)
        sheets.indices.forEach { i ->
            body.append("<input type=\"radio\" name=\"sheet\" class=\"tab\" id=\"t").append(i).append('"')
            if (i == 0) body.append(" checked")
            body.append('>')
            css.append("#t").append(i).append(":checked~.sheets #s").append(i).append("{display:block}")
            css.append("#t").append(i).append(":checked~nav label[for=t").append(i).append("]{background:#fff;color:#185c37;")
                .append("border-top:3px solid #217346;font-weight:bold}")
        }
        if (sheets.size > 1) {
            body.append("<nav>")
            sheets.forEachIndexed { i, s ->
                body.append("<label for=\"t").append(i).append("\">").append(s.name.html()).append("</label>")
            }
            body.append("</nav>")
        } else {
            css.append("body{padding-bottom:0}")
        }
        body.append("<div class=\"sheets\">")
        sheets.forEachIndexed { i, s ->
            body.append("<section class=\"sheet\" id=\"s").append(i).append("\">").append(s.html).append("</section>")
        }
        body.append("</div>")
        css.append(styleCss())
        return officeHtmlPage("width=device-width, initial-scale=1, minimum-scale=0.2, maximum-scale=8", css.toString(), body.toString())
    }

    private fun relTarget(suffix: String) = workbookRels.values.firstOrNull { it.type.endsWith(suffix) }?.target

    // region Tema, textos y estilos

    private fun loadTheme(theme: XmlNode) {
        val scheme = theme.find("clrScheme") ?: return
        // Orden de los índices de color de tema en SpreadsheetML.
        listOf("lt1", "dk1", "lt2", "dk2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink")
            .forEach { name ->
                val node = scheme.child(name)
                val hex = node?.child("srgbClr")?.get("val") ?: node?.child("sysClr")?.get("lastClr")
                themeColors.add(officeHex(hex) ?: "#000000")
            }
    }

    private fun readSharedStrings(bytes: ByteArray): List<String> {
        val result = ArrayList<String>()
        val parser = newParser(bytes)
        val current = StringBuilder()
        var inSi = false
        var inT = false
        var skip = 0 // Dentro de <rPh> (guía fonética), que no se muestra.
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> { inSi = true; current.setLength(0) }
                    "t" -> inT = true
                    "rPh" -> skip++
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "si" -> { inSi = false; result.add(current.toString()) }
                    "t" -> inT = false
                    "rPh" -> skip--
                }
                XmlPullParser.TEXT -> if (inSi && inT && skip == 0) current.append(parser.text)
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        return result
    }

    private fun color(node: XmlNode?): String? {
        node ?: return null
        val base = node["rgb"]?.let(::officeHex)
            ?: node["theme"]?.toIntOrNull()?.let { themeColors.getOrNull(it) }
            ?: node["indexed"]?.toIntOrNull()?.let { INDEXED.getOrNull(it) }
            ?: return null
        val tint = node["tint"]?.toDoubleOrNull() ?: return base
        val rgb = base.drop(1).toInt(16)
        fun channel(shift: Int): Int {
            val c = (rgb shr shift) and 0xFF
            return (if (tint < 0) c * (1 + tint) else c + (255 - c) * tint).roundToLong().toInt().coerceIn(0, 255)
        }
        return "#%02x%02x%02x".format(channel(16), channel(8), channel(0))
    }

    private fun loadStyles(root: XmlNode) {
        root.child("numFmts")?.children("numFmt")?.forEach { f ->
            val id = f["numFmtId"]?.toIntOrNull() ?: return@forEach
            numFmts[id] = f["formatCode"] ?: return@forEach
        }
        root.child("fonts")?.children("font")?.forEach { f ->
            fun on(name: String) = f.child(name)?.let { it["val"] != "0" && it["val"] != "false" } == true
            fonts.add(
                Font(
                    bold = on("b"), italic = on("i"),
                    underline = f.child("u")?.let { it["val"] != "none" } == true,
                    strike = on("strike"),
                    size = f.child("sz")?.get("val")?.toFloatOrNull(),
                    color = color(f.child("color")),
                )
            )
        }
        root.child("fills")?.children("fill")?.forEach { f ->
            val pattern = f.child("patternFill")
            val type = pattern?.get("patternType")
            fills.add(
                if (pattern != null && type != null && type != "none") color(pattern.child("fgColor")) ?: color(pattern.child("bgColor"))
                else f.child("gradientFill")?.find("color")?.let(::color)
            )
        }
        root.child("borders")?.children("border")?.forEach { b ->
            val css = StringBuilder()
            for ((side, cssSide) in listOf("left" to "left", "right" to "right", "top" to "top", "bottom" to "bottom")) {
                val node = b.child(side) ?: continue
                val style = node["style"] ?: continue
                val width = when (style) {
                    "medium", "mediumDashed", "mediumDashDot", "mediumDashDotDot", "slantDashDot" -> 2
                    "thick", "double" -> 3
                    else -> 1
                }
                val line = when (style) {
                    "dashed", "mediumDashed", "dashDot", "mediumDashDot", "dashDotDot", "mediumDashDotDot", "slantDashDot" -> "dashed"
                    "dotted", "hair" -> "dotted"
                    "double" -> "double"
                    else -> "solid"
                }
                css.append("border-").append(cssSide).append(':').append(width).append("px ").append(line).append(' ')
                    .append(color(node.child("color")) ?: "#000").append(';')
            }
            borders.add(css.toString())
        }
        root.child("cellXfs")?.children("xf")?.forEach { x ->
            val alignment = x.child("alignment")
            xfs.add(
                Xf(
                    numFmt = x["numFmtId"]?.toIntOrNull() ?: 0,
                    font = x["fontId"]?.toIntOrNull() ?: 0,
                    fill = x["fillId"]?.toIntOrNull() ?: 0,
                    border = x["borderId"]?.toIntOrNull() ?: 0,
                    hAlign = alignment?.get("horizontal"),
                    vAlign = alignment?.get("vertical"),
                    wrap = alignment?.get("wrapText").let { it == "1" || it == "true" },
                    indent = alignment?.get("indent")?.toIntOrNull() ?: 0,
                )
            )
        }
    }

    private val usedStyles = HashSet<Int>()

    /** CSS de los estilos de celda usados: `.x3{...}`. */
    private fun styleCss(): String = buildString {
        val defaultSize = fonts.firstOrNull()?.size
        for (i in usedStyles.sorted()) {
            val xf = xfs.getOrNull(i) ?: continue
            append("table.grid td.x").append(i).append('{')
            fonts.getOrNull(xf.font)?.let { f ->
                if (f.bold) append("font-weight:bold;")
                if (f.italic) append("font-style:italic;")
                if (f.underline || f.strike) {
                    append("text-decoration:")
                    if (f.underline) append("underline ")
                    if (f.strike) append("line-through")
                    append(';')
                }
                if (f.size != null && f.size != defaultSize) append("font-size:").append(fmt(f.size)).append("pt;")
                f.color?.let { append("color:").append(it).append(';') }
            }
            fills.getOrNull(xf.fill)?.let { append("background:").append(it).append(';') }
            borders.getOrNull(xf.border)?.let { append(it) }
            when (xf.hAlign) {
                "center", "centerContinuous" -> append("text-align:center;")
                "right" -> append("text-align:right;")
                "left" -> append("text-align:left;")
                "justify", "distributed" -> append("text-align:justify;")
            }
            when (xf.vAlign) {
                "top" -> append("vertical-align:top;")
                "center" -> append("vertical-align:middle;")
            }
            if (xf.wrap) append("white-space:pre-wrap;overflow-wrap:break-word;")
            if (xf.indent > 0) append("padding-left:").append(4 + xf.indent * 9).append("px;")
            append("}\n")
        }
        fonts.firstOrNull()?.size?.let { append(".grid td{font-size:").append(fmt(it)).append("pt}") }
    }

    // endregion

    // region Hojas

    private class Cell(val text: String, val style: Int, val kind: Char, val color: String?)

    private fun sheetHtml(bytes: ByteArray): String {
        val rows = HashMap<Int, HashMap<Int, Cell>>()
        val colWidths = HashMap<Int, Float>()
        val hiddenCols = HashSet<Int>()
        val hiddenRows = HashSet<Int>()
        val rowHeights = HashMap<Int, Float>()
        val merges = ArrayList<IntArray>() // fila1, col1, fila2, col2 (base 0)
        var defaultWidth = 8.43f
        var maxRow = -1
        var maxCol = -1
        var showGrid = true

        val parser = newParser(bytes)
        var rowIndex = -1
        var colIndex = -1
        var cellRef: String? = null
        var cellType: String? = null
        var cellStyle = 0
        var value: StringBuilder? = null
        var inline: StringBuilder? = null
        var inV = false
        var inT = false
        var nextCol = 0

        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "sheetView" -> showGrid = parser.attr("showGridLines").let { it != "0" && it != "false" }
                    "sheetFormatPr" -> parser.attr("defaultColWidth")?.toFloatOrNull()?.let { defaultWidth = it }
                    "col" -> {
                        val min = parser.attr("min")?.toIntOrNull() ?: continue
                        val max = (parser.attr("max")?.toIntOrNull() ?: min).coerceAtMost(min + MAX_COLS)
                        val width = parser.attr("width")?.toFloatOrNull()
                        val hidden = parser.attr("hidden").let { it == "1" || it == "true" }
                        for (c in min - 1 until max) {
                            width?.let { colWidths[c] = it }
                            if (hidden) hiddenCols.add(c)
                        }
                    }
                    "row" -> {
                        rowIndex = parser.attr("r")?.toIntOrNull()?.minus(1) ?: (rowIndex + 1)
                        nextCol = 0
                        if (parser.attr("hidden").let { it == "1" || it == "true" }) hiddenRows.add(rowIndex)
                        if (parser.attr("customHeight").let { it == "1" || it == "true" }) {
                            parser.attr("ht")?.toFloatOrNull()?.let { rowHeights[rowIndex] = it }
                        }
                    }
                    "c" -> {
                        cellRef = parser.attr("r")
                        colIndex = cellRef?.let { columnIndex(it) } ?: nextCol
                        nextCol = colIndex + 1
                        cellType = parser.attr("t")
                        cellStyle = parser.attr("s")?.toIntOrNull() ?: 0
                        value = null
                        inline = null
                    }
                    "v" -> { inV = true; value = StringBuilder() }
                    "is" -> inline = StringBuilder()
                    "t" -> inT = true
                    "rPh" -> inT = false
                    "mergeCell" -> parser.attr("ref")?.let(::parseRange)?.let { merges.add(it) }
                }
                XmlPullParser.TEXT -> {
                    if (inV) value?.append(parser.text)
                    else if (inT) inline?.append(parser.text)
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v" -> inV = false
                    "t" -> inT = false
                    "c" -> {
                        if (rowIndex < 0) continue
                        val cell = makeCell(cellType, cellStyle, value?.toString(), inline?.toString())
                        if (cell.text.isNotEmpty()) {
                            if (rowIndex > maxRow) maxRow = rowIndex
                            if (colIndex > maxCol) maxCol = colIndex
                        }
                        if (cell.text.isNotEmpty() || hasVisibleStyle(cellStyle)) {
                            if (rowIndex < MAX_ROWS_SCAN && colIndex < MAX_COLS) {
                                rows.getOrPut(rowIndex) { HashMap() }[colIndex] = cell
                            }
                        }
                    }
                }
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        for (m in merges) {
            if (m[2] > maxRow && rows[m[0]]?.get(m[1])?.text?.isNotEmpty() == true) maxRow = m[2]
            if (m[3] > maxCol && rows[m[0]]?.get(m[1])?.text?.isNotEmpty() == true) maxCol = m[3]
        }
        if (maxRow < 0) return "<p class=\"msg\">${labels.emptySheet.html()}</p>"

        val totalCols = maxCol + 1
        val cols = totalCols.coerceAtMost(MAX_COLS)
        val rowLimit = (MAX_CELLS / cols.coerceAtLeast(1)).coerceAtMost(MAX_ROWS)
        val lastRow = maxRow.coerceAtMost(rowLimit - 1)
        val truncated = maxRow > lastRow || totalCols > cols

        // Celdas combinadas: la de arriba a la izquierda ocupa el resto.
        val spans = HashMap<Long, IntArray>()
        val covered = HashSet<Long>()
        for (m in merges) {
            val r2 = m[2].coerceAtMost(lastRow)
            val c2 = m[3].coerceAtMost(cols - 1)
            if (m[0] > r2 || m[1] > c2) continue
            val visibleRows = (m[0]..r2).count { it !in hiddenRows }
            val visibleCols = (m[1]..c2).count { it !in hiddenCols }
            spans[key(m[0], m[1])] = intArrayOf(visibleRows, visibleCols)
            for (r in m[0]..r2) for (c in m[1]..c2) if (r != m[0] || c != m[1]) covered.add(key(r, c))
        }

        val out = StringBuilder()
        if (truncated) out.append("<p class=\"msg\">").append(labels.truncated(lastRow + 1, cols).html()).append("</p>")
        var tableWidth = 40
        val widths = IntArray(cols) { c ->
            if (c in hiddenCols) 0 else ((colWidths[c] ?: defaultWidth) * 7 + 5).roundToLong().toInt().also { tableWidth += it }
        }
        out.append("<table class=\"grid")
        if (!showGrid) out.append(" nogrid")
        out.append("\" style=\"width:").append(tableWidth).append("px\"><colgroup><col style=\"width:40px\">")
        for (c in 0 until cols) if (c !in hiddenCols) out.append("<col style=\"width:").append(widths[c]).append("px\">")
        out.append("</colgroup><thead><tr><th class=\"corner\"></th>")
        for (c in 0 until cols) if (c !in hiddenCols) out.append("<th>").append(columnName(c)).append("</th>")
        out.append("</tr></thead><tbody>")
        for (r in 0..lastRow) {
            if (r in hiddenRows) continue
            out.append("<tr")
            rowHeights[r]?.let { out.append(" style=\"height:").append((it * 4 / 3).roundToLong()).append("px\"") }
            out.append("><th>").append(r + 1).append("</th>")
            val row = rows[r]
            for (c in 0 until cols) {
                if (c in hiddenCols) continue
                val k = key(r, c)
                if (k in covered) continue
                val cell = row?.get(c)
                out.append("<td")
                spans[k]?.let { s ->
                    if (s[0] > 1) out.append(" rowspan=\"").append(s[0]).append('"')
                    if (s[1] > 1) out.append(" colspan=\"").append(s[1]).append('"')
                }
                if (cell != null) {
                    val classes = StringBuilder()
                    if (cell.style in xfs.indices) {
                        usedStyles.add(cell.style)
                        classes.append('x').append(cell.style)
                    }
                    val align = xfs.getOrNull(cell.style)?.hAlign
                    if (align == null || align == "general") {
                        when (cell.kind) {
                            'n' -> classes.append(" n")
                            'b', 'e' -> classes.append(" c")
                        }
                    }
                    if (spans[k] != null) classes.append(" m")
                    if (classes.isNotEmpty()) out.append(" class=\"").append(classes.trim()).append('"')
                    cell.color?.let { out.append(" style=\"color:").append(it).append('"') }
                    out.append('>').append(cell.text.html())
                } else {
                    out.append('>')
                }
                out.append("</td>")
            }
            out.append("</tr>")
        }
        out.append("</tbody></table>")
        return out.toString()
    }

    private fun hasVisibleStyle(style: Int): Boolean {
        val xf = xfs.getOrNull(style) ?: return false
        return fills.getOrNull(xf.fill) != null || borders.getOrNull(xf.border)?.isNotEmpty() == true
    }

    private fun makeCell(type: String?, style: Int, value: String?, inline: String?): Cell = when (type) {
        "s" -> Cell(value?.trim()?.toIntOrNull()?.let { sharedStrings.getOrNull(it) }.orEmpty(), style, 's', null)
        "inlineStr" -> Cell(inline.orEmpty(), style, 's', null)
        "str" -> Cell(value.orEmpty(), style, 's', null)
        "b" -> Cell(if (value?.trim() == "1") labels.trueText else labels.falseText, style, 'b', null)
        "e" -> Cell(value.orEmpty(), style, 'e', null)
        "d" -> Cell(value.orEmpty().replace('T', ' ').substringBefore('.'), style, 'n', null)
        else -> {
            val number = value?.trim()?.toDoubleOrNull()
            if (number == null) {
                Cell(value.orEmpty(), style, 's', null)
            } else {
                val xf = xfs.getOrNull(style)
                val id = xf?.numFmt ?: 0
                val (text, color) = formatNumber(number, numFmts[id] ?: BUILTIN_FORMATS[id])
                Cell(text, style, 'n', color)
            }
        }
    }

    // endregion

    // region Formatos de número

    /** Aplica un código de formato de Excel. Devuelve el texto y, si lo indica el formato, su color. */
    private fun formatNumber(value: Double, code: String?): Pair<String, String?> {
        if (code == null || code.equals("General", ignoreCase = true)) return general(value) to null
        val sections = splitSections(code)
        val (section, explicitSign) = when {
            sections.size >= 3 && value == 0.0 -> sections[2] to true
            sections.size >= 2 && value < 0 -> sections[1] to true
            else -> sections[0] to false
        }
        var color: String? = null
        Regex("\\[(Red|Blue|Green|Black|White|Magenta|Cyan|Yellow|Rojo|Azul|Verde|Negro|Blanco|Amarillo)]", RegexOption.IGNORE_CASE)
            .find(section)?.let { color = COLOR_NAMES[it.groupValues[1].lowercase()] }
        if (section.equals("General", ignoreCase = true) || section.isBlank()) return general(value) to color
        if (section == "@") return general(value) to color
        return try {
            val text = if (isDateFormat(section)) formatDate(value, section)
            else formatPlainNumber(if (explicitSign) abs(value) else value, section)
            text to color
        } catch (e: Exception) {
            general(value) to null
        }
    }

    private fun splitSections(code: String): List<String> {
        val result = ArrayList<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                c == '"' -> { quoted = !quoted; current.append(c) }
                c == '\\' && i + 1 < code.length -> { current.append(c).append(code[i + 1]); i++ }
                c == ';' && !quoted -> { result.add(current.toString()); current.setLength(0) }
                else -> current.append(c)
            }
            i++
        }
        result.add(current.toString())
        return result
    }

    private fun isDateFormat(section: String): Boolean {
        val stripped = stripLiterals(section).replace(Regex("\\[[^]]*]")) { m ->
            if (m.value.matches(Regex("\\[(h+|m+|s+)]", RegexOption.IGNORE_CASE))) "h" else ""
        }
        return stripped.any { it.lowercaseChar() in "ydhs" } ||
            (stripped.contains('m', ignoreCase = true) && !stripped.any { it == '0' || it == '#' })
    }

    private fun stripLiterals(s: String): String {
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> quoted = !quoted
                quoted -> Unit
                c == '\\' || c == '_' || c == '*' -> i++
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private fun formatPlainNumber(value: Double, section: String): String {
        // Separa el patrón numérico (0 # ? , . E) de los textos literales que lo rodean.
        val prefix = StringBuilder()
        val suffix = StringBuilder()
        val pattern = StringBuilder()
        var percent = 0
        var quoted = false
        var i = 0
        var seenPattern = false
        fun literal(text: String) { if (seenPattern) suffix.append(text) else prefix.append(text) }
        while (i < section.length) {
            val c = section[i]
            when {
                quoted -> if (c == '"') quoted = false else literal(c.toString())
                c == '"' -> quoted = true
                c == '\\' && i + 1 < section.length -> { literal(section[i + 1].toString()); i++ }
                c == '_' -> { literal(" "); i++ }
                c == '*' -> i++
                c == '[' -> {
                    val end = section.indexOf(']', i)
                    if (end < 0) break
                    val inside = section.substring(i + 1, end)
                    if (inside.startsWith("$")) literal(inside.drop(1).substringBefore('-'))
                    i = end
                }
                c == '%' -> { percent++; literal("%") }
                c in "0#?" || (c in ",." && (seenPattern || i + 1 < section.length && section[i + 1] in "0#?")) -> {
                    seenPattern = true
                    if (suffix.isNotEmpty() && c in "0#?") { pattern.append(suffix); suffix.setLength(0) }
                    pattern.append(c)
                }
                (c == 'E' || c == 'e') && seenPattern && i + 1 < section.length && section[i + 1] in "+-" -> {
                    pattern.append("E"); i++
                    while (i + 1 < section.length && section[i + 1] in "0#") { pattern.append('0'); i++ }
                }
                c == '/' && seenPattern -> pattern.append(c) // fracciones: se muestran como decimales
                else -> literal(c.toString())
            }
            i++
        }
        var v = value
        repeat(percent) { v *= 100 }
        val p = pattern.toString()
        if (p.isEmpty()) return prefix.toString() + suffix
        if (p.contains('/')) return prefix.toString() + general(v) + suffix

        // Comas al final del patrón dividen entre mil ("#,##0," → miles).
        var core = p
        while (core.endsWith(',')) { v /= 1000; core = core.dropLast(1) }
        val decimals = core.substringAfter('.', "").takeWhile { it in "0#?" }
        val minDecimals = decimals.count { it == '0' }
        val maxDecimals = decimals.length
        val grouping = core.substringBefore('.').contains(',')
        val formatted = if (core.contains('E')) {
            val mantissa = "0" + (if (maxDecimals > 0) "." + "0".repeat(maxDecimals) else "")
            DecimalFormat("${mantissa}E00", symbols).format(v)
        } else {
            val intPart = core.substringBefore('.').filter { it in "0#?" }
            val minInt = intPart.count { it == '0' }
            DecimalFormat().apply {
                decimalFormatSymbols = symbols
                isGroupingUsed = grouping
                groupingSize = 3
                minimumIntegerDigits = minInt
                minimumFractionDigits = minDecimals
                maximumFractionDigits = maxDecimals
            }.format(v)
        }
        return prefix.toString() + formatted + suffix
    }

    private fun general(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return value.toString()
        if (value == floor(value) && abs(value) < 1e15) {
            return value.toLong().toString()
        }
        val a = abs(value)
        val text = if (a >= 1e11 || a < 1e-9) {
            DecimalFormat("0.#####E0", symbols).format(value)
        } else {
            val plain = BigDecimal(value).round(MathContext(10)).stripTrailingZeros().toPlainString()
            plain.replace('.', symbols.decimalSeparator)
        }
        return text
    }

    private fun formatDate(value: Double, section: String): String {
        val epoch = if (date1904) LocalDateTime.of(1904, 1, 1, 0, 0) else LocalDateTime.of(1899, 12, 30, 0, 0)
        val days = floor(value).toLong()
        val totalSeconds = ((value - days) * 86400).roundToLong()
        val date = epoch.plusDays(days).plusSeconds(totalSeconds)

        // Tokens: texto literal o una letra repetida (yyyy, mm, d, h, ss...).
        data class Tok(val kind: Char, val len: Int, val text: String = "")
        val tokens = ArrayList<Tok>()
        val ampm = Regex("AM/PM|A/P", RegexOption.IGNORE_CASE).containsMatchIn(stripLiterals(section))
        var i = 0
        while (i < section.length) {
            val c = section[i]
            when {
                c == '"' -> {
                    val end = section.indexOf('"', i + 1).let { if (it < 0) section.length else it }
                    tokens.add(Tok('l', 0, section.substring(i + 1, end))); i = end
                }
                c == '\\' && i + 1 < section.length -> { tokens.add(Tok('l', 0, section[i + 1].toString())); i++ }
                c == '_' || c == '*' -> i++
                c == '[' -> {
                    val end = section.indexOf(']', i).let { if (it < 0) section.length - 1 else it }
                    val inside = section.substring(i + 1, end).lowercase()
                    when {
                        inside.matches(Regex("h+")) -> tokens.add(Tok('H', inside.length))
                        inside.matches(Regex("m+")) -> tokens.add(Tok('M', inside.length))
                        inside.matches(Regex("s+")) -> tokens.add(Tok('S', inside.length))
                    }
                    i = end
                }
                section.regionMatches(i, "AM/PM", 0, 5, ignoreCase = true) -> { tokens.add(Tok('a', 5)); i += 4 }
                section.regionMatches(i, "A/P", 0, 3, ignoreCase = true) -> { tokens.add(Tok('a', 1)); i += 2 }
                c.lowercaseChar() in "ymdhse" -> {
                    var j = i
                    while (j < section.length && section[j].lowercaseChar() == c.lowercaseChar()) j++
                    tokens.add(Tok(c.lowercaseChar(), j - i)); i = j - 1
                }
                c == '.' && i + 1 < section.length && section[i + 1] == '0' -> {
                    var j = i + 1
                    while (j < section.length && section[j] == '0') j++
                    tokens.add(Tok('f', j - i - 1)); i = j - 1
                }
                else -> tokens.add(Tok('l', 0, c.toString()))
            }
            i++
        }
        // "m" es minutos si va justo después de horas o antes de segundos.
        val kinds = tokens.indices.map { idx ->
            val t = tokens[idx]
            if (t.kind != 'm') return@map t.kind
            val prev = tokens.subList(0, idx).lastOrNull { it.kind != 'l' }?.kind
            val next = tokens.subList(idx + 1, tokens.size).firstOrNull { it.kind != 'l' }?.kind
            if (prev == 'h' || prev == 'H' || next == 's' || next == 'S') 'n' else 'm'
        }
        val hour24 = date.hour
        val sb = StringBuilder()
        tokens.forEachIndexed { idx, t ->
            when (kinds[idx]) {
                'l' -> sb.append(t.text)
                'y' -> sb.append(if (t.len <= 2) "%02d".format(date.year % 100) else date.year.toString())
                'e' -> sb.append(date.year)
                'm' -> sb.append(
                    when (t.len) {
                        1 -> date.monthValue.toString()
                        2 -> "%02d".format(date.monthValue)
                        3 -> dateSymbols.shortMonths[date.monthValue - 1].trimEnd('.')
                        5 -> dateSymbols.months[date.monthValue - 1].take(1)
                        else -> dateSymbols.months[date.monthValue - 1]
                    }
                )
                'd' -> sb.append(
                    when (t.len) {
                        1 -> date.dayOfMonth.toString()
                        2 -> "%02d".format(date.dayOfMonth)
                        3 -> dateSymbols.shortWeekdays[date.dayOfWeek.value % 7 + 1].trimEnd('.')
                        else -> dateSymbols.weekdays[date.dayOfWeek.value % 7 + 1]
                    }
                )
                'h' -> {
                    val h = if (ampm) (hour24 % 12).let { if (it == 0) 12 else it } else hour24
                    sb.append(if (t.len >= 2) "%02d".format(h) else h.toString())
                }
                'H' -> sb.append((value * 24).toLong().toString().padStart(t.len, '0'))
                'M' -> sb.append((value * 1440).toLong().toString().padStart(t.len, '0'))
                'S' -> sb.append((value * 86400).roundToLong().toString().padStart(t.len, '0'))
                'n' -> sb.append(if (t.len >= 2) "%02d".format(date.minute) else date.minute.toString())
                's' -> sb.append(if (t.len >= 2) "%02d".format(date.second) else date.second.toString())
                'f' -> sb.append(symbols.decimalSeparator).append("0".repeat(t.len))
                'a' -> sb.append(if (t.len == 1) (if (hour24 < 12) "a" else "p") else if (hour24 < 12) "a. m." else "p. m.")
            }
        }
        return sb.toString()
    }

    // endregion

    private fun newParser(bytes: ByteArray): XmlPullParser = android.util.Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setInput(ByteArrayInputStream(bytes), null)
    }

    private fun XmlPullParser.attr(name: String): String? {
        for (i in 0 until attributeCount) if (getAttributeName(i) == name) return getAttributeValue(i)
        return null
    }

    private fun key(r: Int, c: Int) = (r.toLong() shl 20) or c.toLong()

    /** "B3" → 1 (columna, base 0). */
    private fun columnIndex(ref: String): Int {
        var col = 0
        for (ch in ref) {
            if (ch !in 'A'..'Z' && ch !in 'a'..'z') break
            col = col * 26 + (ch.uppercaseChar() - 'A' + 1)
        }
        return col - 1
    }

    private fun rowOf(ref: String): Int = ref.dropWhile { it.isLetter() || it == '$' }.toIntOrNull()?.minus(1) ?: 0

    private fun parseRange(ref: String): IntArray? {
        val parts = ref.replace("$", "").split(':')
        if (parts.size != 2) return null
        return intArrayOf(rowOf(parts[0]), columnIndex(parts[0]), rowOf(parts[1]), columnIndex(parts[1]))
    }

    private fun columnName(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.append('A' + rem)
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    private companion object {
        const val MAX_COLS = 200
        const val MAX_ROWS = 10_000
        const val MAX_ROWS_SCAN = 10_000
        const val MAX_CELLS = 150_000

        val BASE_CSS = """
            body{margin:0;padding-bottom:52px;background:#fff;font-family:sans-serif;color:#000}
            input.tab{display:none}
            .sheet{display:none}
            nav{position:fixed;left:0;right:0;bottom:0;display:flex;overflow-x:auto;background:#f0f0f0;
              border-top:1px solid #c8c8c8;z-index:3}
            nav label{padding:12px 18px;white-space:nowrap;font-size:14px;color:#333;border-top:3px solid transparent}
            .msg{margin:12px;color:#555;font-size:14px}
            table.grid{border-collapse:collapse;table-layout:fixed;font-size:11pt}
            .grid td{border:1px solid #e1e1e1;padding:1px 4px;height:20px;white-space:nowrap;overflow:hidden;vertical-align:bottom;
              text-overflow:clip;box-sizing:border-box}
            .grid.nogrid td{border-color:transparent}
            .grid th{background:#f3f3f3;border:1px solid #d0d0d0;color:#555;font-weight:normal;font-size:12px;
              padding:2px 4px;position:sticky;z-index:1}
            .grid thead th{top:0;z-index:2}
            .grid tbody th{left:0;text-align:center}
            .grid th.corner{left:0;z-index:3}
            .grid td.n{text-align:right}
            .grid td.c{text-align:center}
            .grid td.m{white-space:pre-wrap}
        """.trimIndent()

        val BUILTIN_FORMATS = mapOf(
            1 to "0", 2 to "0.00", 3 to "#,##0", 4 to "#,##0.00",
            5 to "\$#,##0_);(\$#,##0)", 6 to "\$#,##0_);[Red](\$#,##0)", 7 to "\$#,##0.00_);(\$#,##0.00)",
            8 to "\$#,##0.00_);[Red](\$#,##0.00)",
            9 to "0%", 10 to "0.00%", 11 to "0.00E+00", 12 to "# ?/?", 13 to "# ??/??",
            14 to "dd/mm/yyyy", 15 to "d-mmm-yy", 16 to "d-mmm", 17 to "mmm-yy",
            18 to "h:mm AM/PM", 19 to "h:mm:ss AM/PM", 20 to "h:mm", 21 to "h:mm:ss", 22 to "dd/mm/yyyy h:mm",
            37 to "#,##0 ;(#,##0)", 38 to "#,##0 ;[Red](#,##0)", 39 to "#,##0.00;(#,##0.00)",
            40 to "#,##0.00;[Red](#,##0.00)", 45 to "mm:ss", 46 to "[h]:mm:ss", 47 to "mm:ss.0",
            48 to "##0.0E+0", 49 to "@",
        )

        val COLOR_NAMES = mapOf(
            "red" to "#ff0000", "rojo" to "#ff0000", "blue" to "#0000ff", "azul" to "#0000ff",
            "green" to "#008000", "verde" to "#008000", "black" to "#000000", "negro" to "#000000",
            "white" to "#ffffff", "blanco" to "#ffffff", "magenta" to "#ff00ff", "cyan" to "#00ffff",
            "yellow" to "#ffff00", "amarillo" to "#ffff00",
        )

        /** Paleta clásica de colores indexados de Excel. */
        val INDEXED = listOf(
            "#000000", "#ffffff", "#ff0000", "#00ff00", "#0000ff", "#ffff00", "#ff00ff", "#00ffff",
            "#000000", "#ffffff", "#ff0000", "#00ff00", "#0000ff", "#ffff00", "#ff00ff", "#00ffff",
            "#800000", "#008000", "#000080", "#808000", "#800080", "#008080", "#c0c0c0", "#808080",
            "#9999ff", "#993366", "#ffffcc", "#ccffff", "#660066", "#ff8080", "#0066cc", "#ccccff",
            "#000080", "#ff00ff", "#ffff00", "#00ffff", "#800080", "#800000", "#008080", "#0000ff",
            "#00ccff", "#ccffff", "#ccffcc", "#ffff99", "#99ccff", "#ff99cc", "#cc99ff", "#ffcc99",
            "#3366ff", "#33cccc", "#99cc00", "#ffcc00", "#ff9900", "#ff6600", "#666699", "#969696",
            "#003366", "#339966", "#003300", "#333300", "#993300", "#993366", "#333399", "#333333",
        )
    }
}
