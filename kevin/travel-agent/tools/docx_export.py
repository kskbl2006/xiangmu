# -*- coding: utf-8 -*-
"""Markdown → docx 导出（纯标准库实现：zipfile + 最小 OOXML 结构）。

不依赖 python-docx 等第三方库，离线可用；支持标题/段落/表格/列表/引用，
足以承载《旅行方案》的结构化输出。Word / WPS 均可打开。
"""
from __future__ import annotations

import re
import zipfile
from xml.sax.saxutils import escape

CONTENT_TYPES = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
    '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
    '<Default Extension="xml" ContentType="application/xml"/>'
    '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
    '<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>'
    '</Types>'
)

ROOT_RELS = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>'
    '</Relationships>'
)

DOC_RELS = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>'
    '</Relationships>'
)

def _style(sid, name, size, bold):
    return (
        '<w:style w:type="paragraph" w:styleId="%s">'
        '<w:name w:val="%s"/><w:basedOn w:val="Normal"/>'
        '<w:pPr><w:spacing w:before="120" w:after="60"/></w:pPr>'
        '<w:rPr><w:rFonts w:eastAsia="微软雅黑"/><w:b w:val="%d"/><w:sz w:val="%d"/></w:rPr>'
        '</w:style>' % (sid, name, 1 if bold else 0, size)
    )

STYLES = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
    '<w:docDefaults><w:rPrDefault><w:rPr>'
    '<w:rFonts w:ascii="Calibri" w:eastAsia="微软雅黑"/><w:sz w:val="21"/>'
    '</w:rPr></w:rPrDefault></w:docDefaults>'
    + _style("Normal", "Normal", 21, False)
    + _style("Heading1", "heading 1", 36, True)
    + _style("Heading2", "heading 2", 30, True)
    + _style("Heading3", "heading 3", 26, True)
    + '</w:styles>'
)

def _p(text, style=None, indent=False):
    ppr = ""
    if style:
        ppr += '<w:pStyle w:val="%s"/>' % style
    if indent:
        ppr += '<w:ind w:left="480"/>'
    if ppr:
        ppr = "<w:pPr>%s</w:pPr>" % ppr
    text = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", text)   # md 链接仅保留文字
    text = escape(re.sub(r"\*\*(.+?)\*\*", r"\1", text))  # 去掉加粗标记保留文字
    return '<w:p>%s<w:r><w:t xml:space="preserve">%s</w:t></w:r></w:p>' % (ppr, text)


def _cell(text):
    return ('<w:tc><w:tcPr><w:tcW w:w="0" w:type="auto"/></w:tcPr>'
            '<w:p><w:r><w:t xml:space="preserve">%s</w:t></w:r></w:p></w:tc>' % escape(text))


_TBL_BORDERS = (
    '<w:tblBorders>'
    + "".join('<w:%s w:val="single" w:sz="4" w:space="0" w:color="999999"/>' % s
              for s in ("top", "left", "bottom", "right", "insideH", "insideV"))
    + '</w:tblBorders>'
)


def _table(rows):
    xml = ['<w:tbl><w:tblPr><w:tblW w:w="0" w:type="auto"/>%s</w:tblPr>' % _TBL_BORDERS]
    for ri, row in enumerate(rows):
        xml.append("<w:tr>")
        for cell in row:
            if ri == 0:  # 表头加粗
                xml.append('<w:tc><w:tcPr><w:tcW w:w="0" w:type="auto"/></w:tcPr>'
                           '<w:p><w:pPr/></w:p><w:p><w:r><w:rPr><w:b/></w:rPr>'
                           '<w:t xml:space="preserve">%s</w:t></w:r></w:p></w:tc>' % escape(cell))
            else:
                xml.append(_cell(cell))
        xml.append("</w:tr>")
    xml.append("</w:tbl>")
    return "".join(xml)


def _split_row(line):
    return [c.strip() for c in line.strip().strip("|").split("|")]


def md_to_docx(md_text: str, out_path: str):
    """把 Markdown 文本渲染为 docx 文件。支持：标题/表格/列表/引用/加粗去除。"""
    body = []
    lines = md_text.splitlines()
    i = 0
    while i < len(lines):
        line = lines[i].rstrip()
        if not line.strip() or re.fullmatch(r"-{3,}", line.strip()):
            i += 1
            continue
        if line.startswith("### "):
            body.append(_p(line[4:], "Heading3"))
        elif line.startswith("## "):
            body.append(_p(line[3:], "Heading2"))
        elif line.startswith("# "):
            body.append(_p(line[2:], "Heading1"))
        elif line.startswith("> "):
            body.append(_p(line[2:], indent=True))
        elif line.startswith("- "):
            body.append(_p("• " + line[2:], indent=True))
        elif line.strip().startswith("|") and i + 1 < len(lines) and re.match(r"^\s*\|[\s:|-]+\|\s*$", lines[i + 1]):
            rows = [_split_row(line)]
            i += 2
            while i < len(lines) and lines[i].strip().startswith("|"):
                rows.append(_split_row(lines[i]))
                i += 1
            body.append(_table(rows))
            continue
        else:
            body.append(_p(line))
        i += 1

    document = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
        '<w:body>' + "".join(body) +
        '<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>'
        '<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr>'
        '</w:body></w:document>'
    )

    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", CONTENT_TYPES)
        z.writestr("_rels/.rels", ROOT_RELS)
        z.writestr("word/document.xml", document)
        z.writestr("word/styles.xml", STYLES)
        z.writestr("word/_rels/document.xml.rels", DOC_RELS)
