package com.github.wechat.ilink.bot.agent;

import com.github.wechat.ilink.bot.config.AppConfig;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

/** Renders the travel Markdown artifact into a styled, searchable Chinese PDF. */
public final class TravelPdfRenderer {
  private static final List<Path> DEFAULT_FONTS =
      List.of(
          Path.of("/System/Library/Fonts/Supplemental/Arial Unicode.ttf"),
          Path.of("/System/Library/Fonts/Supplemental/AppleGothic.ttf"),
          Path.of("C:/Windows/Fonts/simhei.ttf"),
          Path.of("/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc"),
          Path.of("/usr/share/fonts/truetype/arphic/uming.ttc"));
  private static final List<Extension> MARKDOWN_EXTENSIONS = List.of(TablesExtension.create());
  private static final Parser MARKDOWN_PARSER =
      Parser.builder().extensions(MARKDOWN_EXTENSIONS).build();
  private static final HtmlRenderer HTML_RENDERER =
      HtmlRenderer.builder().extensions(MARKDOWN_EXTENSIONS).escapeHtml(true).build();

  private static final String STYLES =
      """
      @page {
        size: A4;
        margin: 17mm 16mm 18mm 16mm;
        @bottom-center {
          content: "第 " counter(page) " 页 / 共 " counter(pages) " 页";
          font-family: TravelCJK;
          font-size: 8.5pt;
          color: #718096;
        }
      }
      * { box-sizing: border-box; }
      body {
        font-family: TravelCJK, sans-serif;
        color: #24324a;
        font-size: 10.5pt;
        line-height: 1.65;
      }
      h1 {
        margin: 0 0 12pt 0;
        padding-bottom: 7pt;
        border-bottom: 2pt solid #2563eb;
        color: #173b70;
        font-size: 23pt;
        line-height: 1.25;
      }
      h2 {
        margin: 18pt 0 8pt 0;
        padding: 5pt 8pt;
        border-left: 4pt solid #2563eb;
        background: #eff6ff;
        color: #17417d;
        font-size: 15pt;
        line-height: 1.3;
        page-break-after: avoid;
      }
      h3 {
        margin: 12pt 0 4pt 0;
        color: #1e4f86;
        font-size: 12pt;
        line-height: 1.35;
        page-break-after: avoid;
      }
      .travel-section { page-break-inside: avoid; }
      p { margin: 4pt 0 8pt 0; }
      blockquote {
        margin: 8pt 0 14pt 0;
        padding: 8pt 10pt;
        border-left: 4pt solid #60a5fa;
        background: #f8fbff;
        color: #334155;
      }
      blockquote p { margin: 0; }
      ul, ol { margin: 5pt 0 10pt 18pt; padding-left: 8pt; }
      li { margin: 2pt 0; }
      table {
        width: 100%;
        margin: 7pt 0 14pt 0;
        border-collapse: collapse;
        font-size: 9.5pt;
        -fs-table-paginate: paginate;
      }
      thead { display: table-header-group; }
      tr { page-break-inside: avoid; }
      th, td {
        padding: 5pt 6pt;
        border: 0.7pt solid #cbd5e1;
        vertical-align: top;
      }
      th { background: #dbeafe; color: #173b70; font-weight: bold; }
      tbody tr:nth-child(even) { background: #f8fafc; }
      strong { color: #142f54; }
      em { color: #64748b; font-size: 9pt; }
      a { color: #1d4ed8; text-decoration: none; }
      hr { margin: 15pt 0 8pt 0; border: 0; border-top: 0.8pt solid #cbd5e1; }
      code {
        padding: 1pt 3pt;
        background: #f1f5f9;
        color: #9f1239;
        font-family: TravelCJK, sans-serif;
      }
      """;

  private final String configuredFontPath;

  public TravelPdfRenderer(AppConfig config) {
    this(config.getTravelPdfFontPath());
  }

  TravelPdfRenderer(String configuredFontPath) {
    this.configuredFontPath = configuredFontPath == null ? "" : configuredFontPath.trim();
  }

  public byte[] render(String markdown) throws IOException {
    if (markdown == null || markdown.isBlank()) throw new IllegalArgumentException("markdown is blank");
    Path fontPath = resolveFont();
    try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      PdfRendererBuilder builder = new PdfRendererBuilder();
      builder.useFastMode();
      builder.useFont(fontPath.toFile(), "TravelCJK");
      builder.withHtmlContent(renderToHtml(markdown), null);
      builder.toStream(output);
      try {
        builder.run();
      } catch (RuntimeException e) {
        throw new IOException("Unable to render travel PDF", e);
      }
      return output.toByteArray();
    }
  }

  String renderToHtml(String markdown) {
    if (markdown == null || markdown.isBlank()) throw new IllegalArgumentException("markdown is blank");
    String body = groupSecondLevelSections(HTML_RENDERER.render(MARKDOWN_PARSER.parse(markdown)));
    return """
        <!DOCTYPE html>
        <html xmlns="http://www.w3.org/1999/xhtml" lang="zh-CN">
        <head>
          <meta charset="UTF-8" />
          <title>旅行方案</title>
          <style>%s</style>
        </head>
        <body>%s</body>
        </html>
        """.formatted(STYLES, body);
  }

  private static String groupSecondLevelSections(String body) {
    int firstHeading = body.indexOf("<h2>");
    if (firstHeading < 0) return body;
    StringBuilder grouped = new StringBuilder(body.length() + 128);
    grouped.append(body, 0, firstHeading);
    int sectionStart = firstHeading;
    while (sectionStart >= 0) {
      int nextSection = body.indexOf("<h2>", sectionStart + 4);
      grouped.append("<section class=\"travel-section\">");
      grouped.append(body, sectionStart, nextSection < 0 ? body.length() : nextSection);
      grouped.append("</section>");
      sectionStart = nextSection;
    }
    return grouped.toString();
  }

  private Path resolveFont() throws IOException {
    if (!configuredFontPath.isBlank()) {
      Path configured = Path.of(configuredFontPath).toAbsolutePath().normalize();
      if (Files.isRegularFile(configured)) return configured;
      throw new IOException("Configured PDF font does not exist: " + configured);
    }
    return DEFAULT_FONTS.stream()
        .filter(Files::isRegularFile)
        .findFirst()
        .orElseThrow(
            () ->
                new IOException(
                    "No Chinese PDF font found; configure TRAVEL_PDF_FONT_PATH with a TTF/OTF font"));
  }

}
