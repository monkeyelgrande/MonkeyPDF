package com.monkeypdf.app

import java.util.Locale

/**
 * Convierte un documento de Word (.docx) a HTML: párrafos con su formato, títulos, listas
 * numeradas y con viñetas, tablas, imágenes, enlaces y notas al pie. No intenta reproducir la
 * paginación exacta de Word: el texto se ajusta al ancho de la pantalla.
 */
class DocxToHtml(private val pkg: OfficePackage, private val labels: OfficeLabels) {

    private val docPath = pkg.mainPart() ?: "word/document.xml"
    private val rels = pkg.rels(docPath)
    private val images = ImageRegistry(pkg)
    private val styles = HashMap<String, XmlNode>()
    private var defaultRun = RunProps()
    private var defaultPara = ParaProps()
    private val numbering = Numbering()

    private val notes = HashMap<String, XmlNode>() // "f12" / "e3" → nota
    private val noteRels = HashMap<String, Map<String, Rel>>()
    private val usedNotes = ArrayList<Pair<String, Int>>()

    /** Relaciones de la parte que se está convirtiendo (documento o notas). */
    private var currentRels = rels

    fun convert(): String {
        val doc = pkg.xml(docPath) ?: throw InvalidOfficeException("Falta el documento")
        relTarget("/styles")?.let { pkg.xml(it) }?.let(::loadStyles)
        relTarget("/numbering")?.let { pkg.xml(it) }?.let(numbering::load)
        loadNotes("/footnotes", "footnote", "f")
        loadNotes("/endnotes", "endnote", "e")

        val body = doc.child("body") ?: throw InvalidOfficeException("Documento sin cuerpo")
        val html = StringBuilder()
        blocks(body.children, html)

        if (usedNotes.isNotEmpty()) {
            html.append("<hr class=\"notes\"><section class=\"notes\">")
            for ((key, number) in usedNotes) {
                val note = notes[key] ?: continue
                currentRels = noteRels[key] ?: rels
                html.append("<div class=\"note\" id=\"n").append(number).append("\"><sup>").append(number).append("</sup>")
                blocks(note.children, html)
                html.append("</div>")
            }
            currentRels = rels
            html.append("</section>")
        }

        val sect = body.child("sectPr")
        val pageWidthPx = sect?.child("pgSz")?.get("w")?.toIntOrNull()?.let { it / 15 } ?: 816
        val marginPx = sect?.child("pgMar")?.get("left")?.toIntOrNull()?.let { it / 15 } ?: 96
        val css = """
            body{margin:0;background:#e4e4e7;font-family:sans-serif;color:#000}
            .page{background:#fff;max-width:${pageWidthPx}px;margin:0 auto;box-sizing:border-box;
              padding:clamp(16px,5vw,${marginPx / 2}px) clamp(16px,6vw,${marginPx}px);min-height:100vh;
              overflow-wrap:break-word}
            @media (min-width:${pageWidthPx + 32}px){.page{margin:16px auto;box-shadow:0 1px 4px rgba(0,0,0,.25);min-height:0}}
            p,h1,h2,h3,h4,h5,h6{margin:0 0 8pt;white-space:pre-wrap;tab-size:4;font-weight:normal;line-height:1.15}
            h1{font-size:16pt}h2{font-size:13pt}h3{font-size:12pt}h4,h5,h6{font-size:11pt}
            a{color:#0563c1}
            .bu{display:inline-block;white-space:pre}
            .tw{overflow-x:auto;margin:0 0 8pt}
            table{border-collapse:collapse}
            td{vertical-align:top;padding:2px 5px}
            table.bd td{border:1px solid #888}
            td>:last-child{margin-bottom:0}
            .ph{display:inline-block;padding:16px;background:#f1f1f1;color:#666;border:1px dashed #bbb;font-size:10pt}
            .tb{border:1px solid #ccc;padding:6px 8px;margin:4pt 0 8pt}
            hr.pb{border:none;border-top:1px dashed #ccc;margin:16px 0}
            hr.notes{border:none;border-top:1px solid #999;width:30%;margin:24px 0 8px 0}
            .note{font-size:9pt}.note sup{margin-right:4px}.note p{margin-bottom:4pt}
            a.fn{text-decoration:none}
        """.trimIndent()
        return officeHtmlPage(
            "width=device-width, initial-scale=1, minimum-scale=0.5, maximum-scale=8",
            css + images.css(),
            "<div class=\"page\">$html</div>",
        )
    }

