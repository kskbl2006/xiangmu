package com.github.wechat.ilink.bot;

import com.github.wechat.ilink.bot.agent.TravelMapData.RouteOption;
import com.github.wechat.ilink.bot.config.AppConfig;
import com.github.wechat.ilink.bot.maps.JuheRailClient;
import java.time.LocalDate;
import java.util.List;

/** 仅调用一次铁路服务，用于验证低额度配置。 */
public final class RailProviderSmokeTest {
  private RailProviderSmokeTest() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      throw new IllegalArgumentException(
          "Usage: RailProviderSmokeTest <origin> <destination> <yyyy-MM-dd>");
    }
    AppConfig config = AppConfig.fromEnvironment();
    if (!config.hasJuheRailApiKey()) {
      throw new IllegalStateException("JUHE_RAIL_API_KEY is not configured");
    }

    String origin = args[0];
    String destination = args[1];
    LocalDate date = LocalDate.parse(args[2]);
    List<RouteOption> routes = new JuheRailClient(config).query(origin, destination, date);

    System.out.printf(
        "Rail query completed: %s -> %s, date=%s, candidates=%d%n",
        origin, destination, date, routes.size());
    routes.stream()
        .limit(3)
        .forEach(
            route ->
                System.out.printf(
                    "%s | %s -> %s | %d min | %.2f yuan | source=%s%n",
                    route.serviceName(),
                    route.departureTime(),
                    route.arrivalTime(),
                    route.durationMinutes(),
                    route.referencePriceYuan(),
                    route.source()));
  }
}
