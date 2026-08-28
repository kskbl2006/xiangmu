package com.github.wechat.ilink.bot.agent;

import com.github.wechat.ilink.bot.travelrag.InMemoryTravelVectorStore;
import java.util.ArrayList;
import java.util.List;

/** 单次请求中供收集、规划、评估和修复共享的状态。 */
public final class PlanningContext {
  public enum Stage {
    NORMALIZED,
    EVIDENCE_COLLECTED,
    PLANNED,
    EVALUATED,
    REPAIRED,
    COMPLETED
  }

  private final String goal;
  private final TravelBrief brief;
  private TravelForecast forecast;
  private List<InMemoryTravelVectorStore.Hit> knowledgeHits = List.of();
  private List<PlaceCandidate> candidates = List.of();
  private TravelMapData mapData;
  private TravelPlan plan;
  private List<TravelPlan.ReviewIssue> violations = List.of();
  private int repairRound;
  private Stage stage = Stage.NORMALIZED;
  private final List<String> trace = new ArrayList<>();

  public PlanningContext(String goal, TravelBrief brief) {
    this.goal = goal == null ? "" : goal;
    this.brief = brief;
    this.forecast = TravelForecast.unavailable(brief.destination(), brief.startDate(), brief.days());
    this.mapData = TravelMapData.disabled(brief.origin(), brief.destination());
    trace.add("需求已规范化");
  }

  public String goal() { return goal; }
  public TravelBrief brief() { return brief; }
  public TravelForecast forecast() { return forecast; }
  public List<InMemoryTravelVectorStore.Hit> knowledgeHits() { return knowledgeHits; }
  public List<PlaceCandidate> candidates() { return candidates; }
  public TravelMapData mapData() { return mapData; }
  public TravelPlan plan() { return plan; }
  public List<TravelPlan.ReviewIssue> violations() { return violations; }
  public int repairRound() { return repairRound; }
  public Stage stage() { return stage; }
  public List<String> trace() { return List.copyOf(trace); }

  public void evidence(
      TravelForecast forecast,
      List<InMemoryTravelVectorStore.Hit> hits,
      List<PlaceCandidate> candidates,
      TravelMapData mapData) {
    this.forecast = forecast;
    this.knowledgeHits = hits == null ? List.of() : List.copyOf(hits);
    this.candidates = candidates == null ? List.of() : List.copyOf(candidates);
    this.mapData = mapData == null ? TravelMapData.disabled(brief.origin(), brief.destination()) : mapData;
    stage = Stage.EVIDENCE_COLLECTED;
    trace.add("天气、候选地点与交通证据已收集");
  }

  public void planned(TravelPlan plan) {
    this.plan = plan;
    stage = Stage.PLANNED;
    trace.add("已生成第" + (repairRound + 1) + "版方案");
  }

  public void evaluated(List<TravelPlan.ReviewIssue> issues) {
    this.violations = issues == null ? List.of() : List.copyOf(issues);
    stage = Stage.EVALUATED;
    trace.add("已完成第" + (repairRound + 1) + "轮约束检查");
  }

  public void repaired(TravelPlan repaired) {
    repairRound++;
    this.plan = repaired;
    stage = Stage.REPAIRED;
    trace.add("已根据违规项自动修复第" + repairRound + "轮");
  }

  public void complete(TravelPlan finalPlan) {
    this.plan = finalPlan;
    stage = Stage.COMPLETED;
    trace.add("规划闭环结束");
  }
}