    private fun relTarget(typeSuffix: String) = rels.values.firstOrNull { it.type.endsWith(typeSuffix) }?.target

    private fun loadNotes(typeSuffix: String, tag: String, prefix: String) {
        val path = relTarget(typeSuffix) ?: return
        val root = pkg.xml(path) ?: return
        val partRels = pkg.rels(path)
        root.children(tag).forEach { n ->
            val id = n["id"] ?: return@forEach
            notes["$prefix$id"] = n
            noteRels["$prefix$id"] = partRels
        }
    }

    // region Estilos

    private class RunProps(
        val bold: Boolean? = null,
        val italic: Boolean? = null,
        val underline: Boolean? = null,
        val strike: Boolean? = null,
        val sizePt: Float? = null,
        val color: String? = null,
        val background: String? = null,
        val vertAlign: String? = null,
        val caps: Boolean? = null,
        val smallCaps: Boolean? = null,
        val font: String? = null,
        val hidden: Boolean? = null,
    ) {
        fun merge(o: RunProps) = RunProps(
            o.bold ?: bold, o.italic ?: italic, o.underline ?: underline, o.strike ?: strike,
            o.sizePt ?: sizePt, o.color ?: color, o.background ?: background, o.vertAlign ?: vertAlign,
            o.caps ?: caps, o.smallCaps ?: smallCaps, o.font ?: font, o.hidden ?: hidden,
        )

        fun css(): String = buildString {
            if (bold == true) append("font-weight:bold;")
            if (italic == true) append("font-style:italic;")
            val deco = listOfNotNull(
                "underline".takeIf { underline == true }, "line-through".takeIf { strike == true },
            )
            if (deco.isNotEmpty()) append("text-decoration:").append(deco.joinToString(" ")).append(';')
            when (vertAlign) {
                "superscript" -> append("vertical-align:super;")
                "subscript" -> append("vertical-align:sub;")
            }
            sizePt?.let {
                val size = if (vertAlign == "superscript" || vertAlign == "subscript") it * 0.65f else it
                append("font-size:").append(fmt(size)).append("pt;")
            }
            color?.let { append("color:").append(it).append(';') }
            background?.let { append("background:").append(it).append(';') }
            if (caps == true) append("text-transform:uppercase;")
            if (smallCaps == true) append("font-variant:small-caps;")
            cssFontFamily(font)?.let { append("font-family:").append(it).append(';') }
        }
    }

    private class ParaProps(
        val align: String? = null,
        val before: Float? = null,
        val after: Float? = null,
        val line: Float? = null,
        val left: Float? = null,
        val right: Float? = null,
        val firstLine: Float? = null,
        val background: String? = null,
        val numId: String? = null,
        val ilvl: Int? = null,
        val outline: Int? = null,
        val pageBreakBefore: Boolean? = null,
    ) {
        fun merge(o: ParaProps) = ParaProps(
            o.align ?: align, o.before ?: before, o.after ?: after, o.line ?: line, o.left ?: left,
            o.right ?: right, o.firstLine ?: firstLine, o.background ?: background, o.numId ?: numId,
            o.ilvl ?: ilvl, o.outline ?: outline, o.pageBreakBefore ?: pageBreakBefore,
        )
    }

    private fun loadStyles(root: XmlNode) {
        root.children("style").forEach { s -> s["styleId"]?.let { styles[it] = s } }
        root.path("docDefaults", "rPrDefault", "rPr")?.let { defaultRun = parseRun(it) }
        root.path("docDefaults", "pPrDefault", "pPr")?.let { defaultPara = parsePara(it) }
        // Estilo "Normal" de párrafo, que se aplica cuando el párrafo no indica otro.
        styles.values.firstOrNull { it["type"] == "paragraph" && (it["default"] == "1" || it["default"] == "true") }?.let { s ->
            defaultRun = defaultRun.merge(styleRun(s["styleId"]))
            defaultPara = defaultPara.merge(stylePara(s["styleId"]))
        }
    }

    private fun styleChain(id: String?): List<XmlNode> {
        val chain = ArrayList<XmlNode>()
        var current = id
        while (current != null && chain.size < 20) {
            val s = styles[current] ?: break
            chain.add(0, s)
            current = s.child("basedOn")?.get("val")
        }
        return chain
    }

