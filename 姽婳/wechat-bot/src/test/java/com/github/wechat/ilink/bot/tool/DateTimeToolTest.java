package com.github.wechat.ilink.bot.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class DateTimeToolTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Clock clock =
      Clock.fixed(Instant.parse("2026-08-24T04:00:00Z"), ZoneId.of("Asia/Shanghai"));

  @Test
  void returnsCurrentChinaTimeAndRelativeDate() throws Exception {
    DateTimeTool tool = new DateTimeTool(objectMapper, clock);

    var today = objectMapper.readTree(tool.execute(objectMapper.createObjectNode()));
    var tomorrow =
        objectMapper.readTree(
            tool.execute(objectMapper.createObjectNode().put("offset_days", 1)));

    assertEquals("2026-08-24", today.path("date").asText());
    assertEquals("12:00:00", today.path("time").asText());
    assertEquals("星期一", today.path("weekday").asText());
    assertEquals("2026-08-25", tomorrow.path("date").asText());
    assertEquals("星期二", tomorrow.path("weekday").asText());
    assertEquals("明天", tomorrow.path("relative_label").asText());
  }

  @Test
  void rejectsUnreasonableOffsets() {
    DateTimeTool tool = new DateTimeTool(objectMapper, clock);
    assertThrows(
        IllegalArgumentException.class,
        () -> tool.execute(objectMapper.createObjectNode().put("offset_days", 36_501)));
  }
}
