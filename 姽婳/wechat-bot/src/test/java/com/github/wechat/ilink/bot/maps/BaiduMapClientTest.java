package com.github.wechat.ilink.bot.maps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.RecordedRequest;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BaiduMapClientTest {
  @Test
  void parsesGeocodingTransitAndPoiResponses() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(json("""
          {"status":0,"result":{"location":{"lng":119.973987,"lat":31.810689}}}
          """));
      server.enqueue(json("""
          {
            "status":0,
            "result":{"routes":[{
              "duration":7200,
              "price":149.5,
              "steps":[[{"vehicle_info":{"type":1,"detail":{
                "name":"G7001","price":149.5,
                "departure_station":"常州站","arrive_station":"上海站",
                "departure_time":"08:00","arrive_time":"09:05"
              }}}]]
            }]}
          }
          """));
      server.enqueue(json("""
          {
            "status":0,
            "results":[{
              "name":"上海博物馆",
              "address":"人民大道201号",
              "telephone":"021-12345678",
              "location":{"lat":31.2304,"lng":121.4737},
              "detail_info":{
                "overall_rating":"4.8",
                "price":"0",
                "shop_hours":"09:00-17:00",
                "detail_url":"https://map.baidu.com/poi/example"
              }
            }]
          }
          """));
      BaiduMapClient client =
          new BaiduMapClient(
              new OkHttpClient(),
              new ObjectMapper(),
              server.url("/").toString(),
              "test-ak",
              0,
              Clock.fixed(Instant.parse("2026-08-26T03:00:00Z"), ZoneOffset.UTC));

      BaiduMapClient.Point point = client.geocode("常州").orElseThrow();
      assertEquals(31.810689, point.latitude(), 0.000001);
      var routes =
          client.transit(
              point, new BaiduMapClient.Point(31.2304, 121.4737), LocalDate.of(2026, 8, 30));
      assertEquals(1, routes.size());
      assertEquals("火车", routes.getFirst().mode());
      assertEquals("G7001", routes.getFirst().serviceName());
      assertEquals(120, routes.getFirst().durationMinutes());
      assertEquals(149.5, routes.getFirst().referencePriceYuan(), 0.001);
      var poi = client.searchPoi("上海", "上海博物馆").orElseThrow();
      assertEquals("人民大道201号", poi.address());
      assertEquals(4.8, poi.rating(), 0.001);
      assertEquals("09:00-17:00", poi.openingHours());

      assertTrue(server.takeRequest().getPath().startsWith("/geocoding/v3/"));
      String transitPath = server.takeRequest().getPath();
      assertTrue(transitPath.startsWith("/direction/v2/transit"));
      assertTrue(transitPath.contains("departure_date=20260830"));
      assertTrue(transitPath.contains("departure_time=00%3A00-23%3A59"));
      assertTrue(server.takeRequest().getPath().startsWith("/place/v2/search"));
    }
  }

  @Test
  void recognizesAirportCoachEncodedAsRegularTransit() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(json("""
          {"status":0,"result":{"routes":[{
            "duration":18577,"price":216,
            "steps":[[
              {"vehicle_info":{"type":3,"detail":{"name":"地铁1号线"}}},
              {"vehicle_info":{"type":3,"detail":{"name":"浦东机场常州线"}}},
              {"vehicle_info":{"type":3,"detail":{"name":"地铁2号线"}}}
            ]]
          }]}}
          """));
      BaiduMapClient client =
          new BaiduMapClient(
              new OkHttpClient(),
              new ObjectMapper(),
              server.url("/").toString(),
              "test-ak",
              0,
              Clock.systemUTC());

      var routes =
          client.transit(
              new BaiduMapClient.Point(31.81, 119.98),
              new BaiduMapClient.Point(31.23, 121.48),
              LocalDate.of(2026, 8, 27));

      assertEquals(1, routes.size());
      assertEquals("跨城大巴", routes.getFirst().mode());
      assertEquals("浦东机场常州线", routes.getFirst().serviceName());
      assertEquals(216, routes.getFirst().referencePriceYuan(), 0.001);
    }
  }

  @Test
  void prefersMainTrainLegWhenCoachAppearsEarlierInTheRoute() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(json("""
          {"status":0,"result":{"routes":[{
            "duration":4200,"price":39.5,
            "steps":[[
              {"vehicle_info":{"type":6,"detail":{"name":"车站接驳大巴"}}},
              {"vehicle_info":{"type":1,"detail":{
                "name":"G7002","price":39.5,
                "departure_station":"常州站","arrive_station":"苏州站",
                "departure_time":"09:00","arrive_time":"09:40"
              }}}
            ]]
          }]}}
          """));
      BaiduMapClient client =
          new BaiduMapClient(
              new OkHttpClient(),
              new ObjectMapper(),
              server.url("/").toString(),
              "test-ak",
              0,
              Clock.systemUTC());

      var routes =
          client.transit(
              new BaiduMapClient.Point(31.81, 119.98),
              new BaiduMapClient.Point(31.30, 120.58),
              LocalDate.of(2026, 8, 27));

      assertEquals(1, routes.size());
      assertEquals("火车", routes.getFirst().mode());
      assertEquals("G7002", routes.getFirst().serviceName());
    }
  }

  @Test
  void rejectsMultiCoachTransferChainAsAFalseDirectRoute() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(json("""
          {"status":0,"result":{"routes":[{
            "duration":19092,"price":-1,
            "steps":[[
              {"vehicle_info":{"type":3,"detail":{"name":"江阴-常州城际巴士"}}},
              {"vehicle_info":{"type":3,"detail":{"name":"苏南机场巴士-江阴线"}}},
              {"vehicle_info":{"type":3,"detail":{"name":"苏南机场巴士-苏州线"}}}
            ]]
          }]}}
          """));
      BaiduMapClient client =
          new BaiduMapClient(
              new OkHttpClient(),
              new ObjectMapper(),
              server.url("/").toString(),
              "test-ak",
              0,
              Clock.systemUTC());

      var routes =
          client.transit(
              new BaiduMapClient.Point(31.81, 119.98),
              new BaiduMapClient.Point(31.30, 120.58),
              LocalDate.of(2026, 8, 27));

      assertTrue(routes.isEmpty());
    }
  }

  @Test
  void rejectsProviderErrorsWithoutExposingApiKey() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(json("{\"status\":4,\"message\":\"quota failure\"}"));
      BaiduMapClient client =
          new BaiduMapClient(
              new OkHttpClient(),
              new ObjectMapper(),
              server.url("/").toString(),
              "secret-ak",
              0,
              Clock.systemUTC());
      Exception error =
          org.junit.jupiter.api.Assertions.assertThrows(
              Exception.class, () -> client.geocode("上海"));
      assertTrue(error.getMessage().contains("status=4"));
      org.junit.jupiter.api.Assertions.assertFalse(error.getMessage().contains("secret-ak"));
    }
  }

  @Test
  void treatsNoTransitPlanAsAnExpectedEmptyResult() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(json("{\"status\":1001,\"message\":\"没有公交方案\"}"));
      BaiduMapClient client =
          new BaiduMapClient(
              new OkHttpClient(),
              new ObjectMapper(),
              server.url("/").toString(),
              "test-ak",
              0,
              Clock.systemUTC());

      var routes =
          client.transit(
              new BaiduMapClient.Point(31.81, 119.98),
              new BaiduMapClient.Point(39.90, 116.40),
              LocalDate.of(2026, 10, 20));

      assertTrue(routes.isEmpty());
    }
  }

  @Test
  void cachesMatchedPoiAndRejectsUnrelatedFirstResult() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(json("""
          {"status":0,"results":[{"name":"上海科技馆","address":"错误候选"}]}
          """));
      BaiduMapClient client =
          new BaiduMapClient(
              new OkHttpClient(),
              new ObjectMapper(),
              server.url("/").toString(),
              "test-ak",
              0,
              Clock.systemUTC());

      assertTrue(client.searchPoi("上海", "上海博物馆").isEmpty());
      assertTrue(client.searchPoi("上海", "上海博物馆").isEmpty());
      assertEquals(1, server.getRequestCount());
    }
  }

  @Test
  void serializesRequestsToStayBelowPersonalConcurrencyLimit() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      AtomicInteger active = new AtomicInteger();
      AtomicInteger maximum = new AtomicInteger();
      server.setDispatcher(
          new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
              int current = active.incrementAndGet();
              maximum.accumulateAndGet(current, Math::max);
              Thread.sleep(50);
              active.decrementAndGet();
              return json("{\"status\":0,\"result\":{\"routes\":[]}}");
            }
          });
      BaiduMapClient client =
          new BaiduMapClient(
              new OkHttpClient(),
              new ObjectMapper(),
              server.url("/").toString(),
              "test-ak",
              0,
              Clock.systemUTC());
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var first =
            executor.submit(
                () ->
                    client.transit(
                        new BaiduMapClient.Point(31.81, 119.98),
                        new BaiduMapClient.Point(31.23, 121.48),
                        LocalDate.of(2026, 8, 27)));
        var second =
            executor.submit(
                () ->
                    client.transit(
                        new BaiduMapClient.Point(31.23, 121.48),
                        new BaiduMapClient.Point(31.81, 119.98),
                        LocalDate.of(2026, 8, 28)));
        first.get();
        second.get();
      }
      assertEquals(1, maximum.get());
    }
  }

  private static MockResponse json(String body) {
    return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
  }
}