    private val runCache = HashMap<String, RunProps>()
    private val paraCache = HashMap<String, ParaProps>()

    private fun styleRun(id: String?): RunProps = if (id == null) RunProps() else runCache.getOrPut(id) {
        styleChain(id).fold(RunProps()) { acc, s -> s.child("rPr")?.let { acc.merge(parseRun(it)) } ?: acc }
    }

    private fun stylePara(id: String?): ParaProps = if (id == null) ParaProps() else paraCache.getOrPut(id) {
        styleChain(id).fold(ParaProps()) { acc, s -> s.child("pPr")?.let { acc.merge(parsePara(it)) } ?: acc }
    }

    /** Nivel de título (1-6) según el nombre del estilo o su nivel de esquema. */
    private fun headingLevel(styleId: String?, props: ParaProps): Int? {
        val name = styleId?.let { styles[it] }?.child("name")?.get("val")?.lowercase(Locale.ROOT)
        if (name != null) {
            Regex("^heading (\\d)$").find(name)?.let { return it.groupValues[1].toInt().coerceIn(1, 6) }
            if (name == "title") return 1
        }
        return props.outline?.takeIf { it in 0..5 }?.plus(1)
    }

    private fun parseRun(rPr: XmlNode): RunProps {
        fun flag(name: String): Boolean? = rPr.child(name)?.let { it["val"].isOn() }
        val u = rPr.child("u")?.get("val")
        val color = rPr.child("color")?.get("val")?.takeIf { it != "auto" }?.let(::officeHex)
        val highlight = rPr.child("highlight")?.get("val")?.let { HIGHLIGHTS[it] }
        val shading = rPr.child("shd")?.get("fill")?.takeIf { it != "auto" }?.let(::officeHex)
        val fonts = rPr.child("rFonts")
        return RunProps(
            bold = flag("b"),
            italic = flag("i"),
            underline = u?.let { it != "none" } ?: rPr.child("u")?.let { true },
            strike = flag("strike") ?: flag("dstrike"),
            sizePt = rPr.child("sz")?.get("val")?.toFloatOrNull()?.div(2f),
            color = color,
            background = highlight ?: shading,
            vertAlign = rPr.child("vertAlign")?.get("val"),
            caps = flag("caps"),
            smallCaps = flag("smallCaps"),
            font = fonts?.get("ascii") ?: fonts?.get("hAnsi"),
            hidden = flag("vanish"),
        )
    }

    private fun parsePara(pPr: XmlNode): ParaProps {
        val spacing = pPr.child("spacing")
        val ind = pPr.child("ind")
        val numPr = pPr.child("numPr")
        val lineRule = spacing?.get("lineRule")
        val hanging = ind?.get("hanging")?.toFloatOrNull()
        return ParaProps(
            align = when (pPr.child("jc")?.get("val")) {
                "center" -> "center"
                "right", "end" -> "right"
                "both", "distribute" -> "justify"
                "left", "start" -> "left"
                else -> null
            },
            before = spacing?.get("before")?.toFloatOrNull()?.div(20f),
            after = spacing?.get("after")?.toFloatOrNull()?.div(20f),
            line = spacing?.get("line")?.toFloatOrNull()?.let { if (lineRule == null || lineRule == "auto") it / 240f else -it / 20f },
            left = (ind?.get("left") ?: ind?.get("start"))?.toFloatOrNull()?.div(20f),
            right = (ind?.get("right") ?: ind?.get("end"))?.toFloatOrNull()?.div(20f),
            firstLine = hanging?.let { -it / 20f } ?: ind?.get("firstLine")?.toFloatOrNull()?.div(20f),
            background = pPr.child("shd")?.get("fill")?.takeIf { it != "auto" }?.let(::officeHex),
            numId = numPr?.child("numId")?.get("val"),
            ilvl = numPr?.child("ilvl")?.get("val")?.toIntOrNull(),
            outline = pPr.child("outlineLvl")?.get("val")?.toIntOrNull(),
            pageBreakBefore = pPr.child("pageBreakBefore")?.let { it["val"].isOn() },
        )
    }

    // endregion

    // region Bloques

    private fun blocks(nodes: List<XmlNode>, out: StringBuilder) {
        for (n in nodes) {
            when (n.name) {
                "p" -> paragraph(n, out)
                "tbl" -> table(n, out)
                "sdt" -> n.child("sdtContent")?.let { blocks(it.children, out) }
                "customXml", "ins", "smartTag" -> blocks(n.children, out)
                "AlternateContent" -> n.child("Choice")?.let { blocks(it.children, out) }
            }
        }
    }

