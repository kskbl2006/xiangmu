package com.github.wechat.ilink.bot;

import com.github.wechat.ilink.bot.agent.TravelPdfRenderer;
import com.github.wechat.ilink.bot.config.AppConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Command-line PDF QA helper: input Markdown path, output PDF path. */
public final class TravelPdfSmokeTest {
  private TravelPdfSmokeTest() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      throw new IllegalArgumentException("Usage: TravelPdfSmokeTest <input.md> <output.pdf>");
    }
    Path input = Path.of(args[0]).toAbsolutePath().normalize();
    Path output = Path.of(args[1]).toAbsolutePath().normalize();
    String markdown = Files.readString(input, StandardCharsets.UTF_8);
    byte[] pdf = new TravelPdfRenderer(AppConfig.fromEnvironment()).render(markdown);
    if (output.getParent() != null) Files.createDirectories(output.getParent());
    Files.write(output, pdf);
    System.out.printf("Rendered %s (%d bytes)%n", output, pdf.length);
  }
}
