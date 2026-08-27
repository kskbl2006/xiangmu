package com.travel.agent.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Markdown → docx 导出（纯 JDK 实现：zipfile + 最小 OOXML 结构）。
 *
 * 不依赖第三方库，离线可用；支持标题/段落/表格/列表/引用，
 * 足以承载《旅行方案》的结构化输出。Word / WPS 均可打开。
 */
public final class DocxExport {

    private static final String CONTENT_TYPES = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
            <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
            <Default Extension="xml" ContentType="application/xml"/>
            <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
            <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
            </Types>""";

    private static final String ROOT_RELS = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
            </Relationships>""";

    private static final String DOC_RELS = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
            </Relationships>""";

    private static final String STYLES = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
            <w:docDefaults><w:rPrDefault><w:rPr>
            <w:rFonts w:ascii="Calibri" w:eastAsia="微软雅黑"/><w:sz w:val="21"/>
            </w:rPr></w:rPrDefault></w:docDefaults>
            <w:style w:type="paragraph" w:styleId="Normal"><w:name w:val="Normal"/><w:basedOn w:val="Normal"/>
            <w:pPr><w:spacing w:before="120" w:after="60"/></w:pPr>
            <w:rPr><w:rFonts w:eastAsia="微软雅黑"/><w:b w:val="0"/><w:sz w:val="21"/></w:rPr></w:style>
            <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/>
            <w:pPr><w:spacing w:before="120" w:after="60"/></w:pPr>
            <w:rPr><w:rFonts w:eastAsia="微软雅黑"/><w:b w:val="1"/><w:sz w:val="36"/></w:rPr></w:style>
            <w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/>
            <w:pPr><w:spacing w:before="120" w:after="60"/></w:pPr>
            <w:rPr><w:rFonts w:eastAsia="微软雅黑"/><w:b w:val="1"/><w:sz w:val="30"/></w:rPr></w:style>
            <w:style w:type="paragraph" w:styleId="Heading3"><w:name w:val="heading 3"/><w:basedOn w:val="Normal"/>
            <w:pPr><w:spacing w:before="120" w:after="60"/></w:pPr>
            <w:rPr><w:rFonts w:eastAsia="微软雅黑"/><w:b w:val="1"/><w:sz w:val="26"/></w:rPr></w:style>
            </w:styles>""";

    private static final Pattern MD_LINK = Pattern.compile("\\[([^]]+)]\\([^)]+\\)");
    private static final Pattern MD_BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern HR = Pattern.compile("-{3,}");
    private static final Pattern TABLE_SEP = Pattern.compile("^\\s*\\|[\\s:|-]+\\|\\s*$");

    private DocxExport() {
    }

    /** 把 Markdown 文本渲染为 docx 文件。支持：标题/表格/列表/引用/加粗去除。 */
    public static void mdToDocx(String mdText, String outPath) throws IOException {
        List<String> body = new ArrayList<>();
        String[] lines = mdText.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i].replaceAll("\\s+$", "");
            if (line.isBlank() || HR.matcher(line.strip()).matches()) {
                i++;
                continue;
            }
            if (line.startsWith("### ")) {
                body.add(p(line.substring(4), "Heading3", false));
            } else if (line.startsWith("## ")) {
                body.add(p(line.substring(3), "Heading2", false));
            } else if (line.startsWith("# ")) {
                body.add(p(line.substring(2), "Heading1", false));
            } else if (line.startsWith("> ")) {
                body.add(p(line.substring(2), null, true));
            } else if (line.startsWith("- ")) {
                body.add(p("• " + line.substring(2), null, true));
            } else if (line.strip().startsWith("|") && i + 1 < lines.length
                    && TABLE_SEP.matcher(lines[i + 1]).matches()) {
                List<String[]> rows = new ArrayList<>();
                rows.add(splitRow(line));
                i += 2;
                while (i < lines.length && lines[i].strip().startsWith("|")) {
                    rows.add(splitRow(lines[i]));
                    i++;
                }
                body.add(table(rows));
                continue;
            } else {
                body.add(p(line, null, false));
            }
            i++;
        }

        String document = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body>""" + String.join("", body) + """
                <w:sectPr><w:pgSz w:w="11906" w:h="16838"/>
                <w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr>
                </w:body></w:document>""";

        Path out = Path.of(outPath);
        Files.createDirectories(out.getParent());
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(out), StandardCharsets.UTF_8)) {
            put(z, "[Content_Types].xml", CONTENT_TYPES);
            put(z, "_rels/.rels", ROOT_RELS);
            put(z, "word/document.xml", document);
            put(z, "word/styles.xml", STYLES);
            put(z, "word/_rels/document.xml.rels", DOC_RELS);
        }
    }

    private static void put(ZipOutputStream z, String name, String content) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    private static String p(String text, String style, boolean indent) {
        StringBuilder ppr = new StringBuilder();
        if (style != null) {
            ppr.append("<w:pStyle w:val=\"").append(style).append("\"/>");
        }
        if (indent) {
            ppr.append("<w:ind w:left=\"480\"/>");
        }
        String pprXml = ppr.isEmpty() ? "" : "<w:pPr>" + ppr + "</w:pPr>";
        // md 链接仅保留文字；去掉加粗标记保留文字；XML 转义
        String t = stripMd(text);
        return "<w:p>" + pprXml + "<w:r><w:t xml:space=\"preserve\">" + esc(t) + "</w:t></w:r></w:p>";
    }

    private static String stripMd(String text) {
        String t = MD_LINK.matcher(text).replaceAll("$1");   // [text](url) → text
        return MD_BOLD.matcher(t).replaceAll("$1");           // **bold** → bold
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String cell(String text) {
        return "<w:tc><w:tcPr><w:tcW w:w=\"0\" w:type=\"auto\"/></w:tcPr>"
                + "<w:p><w:r><w:t xml:space=\"preserve\">" + esc(text) + "</w:t></w:r></w:p></w:tc>";
    }

    private static String table(List<String[]> rows) {
        StringBuilder borders = new StringBuilder("<w:tblBorders>");
        for (String s : List.of("top", "left", "bottom", "right", "insideH", "insideV")) {
            borders.append("<w:").append(s)
                    .append(" w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"999999\"/>");
        }
        borders.append("</w:tblBorders>");
        StringBuilder xml = new StringBuilder("<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/>")
                .append(borders).append("</w:tblPr>");
        for (int ri = 0; ri < rows.size(); ri++) {
            xml.append("<w:tr>");
            String[] row = rows.get(ri);
            for (String c : row) {
                if (ri == 0) {   // 表头加粗
                    xml.append("<w:tc><w:tcPr><w:tcW w:w=\"0\" w:type=\"auto\"/></w:tcPr>")
                            .append("<w:p><w:pPr/></w:p><w:p><w:r><w:rPr><w:b/></w:rPr>")
                            .append("<w:t xml:space=\"preserve\">").append(esc(c))
                            .append("</w:t></w:r></w:p></w:tc>");
                } else {
                    xml.append(cell(c));
                }
            }
            xml.append("</w:tr>");
        }
        xml.append("</w:tbl>");
        return xml.toString();
    }

    private static String[] splitRow(String line) {
        String trimmed = line.strip().replaceAll("^\\|", "").replaceAll("\\|$", "");
        String[] parts = trimmed.split("\\|", -1);
        String[] out = new String[parts.length];
        for (int k = 0; k < parts.length; k++) {
            out[k] = parts[k].strip();
        }
        return out;
    }
}
