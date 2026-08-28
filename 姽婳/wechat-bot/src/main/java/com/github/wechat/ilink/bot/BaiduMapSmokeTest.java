package com.github.wechat.ilink.bot;

import com.github.wechat.ilink.bot.config.AppConfig;
import com.github.wechat.ilink.bot.maps.BaiduMapClient;
import java.time.LocalDate;

/** 配置百度地图服务端 AK 后运行的真实服务冒烟测试。 */
public final class BaiduMapSmokeTest {
  private BaiduMapSmokeTest() {}

  public static void main(String[] args) throws Exception {
    String origin = args.length > 0 ? args[0] : "常州";
    String destination = args.length > 1 ? args[1] : "上海";
    String poiTitle = args.length > 2 ? args[2] : "上海博物馆";
    LocalDate departureDate =
        args.length > 3 ? LocalDate.parse(args[3]) : LocalDate.now().plusDays(1);
    AppConfig config = AppConfig.fromEnvironment();
    if (!config.hasBaiduMapApiKey()) {
      throw new IllegalStateException(
          "请先在 wechat-bot/.env 中配置 BAIDU_MAP_AK（百度地图服务端 AK）");
    }
    BaiduMapClient client = new BaiduMapClient(config);
    BaiduMapClient.Point originPoint =
        client.geocode(origin).orElseThrow(() -> new IllegalStateException("无法解析出发地：" + origin));
    BaiduMapClient.Point destinationPoint =
        client
            .geocode(destination)
            .orElseThrow(() -> new IllegalStateException("无法解析目的地：" + destination));
    var routes = client.transit(originPoint, destinationPoint, departureDate);
    java.util.Optional<com.github.wechat.ilink.bot.agent.TravelMapData.PoiSnapshot> poi =
        "-".equals(poiTitle)
            ? java.util.Optional.empty()
            : client.searchPoi(destination, poiTitle);
    System.out.printf(
        "Baidu Map smoke test succeeded: %s -> %s, routes=%d, poi=%s%n",
        origin, destination, routes.size(), poi.map(value -> value.name()).orElse("未命中"));
    routes.forEach(
        route ->
            System.out.printf(
                "%s %s %s -> %s, %.0f元%n",
                route.mode(),
                route.serviceName(),
                route.departureTime(),
                route.arrivalTime(),
                route.referencePriceYuan()));
  }
}