    private fun paragraph(p: XmlNode, out: StringBuilder) {
        val pPr = p.child("pPr")
        val styleId = pPr?.child("pStyle")?.get("val")
        val props = defaultPara.merge(stylePara(styleId)).merge(pPr?.let(::parsePara) ?: ParaProps())
        val runBase = defaultRun.merge(styleRun(styleId))

        val after = StringBuilder() // Cuadros de texto: se muestran debajo del párrafo.
        val content = StringBuilder()
        inline(p.children, runBase, content, after)

        if (props.pageBreakBefore == true && out.isNotEmpty()) out.append("<hr class=\"pb\">")

        val heading = headingLevel(styleId, props)
        val tag = heading?.let { "h$it" } ?: "p"
        val style = StringBuilder()
        props.align?.let { style.append("text-align:").append(it).append(';') }
        props.before?.let { style.append("margin-top:").append(fmt(it)).append("pt;") }
        props.after?.let { style.append("margin-bottom:").append(fmt(it)).append("pt;") }
        props.line?.let {
            if (it > 0) style.append("line-height:").append(fmt(it * 1.15f)).append(';')
            else style.append("line-height:").append(fmt(-it)).append("pt;")
        }
        props.background?.let { style.append("background:").append(it).append(';') }
        props.right?.let { if (it > 0) style.append("margin-right:").append(fmt(it)).append("pt;") }
        // Tamaño base del párrafo, para que las líneas vacías tengan su alto real.
        runBase.sizePt?.let { style.append("font-size:").append(fmt(it)).append("pt;") }

        val list = props.numId?.takeIf { it != "0" }?.let { numbering.next(it, props.ilvl ?: 0) }
        var left = props.left
        var firstLine = props.firstLine
        if (list != null) {
            left = props.left ?: list.left
            firstLine = props.firstLine ?: -list.hanging
        }
        left?.let { if (it != 0f) style.append("margin-left:").append(fmt(it)).append("pt;") }
        firstLine?.let { if (it != 0f) style.append("text-indent:").append(fmt(it)).append("pt;") }

        out.append('<').append(tag)
        if (style.isNotEmpty()) out.append(" style=\"").append(style).append('"')
        out.append('>')
        if (list != null && list.text.isNotEmpty()) {
            val width = if (firstLine != null && firstLine < 0) -firstLine else 18f
            out.append("<span class=\"bu\" style=\"min-width:").append(fmt(width)).append("pt;text-indent:0\">")
            val marker = runBase.merge(list.run).css()
            if (marker.isNotEmpty()) out.append("<span style=\"").append(marker).append("\">")
            out.append(list.text.html())
            if (marker.isNotEmpty()) out.append("</span>")
            out.append("</span>")
        }
        if (content.isEmpty()) out.append("​") else out.append(content)
        out.append("</").append(tag).append('>')
        out.append(after)
    }

    private fun inline(nodes: List<XmlNode>, base: RunProps, out: StringBuilder, after: StringBuilder) {
        for (n in nodes) {
            when (n.name) {
                "r" -> run(n, base, out, after)
                "hyperlink" -> {
                    val rel = n["id"]?.let { currentRels[it] }
                    val href = when {
                        rel != null && rel.external -> rel.target
                        n["anchor"] != null -> "#bm-" + n["anchor"]
                        else -> null
                    }
                    if (href != null) out.append("<a href=\"").append(href.html()).append("\">")
                    inline(n.children, base, out, after)
                    if (href != null) out.append("</a>")
                }
                "bookmarkStart" -> n["name"]?.let { out.append("<a id=\"bm-").append(it.html()).append("\"></a>") }
                "ins", "smartTag", "customXml", "fldSimple", "dir", "bdo", "moveTo" -> inline(n.children, base, out, after)
                "sdt" -> n.child("sdtContent")?.let { inline(it.children, base, out, after) }
                "oMathPara", "oMath" -> {
                    out.append("<i>").append(n.allText("t").html()).append("</i>")
                }
                "AlternateContent" -> n.child("Choice")?.let { inline(it.children, base, out, after) }
            }
        }
    }

