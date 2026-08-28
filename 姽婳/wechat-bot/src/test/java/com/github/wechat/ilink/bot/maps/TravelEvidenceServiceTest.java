package com.github.wechat.ilink.bot.maps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.bot.agent.TravelBrief;
import com.github.wechat.ilink.bot.agent.TravelMapData;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

class TravelEvidenceServiceTest {
  @Test
  void usesRailAsPrimaryIntercitySource() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(success("G1", "常州北", "北京南", 500));
      server.enqueue(success("G2", "北京南", "常州北", 480));
      Clock clock =
          Clock.fixed(Instant.parse("2026-08-28T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
      JuheRailClient rail =
          new JuheRailClient(
              new OkHttpClient(),
              new ObjectMapper(),
              server.url("/train/query").toString(),
              "key",
              clock,
              Duration.ofHours(6));
      BaiduMapClient disabledMap =
          new BaiduMapClient(
              new OkHttpClient(), new ObjectMapper(), server.url("/baidu/").toString(), "", 0, clock);
      TravelEvidenceService service =
          new TravelEvidenceService(new TravelMapService(disabledMap, 0), rail);
      TravelBrief brief =
          new TravelBrief(
              "常州", "北京", 3, 2, 5000, "balanced", List.of(), List.of(),
              LocalDate.of(2026, 8, 30), true);

      TravelMapData evidence = service.collect(brief, List.of());

      assertTrue(evidence.enabled());
      assertEquals("G1（二等座）", evidence.outboundRoutes().getFirst().serviceName());
      assertEquals("G2（二等座）", evidence.returnRoutes().getFirst().serviceName());
      assertEquals("juhe-rail-api-817", evidence.outboundRoutes().getFirst().source());
      assertTrue(evidence.warnings().stream().anyMatch(value -> value.contains("聚合数据")));
      assertEquals(2, server.getRequestCount());
    }
  }

  private static MockResponse success(String train, String from, String to, int price) {
    return new MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            "{\"reason\":\"success\",\"error_code\":0,\"result\":[{"
                + "\"train_no\":\"" + train + "\","
                + "\"departure_station\":\"" + from + "\","
                + "\"arrival_station\":\"" + to + "\","
                + "\"departure_time\":\"08:00\",\"arrival_time\":\"12:00\","
                + "\"duration\":\"04:00\","
                + "\"prices\":[{\"seat_name\":\"二等座\",\"price\":" + price + "}]}]}");
  }
}
