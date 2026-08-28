package com.github.wechat.ilink.bot.agent;

import java.util.List;

/** 规划前的外部证据接口，可接入地图等服务。 */
@FunctionalInterface
public interface TravelEvidenceProvider {
  TravelMapData collect(TravelBrief brief, List<PlaceCandidate> candidates);

  /** 区分不同服务配置生成的检查点。 */
  default String checkpointVariant() {
    return "default";
  }

  static TravelEvidenceProvider disabled() {
    return (brief, candidates) -> TravelMapData.disabled(brief.origin(), brief.destination());
  }
}