    private fun run(r: XmlNode, base: RunProps, out: StringBuilder, after: StringBuilder) {
        val rPr = r.child("rPr")
        var props = base
        rPr?.child("rStyle")?.get("val")?.let { props = props.merge(styleRun(it)) }
        rPr?.let { props = props.merge(parseRun(it)) }
        if (props.hidden == true) return

        val css = props.css()
        val text = StringBuilder()
        for (c in r.children) {
            when (c.name) {
                "t" -> text.append(c.text.html())
                "tab", "ptab" -> text.append('\t')
                "br", "cr" -> text.append("<br>")
                "noBreakHyphen" -> text.append('‑')
                "softHyphen" -> text.append("&shy;")
                "sym" -> c["char"]?.toIntOrNull(16)?.let { text.append(symbol(it).html()) }
                "drawing" -> drawing(c, text, after)
                "pict", "object" -> vml(c, text, after)
                "AlternateContent" -> {
                    val choice = c.child("Choice")
                    val drawing = choice?.find("drawing")
                    if (drawing != null) drawing(drawing, text, after)
                    else c.child("Fallback")?.find("pict")?.let { vml(it, text, after) }
                }
                "footnoteReference", "endnoteReference" -> {
                    val key = (if (c.name == "footnoteReference") "f" else "e") + c["id"]
                    if (key in notes) {
                        val number = usedNotes.size + 1
                        usedNotes.add(key to number)
                        text.append("<sup><a class=\"fn\" href=\"#n").append(number).append("\">")
                            .append(number).append("</a></sup>")
                    }
                }
            }
        }
        if (text.isEmpty()) return
        if (css.isEmpty()) out.append(text)
        else out.append("<span style=\"").append(css).append("\">").append(text).append("</span>")
    }

    private fun drawing(d: XmlNode, out: StringBuilder, after: StringBuilder) {
        val container = d.child("inline") ?: d.child("anchor") ?: d
        val extent = container.child("extent")
        val blips = container.findAll("blip")
        if (blips.size == 1) {
            image(blips[0]["embed"], extent?.get("cx"), extent?.get("cy"), out)
        } else {
            blips.forEach { blip ->
                // En grupos, cada imagen trae su propio tamaño en spPr/xfrm/ext.
                image(blip["embed"], null, null, out, fallbackCx = extent?.get("cx"), fallbackCy = extent?.get("cy"))
            }
        }
        container.findAll("txbxContent").forEach { textBox(it, after) }
        if (blips.isEmpty() && container.find("chart") != null) {
            out.append("<span class=\"ph\">").append(labels.chart.html()).append("</span>")
        }
    }

    private fun vml(node: XmlNode, out: StringBuilder, after: StringBuilder) {
        node.findAll("imagedata").forEach { data ->
            val shape = node.find("shape")
            val style = shape?.get("style").orEmpty()
            val w = Regex("width:([\\d.]+)pt").find(style)?.groupValues?.get(1)?.toFloatOrNull()
            val h = Regex("height:([\\d.]+)pt").find(style)?.groupValues?.get(1)?.toFloatOrNull()
            image(data["id"], w?.let { (it * 12700).toLong().toString() }, h?.let { (it * 12700).toLong().toString() }, out)
        }
        node.findAll("txbxContent").forEach { textBox(it, after) }
    }

    private fun textBox(content: XmlNode, after: StringBuilder) {
        val box = StringBuilder()
        blocks(content.children, box)
        if (box.isNotEmpty()) after.append("<div class=\"tb\">").append(box).append("</div>")
    }

    private fun image(
        relId: String?, cx: String?, cy: String?, out: StringBuilder,
        fallbackCx: String? = null, fallbackCy: String? = null,
    ) {
        val rel = relId?.let { currentRels[it] } ?: return
        if (rel.external) return
        val cls = images.classFor(rel.target) ?: return
        val w = (cx ?: fallbackCx)?.toLongOrNull()?.let { it / 9525 }?.takeIf { it > 0 } ?: 200
        val h = (cy ?: fallbackCy)?.toLongOrNull()?.let { it / 9525 }?.takeIf { it > 0 } ?: w
        out.append("<span class=\"img ").append(cls).append("\" style=\"width:").append(w)
            .append("px;max-width:100%;aspect-ratio:").append(w).append('/').append(h).append(";vertical-align:bottom\"></span>")
    }

    // endregion

    // region Tablas

