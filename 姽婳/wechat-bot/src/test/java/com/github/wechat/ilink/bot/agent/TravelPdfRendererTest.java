package com.github.wechat.ilink.bot.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class TravelPdfRendererTest {
  @Test
  void rendersChineseMarkdownAsReadablePdf() throws Exception {
    TravelPdfRenderer renderer =
        new TravelPdfRenderer("/System/Library/Fonts/Supplemental/Arial Unicode.ttf");
    byte[] pdf =
        renderer.render(
            """
            # 上海 3 天旅行方案

            > **人数：** 2 人　**预算：** 5000 元

            ## 天气参考

            | 日期 | 天气 | 温度 |
            |---|---|---:|
            | 2026-08-26 | 多云 | 24～31℃ |

            ## 第 1 天：历史文化

            - 上午参观上海博物馆
            - 下午游览外滩

            ---

            *生成方式：结构化规划 + 约束审校。*
            """);

    assertTrue(pdf.length > 1_000);
    assertTrue(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).startsWith("%PDF-"));
    try (PDDocument document = PDDocument.load(new ByteArrayInputStream(pdf))) {
      String text = new PDFTextStripper().getText(document);
      assertTrue(text.contains("上海 3 天旅行方案"));
      assertTrue(text.contains("上海博物馆"));
      assertTrue(text.contains("2026-08-26"));
      assertTrue(text.contains("多云"));
      assertFalse(text.contains("**"));
      assertFalse(text.contains("|---"));
    }
  }

  @Test
  void convertsGfmTableAndFormattingToHtml() {
    TravelPdfRenderer renderer =
        new TravelPdfRenderer("/System/Library/Fonts/Supplemental/Arial Unicode.ttf");
    String html =
        renderer.renderToHtml(
            """
            # 标题

            ## 天气

            | 城市 | 天气 |
            |---|---|
            | 北京 | 晴 |

            **重点**与*说明*
            """);

    assertTrue(html.contains("<table>"));
    assertTrue(html.contains("<th>城市</th>"));
    assertTrue(html.contains("<strong>重点</strong>"));
    assertTrue(html.contains("<em>说明</em>"));
    assertTrue(html.contains("<section class=\"travel-section\"><h2>"));
    assertFalse(html.contains("|---|---|"));
  }
}
