package com.monkeypdf.app

import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Convierte una presentación de PowerPoint (.pptx) a HTML: cada diapositiva es un lienzo de
 * tamaño fijo donde los cuadros de texto, imágenes, formas, tablas y SmartArt se colocan en su
 * posición. Hereda posiciones, fondos y estilos del diseño y del patrón de diapositivas.
 * No reproduce animaciones, transiciones, gráficos ni formas complejas (se dibujan como rectángulos).
 */
class PptxToHtml(private val pkg: OfficePackage, private val labels: OfficeLabels) {

    private val presPath = pkg.mainPart() ?: "ppt/presentation.xml"
    private val presRels = pkg.rels(presPath)
    private val images = ImageRegistry(pkg)
    private var slideW = 9144000L
    private var slideH = 6858000L
    private var scale = SLIDE_PX / 9144000.0
    private var defaultTextStyle: XmlNode? = null

    private class Part(val path: String, val xml: XmlNode, val rels: Map<String, Rel>)
    private class Master(val part: Part, val theme: Map<String, String>, val clrMap: Map<String, String>)

    private val masters = HashMap<String, Master?>()
    private val layouts = HashMap<String, Part?>()

    /** Contexto de dibujo: la parte (para resolver imágenes) y los colores vigentes. */
    private inner class Ctx(val part: Part, val master: Master, val clrMap: Map<String, String>, val slideNumber: Int) {
        fun withPart(other: Part) = Ctx(other, master, clrMap, slideNumber)
    }

    /** Transformación de coordenadas EMU (de grupos anidados) a EMU de la diapositiva. */
    private class Tx(val ax: Double, val ox: Double, val ay: Double, val oy: Double) {
        fun x(v: Double) = ox + v * ax
        fun y(v: Double) = oy + v * ay
    }

    fun convert(): String {
        val pres = pkg.xml(presPath) ?: throw InvalidOfficeException("Falta la presentación")
        pres.child("sldSz")?.let { s ->
            s["cx"]?.toLongOrNull()?.takeIf { it > 0 }?.let { slideW = it }
            s["cy"]?.toLongOrNull()?.takeIf { it > 0 }?.let { slideH = it }
        }
        scale = SLIDE_PX / slideW.toDouble()
        defaultTextStyle = pres.child("defaultTextStyle")

        val slidePaths = pres.child("sldIdLst")?.children("sldId")?.mapNotNull { s ->
            s["id"]?.let { presRels[it] }?.takeIf { !it.external }?.target
        }.orEmpty()
        val body = StringBuilder()
        val heightPx = (slideH * scale).roundToInt()
        var number = 0
        for (path in slidePaths) {
            val xml = pkg.xml(path) ?: continue
            number++
            body.append(slideHtml(Part(path, xml, pkg.rels(path)), number, heightPx))
            body.append("<div class=\"cap\">").append(labels.slide(number, slidePaths.size).html()).append("</div>")
        }
        if (number == 0) throw InvalidOfficeException("Presentación sin diapositivas")

        val css = """
            body{margin:0;padding:12px 0 24px;background:#e4e4e7;font-family:sans-serif}
            .slide{position:relative;overflow:hidden;width:${SLIDE_PX}px;height:${heightPx}px;margin:0 auto;
              background:#fff;box-shadow:0 1px 4px rgba(0,0,0,.3);color:#000;line-height:1.2}
            .cap{text-align:center;color:#666;font-size:22px;margin:8px 0 22px}
            .sh{position:absolute;box-sizing:border-box}
            .sh>.img{position:absolute;left:0;top:0;width:100%;height:100%}
            .tx{position:absolute;left:0;top:0;right:0;bottom:0;display:flex;flex-direction:column;
              white-space:pre-wrap;overflow-wrap:break-word;box-sizing:border-box}
            .tx p{margin:0}
            .bu{display:inline-block;text-indent:0;white-space:pre}
            table.pt{position:absolute;border-collapse:collapse;table-layout:fixed}
            table.pt td{vertical-align:top;overflow:hidden;white-space:pre-wrap;overflow-wrap:break-word;box-sizing:border-box}
            table.pt p{margin:0}
            .ph{position:absolute;display:flex;align-items:center;justify-content:center;background:#f1f1f1;
              color:#777;border:2px dashed #bbb;box-sizing:border-box;font-size:20px}
        """.trimIndent()
        return officeHtmlPage("width=${SLIDE_PX + 32}", css + images.css(), body.toString())
    }

    // region Diapositiva

    private fun layoutOf(slide: Part): Part? {
        val path = slide.rels.values.firstOrNull { it.type.endsWith("/slideLayout") }?.target ?: return null
        return layouts.getOrPut(path) { pkg.xml(path)?.let { Part(path, it, pkg.rels(path)) } }
    }

    private fun masterOf(layout: Part?): Master? {
        val path = layout?.rels?.values?.firstOrNull { it.type.endsWith("/slideMaster") }?.target ?: return null
        return masters.getOrPut(path) {
            val xml = pkg.xml(path) ?: return@getOrPut null
            val part = Part(path, xml, pkg.rels(path))
            val theme = part.rels.values.firstOrNull { it.type.endsWith("/theme") }?.target?.let { pkg.xml(it) }
            Master(part, theme?.let(::themeColors).orEmpty(), clrMapOf(xml.child("clrMap")) ?: DEFAULT_CLR_MAP)
        }
    }

