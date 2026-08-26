package com.github.wechat.ilink.bot.maps;

import com.github.wechat.ilink.bot.agent.TravelBrief;
import com.github.wechat.ilink.bot.agent.TravelMapData;
import com.github.wechat.ilink.bot.agent.TravelMapData.PoiSnapshot;
import com.github.wechat.ilink.bot.agent.TravelMapData.RouteOption;
import com.github.wechat.ilink.bot.agent.TravelPlan;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Enriches a completed itinerary with dynamic map facts while preserving no-key fallback. */
public final class TravelMapService {
  private static final Logger log = LoggerFactory.getLogger(TravelMapService.class);
  private final BaiduMapClient client;
  private final int maxPoiQueries;

  public TravelMapService(BaiduMapClient client) {
    this(client, 8);
  }

  public TravelMapService(BaiduMapClient client, int maxPoiQueries) {
    this.client = client;
    this.maxPoiQueries = Math.max(0, maxPoiQueries);
  }

  public TravelMapData enrich(TravelBrief brief, TravelPlan plan) {
    if (!client.isConfigured()) {
      return TravelMapData.disabled(brief.origin(), brief.destination());
    }
    List<String> warnings = new ArrayList<>();
    List<RouteOption> outbound = List.of();
    List<RouteOption> inbound = List.of();
    if (brief.origin().isBlank()) {
      warnings.add("未提供出发地，未查询往返大交通");
    } else if (!brief.origin().equals(brief.destination())) {
      try {
        Optional<BaiduMapClient.Point> origin = client.geocode(brief.origin());
        Optional<BaiduMapClient.Point> destination = client.geocode(brief.destination());
        if (origin.isPresent() && destination.isPresent()) {
          LocalDate returnDate = brief.startDate().plusDays(brief.days() - 1L);
          try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<List<RouteOption>> outboundFuture =
                executor.submit(
                    () -> client.transit(origin.get(), destination.get(), brief.startDate()));
            Future<List<RouteOption>> inboundFuture =
                executor.submit(() -> client.transit(destination.get(), origin.get(), returnDate));
            outbound = outboundFuture.get();
            inbound = inboundFuture.get();
          }
          log.info(
              "Baidu transit candidates: {} -> {}, outbound={}, return={}",
              brief.origin(),
              brief.destination(),
              summarizeRoutes(outbound),
              summarizeRoutes(inbound));
          if (outbound.isEmpty() || inbound.isEmpty()) warnings.add("地图服务未返回完整的往返交通候选");
        } else {
          warnings.add("地图服务未能解析出发地或目的地，往返交通暂缺");
        }
      } catch (Exception e) {
        log.warn("Baidu transit enrichment failed: {}", e.getMessage());
        warnings.add("往返交通动态查询失败，请通过官方交通平台复核");
      }
    }

    List<String> titles =
        plan.days().stream()
        .flatMap(day -> day.activities().stream())
        .map(TravelPlan.Activity::title)
        .distinct()
        .limit(maxPoiQueries)
        .toList();
    Map<String, PoiSnapshot> pois = new LinkedHashMap<>();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Map<String, Future<Optional<PoiSnapshot>>> futures = new LinkedHashMap<>();
      for (String title : titles) {
        futures.put(title, executor.submit(() -> client.searchPoi(brief.destination(), title)));
      }
      for (Map.Entry<String, Future<Optional<PoiSnapshot>>> entry : futures.entrySet()) {
        try {
          entry.getValue().get().ifPresent(poi -> pois.put(entry.getKey(), poi));
        } catch (Exception e) {
          log.warn("Baidu POI enrichment failed for {}: {}", entry.getKey(), e.getMessage());
        }
      }
    }
    if (pois.isEmpty()) warnings.add("景点动态详情暂不可用，行程仍使用知识库与模型结果");
    return new TravelMapData(
        true,
        brief.origin(),
        brief.destination(),
        outbound,
        inbound,
        pois,
        warnings);
  }

  private static String summarizeRoutes(List<RouteOption> routes) {
    if (routes.isEmpty()) return "none";
    return routes.stream()
        .map(route -> route.mode() + ":" + route.serviceName())
        .limit(3)
        .toList()
        .toString();
  }
}
