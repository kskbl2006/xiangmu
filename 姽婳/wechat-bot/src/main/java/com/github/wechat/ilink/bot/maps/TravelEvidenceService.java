package com.github.wechat.ilink.bot.maps;

import com.github.wechat.ilink.bot.agent.PlaceCandidate;
import com.github.wechat.ilink.bot.agent.TravelBrief;
import com.github.wechat.ilink.bot.agent.TravelEvidenceProvider;
import com.github.wechat.ilink.bot.agent.TravelMapData;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 合并地图 POI 与额度受控的铁路数据，并提供稳定降级。 */
public final class TravelEvidenceService implements TravelEvidenceProvider {
  private static final Logger log = LoggerFactory.getLogger(TravelEvidenceService.class);
  private final TravelMapService mapService;
  private final JuheRailClient railClient;

  public TravelEvidenceService(TravelMapService mapService, JuheRailClient railClient) {
    this.mapService = mapService;
    this.railClient = railClient;
  }

  @Override
  public String checkpointVariant() {
    return railClient.isEnabled() ? "rail-enabled-v1" : "rail-disabled-v1";
  }

  @Override
  public TravelMapData collect(TravelBrief brief, List<PlaceCandidate> candidates) {
    if (!railClient.isEnabled()
        || brief.origin().isBlank()
        || brief.origin().equals(brief.destination())) {
      return mapService.collect(brief, candidates);
    }
    List<TravelMapData.RouteOption> liveOutbound = List.of();
    List<TravelMapData.RouteOption> liveInbound = List.of();
    List<String> railWarnings = new ArrayList<>();
    LocalDate returnDate = brief.startDate().plusDays(brief.days() - 1L);
    try {
      liveOutbound = railClient.query(brief.origin(), brief.destination(), brief.startDate());
      liveInbound = railClient.query(brief.destination(), brief.origin(), returnDate);
      if (!liveOutbound.isEmpty() || !liveInbound.isEmpty()) {
        railWarnings.add("铁路班次和参考票价来自聚合数据，余票与最终价格请在12306确认");
      }
    } catch (Exception e) {
      log.warn("Juhe rail enrichment failed; keeping map fallback: {}", e.getMessage());
      railWarnings.add("铁路动态查询失败，已保留地图交通候选或官方查询入口");
    }
    if (liveOutbound.isEmpty()
        && liveInbound.isEmpty()
        && !railClient.isAvailable()
        && railClient.operationalState() != JuheRailClient.RailState.DISABLED) {
      railWarnings.add("铁路查询已达到本地额度上限或临时熔断，本次使用地图交通降级");
    }
    boolean completeRail = !liveOutbound.isEmpty() && !liveInbound.isEmpty();
    TravelMapData mapData = mapService.collect(brief, candidates, !completeRail);
    List<TravelMapData.RouteOption> outbound =
        liveOutbound.isEmpty() ? mapData.outboundRoutes() : liveOutbound;
    List<TravelMapData.RouteOption> inbound =
        liveInbound.isEmpty() ? mapData.returnRoutes() : liveInbound;
    List<String> warnings = new ArrayList<>(mapData.warnings());
    warnings.addAll(railWarnings);
    return new TravelMapData(
        mapData.enabled() || !liveOutbound.isEmpty() || !liveInbound.isEmpty(),
        brief.origin(),
        brief.destination(),
        outbound,
        inbound,
        mapData.poiByTitle(),
        warnings);
  }
}