    private fun themeColors(theme: XmlNode): Map<String, String> {
        val scheme = theme.find("clrScheme") ?: return emptyMap()
        return scheme.children.mapNotNull { c ->
            val hex = c.child("srgbClr")?.get("val") ?: c.child("sysClr")?.get("lastClr")
            officeHex(hex)?.let { c.name to it }
        }.toMap()
    }

    private fun clrMapOf(node: XmlNode?): Map<String, String>? {
        node ?: return null
        return listOf("bg1", "tx1", "bg2", "tx2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink")
            .associateWith { node[it] ?: DEFAULT_CLR_MAP.getValue(it) }
    }

    private fun slideHtml(slide: Part, number: Int, heightPx: Int): String {
        val layout = layoutOf(slide)
        val master = masterOf(layout) ?: Master(slide, emptyMap(), DEFAULT_CLR_MAP)
        val clrMap = clrMapOf(slide.xml.path("clrMapOvr", "overrideClrMapping"))
            ?: clrMapOf(layout?.xml?.path("clrMapOvr", "overrideClrMapping"))
            ?: master.clrMap
        val ctx = Ctx(slide, master, clrMap, number)
        val out = StringBuilder()
        out.append("<section class=\"slide\"")
        val bg = background(slide, ctx) ?: layout?.let { background(it, ctx.withPart(it)) }
            ?: background(master.part, ctx.withPart(master.part))
        bg?.color?.let { out.append(" style=\"background:").append(it).append('"') }
        out.append('>')
        bg?.image?.let { out.append("<div class=\"img ").append(it).append("\" style=\"position:absolute;left:0;top:0;width:100%;height:100%\"></div>") }

        val root = Tx(1.0, 0.0, 1.0, 0.0)
        val inherit = Inherit(layout, master)
        val showMaster = slide.xml["showMasterSp"] != "0" && layout?.xml?.get("showMasterSp") != "0"
        if (showMaster && master.part !== slide) {
            master.part.xml.path("cSld", "spTree")?.let { shapes(it, ctx.withPart(master.part), root, out, null) }
        }
        if (slide.xml["showMasterSp"] != "0" && layout != null) {
            layout.xml.path("cSld", "spTree")?.let { shapes(it, ctx.withPart(layout), root, out, null) }
        }
        slide.xml.path("cSld", "spTree")?.let { shapes(it, ctx, root, out, inherit) }
        out.append("</section>")
        return out.toString()
    }

    /** Fondo definido en una parte (diapositiva, diseño o patrón), o null si no define uno. */
    private fun background(part: Part, ctx: Ctx): Fill? {
        val bg = part.xml.path("cSld", "bg") ?: return null
        bg.child("bgPr")?.let { return fillCss(it, ctx) }
        bg.child("bgRef")?.let { ref -> return color(ref, ctx)?.let { Fill(it, null) } }
        return null
    }

    // endregion

    // region Formas

    /** Marcadores de posición del diseño y del patrón, de donde heredan posición y estilo. */
    private inner class Inherit(val layout: Part?, val master: Master) {
        fun layoutPh(ph: XmlNode): XmlNode? = layout?.xml?.path("cSld", "spTree")?.let { findPh(it, ph, true) }
        fun masterPh(ph: XmlNode): XmlNode? = master.part.xml.path("cSld", "spTree")?.let { findPh(it, ph, false) }
    }

    private fun phType(ph: XmlNode): String = when (val t = ph["type"]) {
        null, "obj" -> "body"
        "ctrTitle" -> "title"
        "subTitle" -> "body"
        else -> t
    }

    private fun findPh(tree: XmlNode, ph: XmlNode, byIdx: Boolean): XmlNode? {
        val candidates = tree.children.filter { it.name == "sp" || it.name == "pic" || it.name == "graphicFrame" }
            .mapNotNull { s -> placeholderOf(s)?.let { s to it } }
        val idx = ph["idx"]
        if (byIdx && idx != null) candidates.firstOrNull { it.second["idx"] == idx }?.let { return it.first }
        val type = phType(ph)
        return candidates.firstOrNull { phType(it.second) == type }?.first
    }

    private fun placeholderOf(shape: XmlNode): XmlNode? =
        (shape.child("nvSpPr") ?: shape.child("nvPicPr") ?: shape.child("nvGraphicFramePr"))?.path("nvPr", "ph")

    private fun shapes(tree: XmlNode, ctx: Ctx, tx: Tx, out: StringBuilder, inherit: Inherit?) {
        for (n in tree.children) {
            when (n.name) {
                "sp" -> shape(n, ctx, tx, out, inherit)
                "pic" -> picture(n, ctx, tx, out, inherit)
                "grpSp" -> {
                    val xfrm = n.path("grpSpPr", "xfrm")
                    val off = xfrm?.child("off")
                    val ext = xfrm?.child("ext")
                    val chOff = xfrm?.child("chOff")
                    val chExt = xfrm?.child("chExt")
                    val sx = ratio(ext?.get("cx"), chExt?.get("cx"))
                    val sy = ratio(ext?.get("cy"), chExt?.get("cy"))
                    val offX = off.num("x") - chOff.num("x") * sx
                    val offY = off.num("y") - chOff.num("y") * sy
                    val child = Tx(tx.ax * sx, tx.ox + tx.ax * offX, tx.ay * sy, tx.oy + tx.ay * offY)
                    shapes(n, ctx, child, out, inherit)
                }
                "graphicFrame" -> graphicFrame(n, ctx, tx, out, inherit)
                "cxnSp" -> connector(n, ctx, tx, out)
                "AlternateContent" -> (n.child("Fallback") ?: n.child("Choice"))?.let { shapes(it, ctx, tx, out, inherit) }
            }
        }
    }