    private fun table(tbl: XmlNode, out: StringBuilder) {
        val tblPr = tbl.child("tblPr")
        val styleNode = tblPr?.child("tblStyle")?.get("val")?.let { styleChain(it) }.orEmpty()
        val borderSources = listOfNotNull(tblPr?.child("tblBorders")) +
            styleNode.mapNotNull { it.path("tblPr", "tblBorders") }
        val bordered = borderSources.firstOrNull()?.children?.any { it["val"] !in listOf(null, "nil", "none") } == true

        class Cell(val node: XmlNode, val span: Int) { var rowSpan = 1 }

        val rows = ArrayList<List<Cell>>()
        val origin = HashMap<Int, Cell>() // columna → celda que empieza la fusión vertical
        for (tr in tbl.children("tr")) {
            val cells = ArrayList<Cell>()
            var col = 0
            for (tc in tr.children.flatMap { if (it.name == "sdt") it.child("sdtContent")?.children.orEmpty() else listOf(it) }) {
                if (tc.name != "tc") continue
                val tcPr = tc.child("tcPr")
                val span = tcPr?.child("gridSpan")?.get("val")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val vMerge = tcPr?.child("vMerge")
                if (vMerge != null && vMerge["val"] != "restart") {
                    origin[col]?.let { it.rowSpan++ }
                } else {
                    val cell = Cell(tc, span)
                    cells.add(cell)
                    for (i in col until col + span) origin[i] = cell
                }
                col += span
            }
            rows.add(cells)
        }

        val align = tblPr?.child("jc")?.get("val")
        out.append("<div class=\"tw\"><table")
        if (bordered) out.append(" class=\"bd\"")
        if (align == "center") out.append(" style=\"margin:0 auto\"")
        else if (align == "right" || align == "end") out.append(" style=\"margin-left:auto\"")
        out.append('>')
        for (cells in rows) {
            out.append("<tr>")
            for (cell in cells) {
                val tcPr = cell.node.child("tcPr")
                out.append("<td")
                if (cell.span > 1) out.append(" colspan=\"").append(cell.span).append('"')
                if (cell.rowSpan > 1) out.append(" rowspan=\"").append(cell.rowSpan).append('"')
                val style = StringBuilder()
                tcPr?.child("shd")?.get("fill")?.takeIf { it != "auto" }?.let(::officeHex)
                    ?.let { style.append("background:").append(it).append(';') }
                val width = tcPr?.child("tcW")
                if (width?.get("type") == "dxa") {
                    width["w"]?.toIntOrNull()?.takeIf { it > 0 }?.let { style.append("width:").append(it / 15).append("px;") }
                }
                when (tcPr?.child("vAlign")?.get("val")) {
                    "center" -> style.append("vertical-align:middle;")
                    "bottom" -> style.append("vertical-align:bottom;")
                }
                if (style.isNotEmpty()) out.append(" style=\"").append(style).append('"')
                out.append('>')
                blocks(cell.node.children, out)
                out.append("</td>")
            }
            out.append("</tr>")
        }
        out.append("</table></div>")
    }

    // endregion

    // region Listas

    private class ListMarker(val text: String, val left: Float, val hanging: Float, val run: RunProps)

