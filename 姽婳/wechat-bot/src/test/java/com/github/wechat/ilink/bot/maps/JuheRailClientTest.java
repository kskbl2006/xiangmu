package com.github.wechat.ilink.bot.maps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.nio.file.Path;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JuheRailClientTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-28T00:00:00Z"), ZoneId.of("Asia/Shanghai"));

  @TempDir Path temporaryDirectory;

  @Test
  void parsesSchedulesSelectsEconomicSeatAndCachesResponse() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "application/json")
              .setBody(
                  """
                  {"reason":"success.","error_code":0,"result":[
                    {"train_no":"G25","departure_station":"常州北","arrival_station":"北京南",
                     "departure_time":"08:04","arrival_time":"12:32","duration":"04:28",
                     "prices":[{"seat_name":"一等座","price":1003},{"seat_name":"二等座","price":627}]},
                    {"train_no":"G101","departure_station":"常州北","arrival_station":"北京南",
                     "departure_time":"09:00","arrival_time":"14:00","duration":"05:00","prices":[]}
                  ]}
                  """));
      JuheRailClient client =
          new JuheRailClient(
              new OkHttpClient(),
              new ObjectMapper(),
              server.url("/fapigw/train/query").toString(),
              "test-key",
              CLOCK,
              Duration.ofHours(6));

      LocalDate date = LocalDate.of(2026, 8, 30);
      List<com.github.wechat.ilink.bot.agent.TravelMapData.RouteOption> first =
          client.query("常州市", "北京", date);
      List<com.github.wechat.ilink.bot.agent.TravelMapData.RouteOption> cached =
          client.query("常州市", "北京", date);

      assertEquals(2, first.size());
      assertEquals("G25（二等座）", first.getFirst().serviceName());
      assertEquals(627, first.getFirst().referencePriceYuan());
      assertEquals(268, first.getFirst().durationMinutes());
      assertEquals("juhe-rail-api-817", first.getFirst().source());
      assertEquals(first, cached);
      assertEquals(1, server.getRequestCount());
      String request = server.takeRequest().getPath();
      assertTrue(request.contains("departure_station=%E5%B8%B8%E5%B7%9E"));
      assertTrue(request.contains("arrival_station=%E5%8C%97%E4%BA%AC"));
      assertTrue(request.contains("enable_booking=1"));
    }
  }

  @Test
  void skipsDatesOutsideProviderWindowWithoutCallingApi() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      JuheRailClient client =
          new JuheRailClient(
              new OkHttpClient(),
              new ObjectMapper(),
              server.url("/fapigw/train/query").toString(),
              "test-key",
              CLOCK,
              Duration.ofHours(6));

      assertTrue(client.query("常州", "北京", LocalDate.of(2026, 9, 20)).isEmpty());
      assertEquals(0, server.getRequestCount());
    }
  }

  @Test
  void surfacesProviderErrorsWithoutLeakingApiKey() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "application/json")
              .setBody("{\"reason\":\"错误的请求KEY\",\"error_code\":10001}"));
      JuheRailClient client =
          new JuheRailClient(
              new OkHttpClient(),
              new ObjectMapper(),
              server.url("/fapigw/train/query").toString(),
              "secret-key",
              CLOCK,
              Duration.ofHours(6));

      java.io.IOException error =
          org.junit.jupiter.api.Assertions.assertThrows(
              java.io.IOException.class,
              () -> client.query("常州", "北京", LocalDate.of(2026, 8, 30)));
      assertTrue(error.getMessage().contains("10001"));
      assertTrue(!error.getMessage().contains("secret-key"));
    }
  }

  @Test
  void disablesCallsAndTripsCircuitOnQuotaError() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      Path state = temporaryDirectory.resolve("quota.properties");
      JuheRailClient disabled =
          new JuheRailClient(
              new OkHttpClient(),
              new ObjectMapper(),
              server.url("/fapigw/train/query").toString(),
              "test-key",
              CLOCK,
              Duration.ofHours(6),
              false,
              10,
              state);
      assertTrue(disabled.query("常州", "北京", LocalDate.of(2026, 8, 30)).isEmpty());
      assertEquals(0, server.getRequestCount());

      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "application/json")
              .setBody("{\"reason\":\"今日调用次数已达上限\",\"error_code\":10012}"));
      JuheRailClient enabled =
          new JuheRailClient(
              new OkHttpClient(),
              new ObjectMapper(),
              server.url("/fapigw/train/query").toString(),
              "test-key",
              CLOCK,
              Duration.ofHours(6),
              true,
              10,
              state);
      org.junit.jupiter.api.Assertions.assertThrows(
          java.io.IOException.class,
          () -> enabled.query("常州", "北京", LocalDate.of(2026, 8, 30)));
      assertEquals(DailyQuotaGuard.Status.CIRCUIT_OPEN, enabled.quotaStatus());
      assertTrue(enabled.query("常州", "上海", LocalDate.of(2026, 8, 30)).isEmpty());
      assertEquals(1, server.getRequestCount());
    }
  }
}