    private fun ratio(a: String?, b: String?): Double {
        val num = a?.toDoubleOrNull() ?: return 1.0
        val den = b?.toDoubleOrNull()?.takeIf { it != 0.0 } ?: return 1.0
        return num / den
    }

    private fun XmlNode?.num(attr: String): Double = this?.get(attr)?.toDoubleOrNull() ?: 0.0

    private class Box(val x: Double, val y: Double, val w: Double, val h: Double, val rot: Double, val flipH: Boolean, val flipV: Boolean)

    private fun boxOf(xfrm: XmlNode?, tx: Tx): Box? {
        val off = xfrm?.child("off") ?: return null
        val ext = xfrm.child("ext") ?: return null
        val x = tx.x(off.num("x"))
        val y = tx.y(off.num("y"))
        return Box(
            x * scale, y * scale, ext.num("cx") * tx.ax * scale, ext.num("cy") * tx.ay * scale,
            xfrm.num("rot") / 60000.0, xfrm["flipH"] == "1", xfrm["flipV"] == "1",
        )
    }

    private fun StringBuilder.position(box: Box) {
        append("left:").append(px(box.x)).append("px;top:").append(px(box.y)).append("px;width:")
            .append(px(box.w)).append("px;height:").append(px(box.h)).append("px;")
        val transforms = ArrayList<String>()
        if (box.rot != 0.0) transforms.add("rotate(${fmt(box.rot.toFloat())}deg)")
        if (box.flipH) transforms.add("scaleX(-1)")
        if (box.flipV) transforms.add("scaleY(-1)")
        if (transforms.isNotEmpty()) append("transform:").append(transforms.joinToString(" ")).append(';')
    }

    private fun px(v: Double) = fmt(v.toFloat())

    private fun shape(sp: XmlNode, ctx: Ctx, tx: Tx, out: StringBuilder, inherit: Inherit?) {
        val ph = placeholderOf(sp)
        // Los marcadores del diseño y del patrón solo son plantillas ("Haga clic para agregar título").
        if (ph != null && inherit == null) return
        val layoutPh = if (ph != null) inherit?.layoutPh(ph) else null
        val masterPh = if (ph != null) inherit?.masterPh(ph) else null

        val spPr = sp.child("spPr")
        val xfrm = spPr?.child("xfrm")?.takeIf { it.child("off") != null }
            ?: layoutPh?.path("spPr", "xfrm")?.takeIf { it.child("off") != null }
            ?: masterPh?.path("spPr", "xfrm")
        val box = boxOf(xfrm, tx) ?: return
        val txBody = sp.child("txBody")
        val hasText = txBody?.findAll("t")?.any { it.text.isNotBlank() } == true

        val style = sp.child("style")
        val fill = fillCss(spPr, ctx) ?: style?.child("fillRef")?.takeIf { (it["idx"] ?: "0") != "0" }
            ?.let { ref -> color(ref, ctx)?.let { Fill(it, null) } }
        val line = lineCss(spPr?.child("ln"), style, ctx)
        if (!hasText && fill == null && line == null) return

        out.append("<div class=\"sh\" style=\"")
        out.position(box)
        if (fill?.color != null) out.append("background:").append(fill.color).append(';')
        line?.let { out.append(it) }
        when (spPr?.child("prstGeom")?.get("prst")) {
            "ellipse", "circle", "donut", "pie", "chord" -> out.append("border-radius:50%;")
            "roundRect", "round2SameRect", "snipRoundRect" ->
                out.append("border-radius:").append(px(min(box.w, box.h) * 0.16)).append("px;")
        }
        out.append("\">")
        fill?.image?.let { out.append("<div class=\"img ").append(it).append("\"></div>") }
        if (hasText && txBody != null) {
            val kind = when (ph?.let(::phType)) {
                null -> TextKind.OTHER
                "title" -> TextKind.TITLE
                "body" -> TextKind.BODY
                else -> TextKind.OTHER_PH
            }
            val fontRefColor = style?.child("fontRef")?.let { color(it, ctx) }
            textBody(txBody, ctx, kind, listOfNotNull(layoutPh?.child("txBody"), masterPh?.child("txBody")), fontRefColor, out)
        }
        out.append("</div>")
    }

    private fun picture(pic: XmlNode, ctx: Ctx, tx: Tx, out: StringBuilder, inherit: Inherit?) {
        val ph = placeholderOf(pic)
        if (ph != null && inherit == null) return
        val xfrm = pic.path("spPr", "xfrm")?.takeIf { it.child("off") != null }
            ?: ph?.let { inherit?.layoutPh(it) }?.path("spPr", "xfrm")
        val box = boxOf(xfrm, tx) ?: return
        val blipFill = pic.child("blipFill") ?: return
        val cls = blipFill.child("blip")?.get("embed")?.let { ctx.part.rels[it] }?.takeIf { !it.external }
            ?.let { images.classFor(it.target) } ?: return
        out.append("<div class=\"img ").append(cls).append("\" style=\"position:absolute;")
        out.position(box)
        blipFill.child("srcRect")?.let { crop(it, out) }
        if (pic.path("spPr", "prstGeom")?.get("prst") == "ellipse") out.append("border-radius:50%;")
        out.append("\"></div>")
    }