    private inner class Numbering {
        private inner class Level(val format: String, val text: String, val start: Int, val left: Float?, val hanging: Float?, val run: RunProps)

        private val abstracts = HashMap<String, Array<Level?>>()
        private val nums = HashMap<String, String>()
        private val startOverrides = HashMap<String, Map<Int, Int>>()
        private val counters = HashMap<String, IntArray>()

        fun load(root: XmlNode) {
            root.children("abstractNum").forEach { a ->
                val id = a["abstractNumId"] ?: return@forEach
                val levels = arrayOfNulls<Level>(9)
                a.children("lvl").forEach { lvl ->
                    val i = lvl["ilvl"]?.toIntOrNull()?.takeIf { it in 0..8 } ?: return@forEach
                    val ind = lvl.path("pPr", "ind")
                    levels[i] = Level(
                        format = lvl.child("numFmt")?.get("val") ?: "decimal",
                        text = lvl.child("lvlText")?.get("val") ?: "",
                        start = lvl.child("start")?.get("val")?.toIntOrNull() ?: 1,
                        left = (ind?.get("left") ?: ind?.get("start"))?.toFloatOrNull()?.div(20f),
                        hanging = ind?.get("hanging")?.toFloatOrNull()?.div(20f),
                        run = lvl.child("rPr")?.let { r -> parseRun(r) } ?: RunProps(),
                    )
                }
                abstracts[id] = levels
            }
            root.children("num").forEach { n ->
                val id = n["numId"] ?: return@forEach
                nums[id] = n.child("abstractNumId")?.get("val") ?: return@forEach
                val overrides = n.children("lvlOverride").mapNotNull { o ->
                    val lvl = o["ilvl"]?.toIntOrNull() ?: return@mapNotNull null
                    val start = o.child("startOverride")?.get("val")?.toIntOrNull() ?: return@mapNotNull null
                    lvl to start
                }.toMap()
                if (overrides.isNotEmpty()) startOverrides[id] = overrides
            }
        }

        /** Avanza el contador de la lista y devuelve su viñeta o número. */
        fun next(numId: String, ilvl: Int): ListMarker? {
            val levels = nums[numId]?.let { abstracts[it] } ?: return null
            val level = ilvl.coerceIn(0, 8)
            val def = levels[level] ?: return null
            val key = nums[numId]!!
            val counts = counters.getOrPut(key) {
                IntArray(9) { i -> (startOverrides[numId]?.get(i) ?: levels[i]?.start ?: 1) - 1 }
            }
            counts[level]++
            for (i in level + 1 until 9) counts[i] = (levels[i]?.start ?: 1) - 1

            val text = if (def.format == "bullet") {
                bullet(def.text)
            } else if (def.format == "none") {
                ""
            } else {
                Regex("%(\\d)").replace(def.text) { m ->
                    val i = (m.groupValues[1].toInt() - 1).coerceIn(0, 8)
                    formatNumber(counts[i].coerceAtLeast(1), levels[i]?.format ?: "decimal")
                }
            }
            return ListMarker(text, def.left ?: (18f * (level + 1)), def.hanging ?: 18f, def.run)
        }

        private fun bullet(text: String): String {
            val c = text.firstOrNull() ?: return "•"
            return when {
                c == 'o' -> "◦"
                c.code in 0xF000..0xF0FF -> when (c.code) {
                    0xF0A7, 0xF06E -> "▪"
                    0xF0D8 -> "➢"
                    0xF0FC -> "✓"
                    0xF076 -> "❖"
                    0xF0A8 -> "◆"
                    else -> "•"
                }
                c == '§' -> "▪"
                else -> text
            }
        }
    }

    private fun formatNumber(n: Int, format: String): String = when (format) {
        "lowerLetter" -> letters(n).lowercase()
        "upperLetter" -> letters(n)
        "lowerRoman" -> roman(n).lowercase()
        "upperRoman" -> roman(n)
        "decimalZero" -> if (n < 10) "0$n" else n.toString()
        else -> n.toString()
    }

    private fun letters(n: Int): String {
        val letter = 'A' + (n - 1) % 26
        return letter.toString().repeat((n - 1) / 26 + 1)
    }

    private fun roman(n: Int): String {
        if (n <= 0 || n >= 4000) return n.toString()
        val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val symbols = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var rest = n
        return buildString {
            for (i in values.indices) while (rest >= values[i]) { append(symbols[i]); rest -= values[i] }
        }
    }

    // endregion

    private fun symbol(code: Int): String {
        val c = if (code in 0xF000..0xF0FF) code - 0xF000 else code
        return when (c) {
            0xB7 -> "•"
            0xFC -> "✓"
            0xFB -> "✗"
            0xA7 -> "▪"
            0xE0 -> "→"
            else -> String(Character.toChars(c))
        }
    }

    private companion object {
        val HIGHLIGHTS = mapOf(
            "yellow" to "#ffff00", "green" to "#00ff00", "cyan" to "#00ffff", "magenta" to "#ff00ff",
            "blue" to "#0000ff", "red" to "#ff0000", "darkBlue" to "#000080", "darkCyan" to "#008080",
            "darkGreen" to "#008000", "darkMagenta" to "#800080", "darkRed" to "#800000",
            "darkYellow" to "#808000", "darkGray" to "#808080", "lightGray" to "#c0c0c0", "black" to "#000000",
            "white" to "#ffffff",
        )
    }
}

/** `<w:b/>` significa activado; `<w:b w:val="0"/>` o `"false"`, desactivado. */
private fun String?.isOn() = this == null || (this != "0" && this != "false" && this != "off")

internal fun fmt(value: Float): String {
    val rounded = Math.round(value * 100) / 100f
    return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString() else rounded.toString()
}