    /** Recorte de imagen (`srcRect`, en milésimas de porcentaje) como tamaño/posición del fondo. */
    private fun crop(rect: XmlNode, out: StringBuilder) {
        val l = rect.num("l") / 100000
        val t = rect.num("t") / 100000
        val r = rect.num("r") / 100000
        val b = rect.num("b") / 100000
        if (l == 0.0 && t == 0.0 && r == 0.0 && b == 0.0) return
        val w = 1 - l - r
        val h = 1 - t - b
        if (w <= 0 || h <= 0) return
        out.append("background-size:").append(fmt((100 / w).toFloat())).append("% ").append(fmt((100 / h).toFloat())).append("%;")
        val px = if (l + r != 0.0) l / (l + r) * 100 else 0.0
        val py = if (t + b != 0.0) t / (t + b) * 100 else 0.0
        out.append("background-position:").append(fmt(px.toFloat())).append("% ").append(fmt(py.toFloat())).append("%;")
    }

    private fun connector(cxn: XmlNode, ctx: Ctx, tx: Tx, out: StringBuilder) {
        val box = boxOf(cxn.path("spPr", "xfrm"), tx) ?: return
        val ln = cxn.path("spPr", "ln")
        if (ln?.child("noFill") != null) return
        val color = ln?.child("solidFill")?.let { color(it, ctx) }
            ?: cxn.path("style", "lnRef")?.let { color(it, ctx) } ?: "#000"
        val width = max(1.0, (ln?.get("w")?.toDoubleOrNull() ?: 12700.0) * scale)
        var x1 = box.x
        var y1 = box.y
        var x2 = box.x + box.w
        var y2 = box.y + box.h
        if (box.flipH) { val t = x1; x1 = x2; x2 = t }
        if (box.flipV) { val t = y1; y1 = y2; y2 = t }
        val length = sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))
        val angle = Math.toDegrees(atan2(y2 - y1, x2 - x1)) + box.rot
        out.append("<div style=\"position:absolute;left:").append(px(x1)).append("px;top:").append(px(y1 - width / 2))
            .append("px;width:").append(px(length)).append("px;border-top:").append(px(width)).append("px ")
            .append(if (ln?.child("prstDash")?.get("val")?.contains("dash") == true) "dashed" else "solid")
            .append(' ').append(color).append(";transform-origin:0 50%;transform:rotate(")
            .append(fmt(angle.toFloat())).append("deg)\"></div>")
    }

    private fun graphicFrame(frame: XmlNode, ctx: Ctx, tx: Tx, out: StringBuilder, inherit: Inherit?) {
        if (placeholderOf(frame) != null && inherit == null) return
        val box = boxOf(frame.child("xfrm"), tx) ?: return
        val data = frame.path("graphic", "graphicData") ?: return
        val uri = data["uri"].orEmpty()
        val table = data.child("tbl")
        when {
            table != null -> table(table, box, ctx, out)
            uri.endsWith("/diagram") -> smartArt(data, box, ctx, out)
            else -> {
                // Gráficos y otros objetos incrustados: si traen imagen de respaldo, se usa.
                val fallback = frame.find("blip")?.get("embed")?.let { ctx.part.rels[it] }?.let { images.classFor(it.target) }
                if (fallback != null) {
                    out.append("<div class=\"img ").append(fallback).append("\" style=\"position:absolute;")
                    out.position(box)
                    out.append("\"></div>")
                } else {
                    out.append("<div class=\"ph\" style=\"")
                    out.position(box)
                    out.append("\">").append(labels.chart.html()).append("</div>")
                }
            }
        }
    }

    /** SmartArt: PowerPoint guarda una copia ya dibujada (diagramDrawing) que se puede reutilizar. */
    private fun smartArt(data: XmlNode, box: Box, ctx: Ctx, out: StringBuilder) {
        val rels = ctx.part.rels
        val dataPath = data.child("relIds")?.get("dm")?.let { rels[it] }?.target
        val drawingRelId = dataPath?.let { pkg.xml(it) }?.find("dataModelExt")?.get("relId")
        val drawingPath = drawingRelId?.let { rels[it] }?.target
            ?: rels.values.singleOrNull { it.type.endsWith("/diagramDrawing") }?.target
        val drawing = drawingPath?.let { pkg.xml(it) }
        val tree = drawing?.child("spTree")
        if (tree == null) {
            out.append("<div class=\"ph\" style=\"")
            out.position(box)
            out.append("\">SmartArt</div>")
            return
        }
        val part = Part(drawingPath, drawing, pkg.rels(drawingPath))
        val frameTx = Tx(1.0, box.x / scale, 1.0, box.y / scale)
        shapes(tree, ctx.withPart(part), frameTx, out, null)
    }

    private fun table(tbl: XmlNode, box: Box, ctx: Ctx, out: StringBuilder) {
        val tblPr = tbl.child("tblPr")
        val styled = tblPr?.child("tableStyleId") != null || tblPr?.get("firstRow") == "1"
        val firstRow = tblPr?.get("firstRow") == "1"
        val banded = tblPr?.get("bandRow") == "1"
        val accent = ctx.master.theme["accent1"] ?: "#4472c4"
        val widths = tbl.child("tblGrid")?.children("gridCol")?.map { it.num("w") * scale }.orEmpty()
        out.append("<table class=\"pt\" style=\"left:").append(px(box.x)).append("px;top:").append(px(box.y))
            .append("px;width:").append(px(widths.sum().takeIf { it > 0 } ?: box.w)).append("px\"><colgroup>")
        widths.forEach { out.append("<col style=\"width:").append(px(it)).append("px\">") }
        out.append("</colgroup>")
        tbl.children("tr").forEachIndexed { rowIndex, tr ->
            out.append("<tr style=\"height:").append(px(tr.num("h") * scale)).append("px\">")
            val header = firstRow && rowIndex == 0
            for (tc in tr.children("tc")) {
                if (tc["hMerge"] == "1" || tc["vMerge"] == "1") continue
                val tcPr = tc.child("tcPr")
                out.append("<td")
                tc["gridSpan"]?.toIntOrNull()?.takeIf { it > 1 }?.let { out.append(" colspan=\"").append(it).append('"') }
                tc["rowSpan"]?.toIntOrNull()?.takeIf { it > 1 }?.let { out.append(" rowspan=\"").append(it).append('"') }
                out.append(" style=\"")
                val fill = tcPr?.let { fillCss(it, ctx) }?.color
                val background = fill ?: when {
                    !styled -> null
                    header -> accent
                    banded && (rowIndex - (if (firstRow) 1 else 0)) % 2 == 0 -> mix(accent, 0.25)
                    else -> mix(accent, 0.12)
                }
                background?.let { out.append("background:").append(it).append(';') }
                val pad = { attr: String, default: Double -> px((tcPr?.get(attr)?.toDoubleOrNull() ?: default) * scale) }
                out.append("padding:").append(pad("marT", 45720.0)).append("px ").append(pad("marR", 91440.0)).append("px ")
                    .append(pad("marB", 45720.0)).append("px ").append(pad("marL", 91440.0)).append("px;")
                for ((side, css) in listOf("lnL" to "left", "lnR" to "right", "lnT" to "top", "lnB" to "bottom")) {
                    val ln = tcPr?.child(side)
                    val color = ln?.child("solidFill")?.let { color(it, ctx) }
                    if (color != null) {
                        out.append("border-").append(css).append(':')
                            .append(px(max(1.0, ln.num("w").takeIf { it > 0 }?.times(scale) ?: 1.0))).append("px solid ")
                            .append(color).append(';')
                    } else if (styled) {
                        out.append("border-").append(css).append(":1px solid #fff;")
                    } else {
                        out.append("border-").append(css).append(":1px solid #999;")
                    }
                }
                when (tcPr?.get("anchor")) {
                    "ctr" -> out.append("vertical-align:middle;")
                    "b" -> out.append("vertical-align:bottom;")
                }
                out.append("\">")
                tc.child("txBody")?.let { body ->
                    paragraphs(body, ctx, TextKind.TABLE, emptyList(), if (header && styled) "#ffffff" else null, 1.0, out, header && styled)
                }
                out.append("</td>")
            }
            out.append("</tr>")
        }
        out.append("</table>")
    }

    // endregion

    // region Texto

    private enum class TextKind { TITLE, BODY, OTHER_PH, OTHER, TABLE }

    private fun textBody(
        txBody: XmlNode, ctx: Ctx, kind: TextKind, inherited: List<XmlNode>, fontRefColor: String?, out: StringBuilder,
    ) {
        val bodies = listOf(txBody) + inherited
        fun bodyAttr(name: String) = bodies.firstNotNullOfOrNull { it.child("bodyPr")?.get(name) }
        val anchor = bodyAttr("anchor") ?: "t"
        val inset = { name: String, default: Double -> (bodyAttr(name)?.toDoubleOrNull() ?: default) * scale }
        val fontScale = bodies.firstNotNullOfOrNull { it.child("bodyPr")?.child("normAutofit")?.get("fontScale") }
            ?.toDoubleOrNull()?.div(100000) ?: 1.0

        out.append("<div class=\"tx\" style=\"padding:").append(px(inset("tIns", 45720.0))).append("px ")
            .append(px(inset("rIns", 91440.0))).append("px ").append(px(inset("bIns", 45720.0))).append("px ")
            .append(px(inset("lIns", 91440.0))).append("px;justify-content:")
            .append(
                when (anchor) {
                    "ctr" -> "center"
                    "b" -> "flex-end"
                    else -> "flex-start"
                }
            ).append(';')
        if (bodyAttr("wrap") == "none") out.append("white-space:pre;")
        if (bodyAttr("vert")?.startsWith("vert") == true) out.append("writing-mode:vertical-rl;")
        out.append("\">")
        paragraphs(txBody, ctx, kind, inherited, null, fontScale, out, false, fontRefColor)
        out.append("</div>")
    }

    private fun paragraphs(
        txBody: XmlNode, ctx: Ctx, kind: TextKind, inherited: List<XmlNode>, overrideColor: String?,
        fontScale: Double, out: StringBuilder, forceBold: Boolean, shapeColor: String? = null,
    ) {
        val master = ctx.master.part.xml.child("txStyles")
        val baseStyle = when (kind) {
            TextKind.TITLE -> master?.child("titleStyle")
            TextKind.BODY -> master?.child("bodyStyle")
            TextKind.OTHER_PH -> master?.child("otherStyle")
            TextKind.OTHER, TextKind.TABLE -> defaultTextStyle
        }
        val styleLists = listOfNotNull(txBody.child("lstStyle")) + inherited.mapNotNull { it.child("lstStyle") } +
            listOfNotNull(baseStyle)
        val counters = IntArray(9)
        var lastNumberedLevel = -1

        for (p in txBody.children("p")) {
            val pPr = p.child("pPr")
            val level = (pPr?.get("lvl")?.toIntOrNull() ?: 0).coerceIn(0, 8)
            val lvlName = "lvl${level + 1}pPr"
            val sources = listOfNotNull(pPr) + styleLists.mapNotNull { it.child(lvlName) }
            fun pAttr(name: String) = sources.firstNotNullOfOrNull { it[name] }
            fun rAttr(name: String) = sources.firstNotNullOfOrNull { it.child("defRPr")?.get(name) }

            val defaultSizePt = (rAttr("sz")?.toDoubleOrNull()?.div(100) ?: if (kind == TextKind.TITLE) 44.0 else 18.0) * fontScale
            // Prioridad del color: el propio cuadro de texto, luego el estilo de la forma
            // (p. ej. texto blanco sobre una forma de color) y por último los estilos heredados.
            fun colorIn(list: List<XmlNode>) = list.firstNotNullOfOrNull { it.child("defRPr")?.child("solidFill") }?.let { color(it, ctx) }
            val ownSources = listOfNotNull(pPr, txBody.child("lstStyle")?.child(lvlName))
            val defaultColor = overrideColor ?: colorIn(ownSources) ?: shapeColor ?: colorIn(sources)
            val baseColor = defaultColor ?: colorByName("tx1", ctx)

            val runs = p.children.filter { it.name == "r" || it.name == "br" || it.name == "fld" }
            val isEmpty = runs.none { it.name != "br" && it.child("t")?.text?.isNotEmpty() == true }

            val style = StringBuilder()
            when (pAttr("algn")) {
                "ctr" -> style.append("text-align:center;")
                "r" -> style.append("text-align:right;")
                "just", "dist" -> style.append("text-align:justify;")
            }
            val marL = pAttr("marL")?.toDoubleOrNull()?.times(scale) ?: 0.0
            val indent = pAttr("indent")?.toDoubleOrNull()?.times(scale) ?: 0.0
            if (marL != 0.0) style.append("padding-left:").append(px(marL)).append("px;")
            if (indent != 0.0) style.append("text-indent:").append(px(indent)).append("px;")
            val ptPx = 12700 * scale
            spacing(sources, "spcBef", defaultSizePt, ptPx)?.let { style.append("margin-top:").append(px(it)).append("px;") }
            spacing(sources, "spcAft", defaultSizePt, ptPx)?.let { style.append("margin-bottom:").append(px(it)).append("px;") }
            sources.firstNotNullOfOrNull { it.child("lnSpc") }?.let { ln ->
                ln.child("spcPct")?.get("val")?.toDoubleOrNull()?.let { style.append("line-height:").append(fmt((it / 100000 * 1.2).toFloat())).append(';') }
                ln.child("spcPts")?.get("val")?.toDoubleOrNull()?.let { style.append("line-height:").append(px(it / 100 * ptPx)).append("px;") }
            }
            style.append("font-size:").append(px(defaultSizePt * ptPx)).append("px;")
            style.append("color:").append(baseColor).append(';')

            out.append("<p style=\"").append(style).append("\">")

            // Viñeta o numeración automática.
            val bulletSource = sources.firstOrNull { s -> s.children.any { it.name in BULLET_TAGS } }
            val bulletNode = bulletSource?.children?.firstOrNull { it.name in BULLET_TAGS }
            if (!isEmpty && bulletNode != null && bulletNode.name != "buNone") {
                val bulletText = when (bulletNode.name) {
                    "buChar" -> bulletChar(bulletNode["char"] ?: "•")
                    "buAutoNum" -> {
                        if (lastNumberedLevel != level) {
                            counters[level] = (bulletNode["startAt"]?.toIntOrNull() ?: 1) - 1
                        }
                        counters[level]++
                        lastNumberedLevel = level
                        autoNumber(counters[level], bulletNode["type"] ?: "arabicPeriod")
                    }
                    else -> "•"
                }
                val bulletColor = bulletSource.child("buClr")?.let { color(it, ctx) }
                out.append("<span class=\"bu\" style=\"min-width:").append(px(max(-indent, 0.0))).append("px;")
                bulletColor?.let { out.append("color:").append(it).append(';') }
                out.append("\">").append(bulletText.html()).append("</span>")
            } else if (bulletNode?.name != "buAutoNum") {
                lastNumberedLevel = -1
            }

            if (isEmpty) {
                out.append('​')
            } else {
                for (r in runs) {
                    if (r.name == "br") { out.append("<br>"); continue }
                    var text = r.child("t")?.text.orEmpty()
                    if (r.name == "fld" && r["type"] == "slidenum") text = ctx.slideNumber.toString()
                    if (text.isEmpty()) continue
                    val rPr = r.child("rPr")
                    val css = StringBuilder()
                    rPr?.get("sz")?.toDoubleOrNull()?.let { css.append("font-size:").append(px(it / 100 * fontScale * ptPx)).append("px;") }
                    val bold = rPr?.get("b") ?: rAttr("b")
                    if (bold == "1" || bold == "true" || (forceBold && bold == null)) css.append("font-weight:bold;")
                    val italic = rPr?.get("i") ?: rAttr("i")
                    if (italic == "1" || italic == "true") css.append("font-style:italic;")
                    val u = rPr?.get("u") ?: rAttr("u")
                    val strike = rPr?.get("strike") ?: rAttr("strike")
                    val deco = listOfNotNull(
                        "underline".takeIf { u != null && u != "none" },
                        "line-through".takeIf { strike != null && strike != "noStrike" },
                    )
                    if (deco.isNotEmpty()) css.append("text-decoration:").append(deco.joinToString(" ")).append(';')
                    rPr?.get("baseline")?.toIntOrNull()?.let {
                        if (it > 0) css.append("vertical-align:super;font-size:70%;")
                        else if (it < 0) css.append("vertical-align:sub;font-size:70%;")
                    }
                    if ((rPr?.get("cap") ?: rAttr("cap")) == "all") css.append("text-transform:uppercase;")
                    rPr?.child("solidFill")?.let { color(it, ctx) }?.let { if (overrideColor == null) css.append("color:").append(it).append(';') }
                    cssFontFamily(rPr?.child("latin")?.get("typeface"))?.let { css.append("font-family:").append(it).append(';') }
                    if (css.isEmpty()) out.append(text.html())
                    else out.append("<span style=\"").append(css).append("\">").append(text.html()).append("</span>")
                }
            }
            out.append("</p>")
        }
    }

    private fun spacing(sources: List<XmlNode>, name: String, sizePt: Double, ptPx: Double): Double? {
        val node = sources.firstNotNullOfOrNull { it.child(name) } ?: return null
        node.child("spcPts")?.get("val")?.toDoubleOrNull()?.let { return it / 100 * ptPx }
        node.child("spcPct")?.get("val")?.toDoubleOrNull()?.let { return it / 100000 * sizePt * 1.2 * ptPx }
        return null
    }

    private fun bulletChar(c: String): String {
        val ch = c.firstOrNull() ?: return "•"
        return when {
            ch.code in 0xF000..0xF0FF -> when (ch.code) {
                0xF0A7, 0xF06E -> "▪"
                0xF0D8 -> "➢"
                0xF0FC -> "✓"
                else -> "•"
            }
            ch == '§' -> "▪"
            ch == 'Ø' -> "➢"
            ch == 'ü' -> "✓"
            ch == 'q' -> "❑"
            ch == 'v' -> "❖"
            else -> c
        }
    }

    private fun autoNumber(n: Int, type: String): String {
        val body = when {
            type.startsWith("alphaLc") -> ('a' + (n - 1) % 26).toString()
            type.startsWith("alphaUc") -> ('A' + (n - 1) % 26).toString()
            type.startsWith("romanLc") -> roman(n).lowercase()
            type.startsWith("romanUc") -> roman(n)
            else -> n.toString()
        }
        return when {
            type.endsWith("ParenBoth") -> "($body)"
            type.endsWith("ParenR") -> "$body)"
            type.endsWith("Plain") -> body
            else -> "$body."
        } + " "
    }

    private fun roman(n: Int): String {
        if (n <= 0 || n >= 4000) return n.toString()
        val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val symbols = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var rest = n
        return buildString { for (i in values.indices) while (rest >= values[i]) { append(symbols[i]); rest -= values[i] } }
    }

    // endregion

    // region Rellenos y colores

    private class Fill(val color: String?, val image: String?)

    /** Relleno definido en [spPr] (o bgPr/tcPr): color, degradado o imagen. Null si no define relleno. */
    private fun fillCss(spPr: XmlNode?, ctx: Ctx): Fill? {
        spPr ?: return null
        for (c in spPr.children) {
            when (c.name) {
                "noFill" -> return Fill("transparent", null)
                "solidFill" -> return color(c, ctx)?.let { Fill(it, null) }
                "gradFill" -> {
                    val stops = c.child("gsLst")?.children("gs").orEmpty().mapNotNull { gs ->
                        color(gs, ctx)?.let { it to (gs.num("pos") / 1000) }
                    }
                    if (stops.isEmpty()) return null
                    if (stops.size == 1) return Fill(stops[0].first, null)
                    val angle = (c.child("lin")?.num("ang") ?: 5400000.0) / 60000 + 90
                    val css = "linear-gradient(${fmt(angle.toFloat())}deg," +
                        stops.joinToString(",") { "${it.first} ${fmt(it.second.toFloat())}%" } + ")"
                    return Fill(css, null)
                }
                "blipFill" -> {
                    val cls = c.child("blip")?.get("embed")?.let { ctx.part.rels[it] }?.takeIf { !it.external }
                        ?.let { images.classFor(it.target) }
                    return Fill(null, cls)
                }
                "pattFill" -> return color(c.child("fgClr"), ctx)?.let { Fill(it, null) }
            }
        }
        return null
    }

    private fun lineCss(ln: XmlNode?, style: XmlNode?, ctx: Ctx): String? {
        if (ln?.child("noFill") != null) return null
        val color = ln?.child("solidFill")?.let { color(it, ctx) }
            ?: style?.child("lnRef")?.takeIf { (it["idx"] ?: "0") != "0" }?.let { color(it, ctx) }
            ?: return null
        val idx = style?.child("lnRef")?.get("idx")?.toIntOrNull() ?: 1
        val w = ln?.get("w")?.toDoubleOrNull() ?: (9525.0 * idx)
        val dashed = ln?.child("prstDash")?.get("val")?.let { it != "solid" } == true
        return "border:${px(max(1.0, w * scale))}px ${if (dashed) "dashed" else "solid"} $color;"
    }

    /** Color del primer hijo de color de [holder] (srgbClr, schemeClr...) con sus modificadores. */
    private fun color(holder: XmlNode?, ctx: Ctx): String? {
        val c = holder?.children?.firstOrNull { it.name in COLOR_TAGS } ?: return null
        val base = when (c.name) {
            "srgbClr" -> officeHex(c["val"])
            "schemeClr" -> colorByName(c["val"] ?: return null, ctx)
            "sysClr" -> officeHex(c["lastClr"]) ?: if (c["val"] == "window") "#ffffff" else "#000000"
            "prstClr" -> PRESET_COLORS[c["val"]]
            "scrgbClr" -> {
                fun ch(n: String) = ((c[n]?.toDoubleOrNull() ?: 0.0) / 100000 * 255).roundToInt().coerceIn(0, 255)
                "#%02x%02x%02x".format(ch("r"), ch("g"), ch("b"))
            }
            else -> null
        } ?: return null
        return applyModifiers(base, c)
    }

    private fun colorByName(name: String, ctx: Ctx): String {
        val mapped = ctx.clrMap[name] ?: name
        return ctx.master.theme[mapped] ?: when (mapped) {
            "dk1", "tx1" -> "#000000"
            "lt1", "bg1" -> "#ffffff"
            "dk2" -> "#44546a"
            "lt2" -> "#e7e6e6"
            else -> "#4472c4"
        }
    }

    private fun applyModifiers(hex: String, node: XmlNode): String {
        if (node.children.isEmpty()) return hex
        val rgb = hex.drop(1).toInt(16)
        var r = ((rgb shr 16) and 0xFF) / 255.0
        var g = ((rgb shr 8) and 0xFF) / 255.0
        var b = (rgb and 0xFF) / 255.0
        var alpha = 1.0
        for (m in node.children) {
            val v = (m["val"]?.toDoubleOrNull() ?: continue) / 100000
            when (m.name) {
                "lumMod", "lumOff" -> {
                    val hsl = toHsl(r, g, b)
                    val l = if (m.name == "lumMod") hsl[2] * v else hsl[2] + v
                    val back = fromHsl(hsl[0], hsl[1], l.coerceIn(0.0, 1.0))
                    r = back[0]; g = back[1]; b = back[2]
                }
                "tint" -> { r += (1 - r) * (1 - v); g += (1 - g) * (1 - v); b += (1 - b) * (1 - v) }
                "shade" -> { r *= v; g *= v; b *= v }
                "alpha" -> alpha = v
            }
        }
        fun ch(x: Double) = (x.coerceIn(0.0, 1.0) * 255).roundToInt()
        return if (alpha < 1.0) "rgba(${ch(r)},${ch(g)},${ch(b)},${fmt(alpha.toFloat())})"
        else "#%02x%02x%02x".format(ch(r), ch(g), ch(b))
    }

    private fun toHsl(r: Double, g: Double, b: Double): DoubleArray {
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val l = (maxC + minC) / 2
        if (maxC == minC) return doubleArrayOf(0.0, 0.0, l)
        val d = maxC - minC
        val s = if (l > 0.5) d / (2 - maxC - minC) else d / (maxC + minC)
        val h = when (maxC) {
            r -> (g - b) / d + (if (g < b) 6 else 0)
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        } / 6
        return doubleArrayOf(h, s, l)
    }

    private fun fromHsl(h: Double, s: Double, l: Double): DoubleArray {
        if (s == 0.0) return doubleArrayOf(l, l, l)
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun hue(tIn: Double): Double {
            var t = tIn
            if (t < 0) t += 1.0
            if (t > 1) t -= 1.0
            return when {
                t < 1.0 / 6 -> p + (q - p) * 6 * t
                t < 1.0 / 2 -> q
                t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
                else -> p
            }
        }
        return doubleArrayOf(hue(h + 1.0 / 3), hue(h), hue(h - 1.0 / 3))
    }

    /** Mezcla [hex] con blanco: [amount] = proporción del color. */
    private fun mix(hex: String, amount: Double): String {
        val rgb = hex.drop(1).toIntOrNull(16) ?: return hex
        fun ch(shift: Int) = (((rgb shr shift) and 0xFF) * amount + 255 * (1 - amount)).roundToInt()
        return "#%02x%02x%02x".format(ch(16), ch(8), ch(0))
    }

    // endregion

    private companion object {
        /** Ancho en píxeles CSS con el que se dibuja cada diapositiva. */
        const val SLIDE_PX = 960

        val COLOR_TAGS = setOf("srgbClr", "schemeClr", "sysClr", "prstClr", "scrgbClr", "hslClr")
        val BULLET_TAGS = setOf("buNone", "buChar", "buAutoNum", "buBlip")

        val DEFAULT_CLR_MAP = mapOf(
            "bg1" to "lt1", "tx1" to "dk1", "bg2" to "lt2", "tx2" to "dk2",
            "accent1" to "accent1", "accent2" to "accent2", "accent3" to "accent3", "accent4" to "accent4",
            "accent5" to "accent5", "accent6" to "accent6", "hlink" to "hlink", "folHlink" to "folHlink",
        )

        val PRESET_COLORS = mapOf(
            "black" to "#000000", "white" to "#ffffff", "red" to "#ff0000", "green" to "#008000",
            "blue" to "#0000ff", "yellow" to "#ffff00", "gray" to "#808080", "grey" to "#808080",
            "orange" to "#ffa500", "purple" to "#800080", "darkBlue" to "#00008b", "darkRed" to "#8b0000",
            "darkGreen" to "#006400", "lightGray" to "#d3d3d3", "ltGray" to "#d3d3d3", "dkGray" to "#a9a9a9",
            "cyan" to "#00ffff", "magenta" to "#ff00ff", "navy" to "#000080", "silver" to "#c0c0c0",
        )
    }
}
