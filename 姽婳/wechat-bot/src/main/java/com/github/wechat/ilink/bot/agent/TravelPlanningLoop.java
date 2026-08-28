package com.github.wechat.ilink.bot.agent;

/** 执行有限轮次的规划、评估和修复闭环。 */
public final class TravelPlanningLoop {
  private final CnTripPlannerSkill planner;
  private final TravelPlanReviewSkill reviewer;
  private final DynamicBudgetEngine budgetEngine;
  private final CityRoutePlanner routePlanner;
  private final ItineraryScheduler scheduler;
  private final int maxRepairRounds;

  public TravelPlanningLoop(
      CnTripPlannerSkill planner,
      TravelPlanReviewSkill reviewer,
      DynamicBudgetEngine budgetEngine,
      int maxRepairRounds) {
    this.planner = planner;
    this.reviewer = reviewer;
    this.budgetEngine = budgetEngine;
    this.routePlanner = new CityRoutePlanner();
    this.scheduler = new ItineraryScheduler();
    this.maxRepairRounds = Math.max(0, maxRepairRounds);
  }

  public Outcome run(PlanningContext context) {
    int transport = context.mapData().referenceRoundTripCost(context.brief().travelers());
    int destinationBudget = Math.max(0, context.brief().budgetYuan() - transport);
    TravelPlan plan =
        planner.build(
            context.brief(), context.forecast(), context.knowledgeHits(), destinationBudget);
    plan = scheduler.schedule(plan, context.mapData());
    plan = withMapRoutesAndBudget(plan, context.mapData());
    context.planned(plan);

    TravelPlanReviewSkill.ReviewResult review = reviewer.review(plan);
    context.evaluated(review.issues());
    while (!review.passed() && context.repairRound() < maxRepairRounds) {
      TravelPlan repaired = reviewer.repair(plan, review.issues());
      repaired = scheduler.schedule(repaired, context.mapData());
      repaired = withMapRoutesAndBudget(repaired, context.mapData());
      context.repaired(repaired);
      plan = repaired;
      review = reviewer.review(plan);
      context.evaluated(review.issues());
    }
    TravelPlan completed =
        new TravelPlan(
            plan.brief(),
            plan.forecast(),
            plan.mapData(),
            plan.days(),
            plan.cityLegs(),
            plan.budget(),
            plan.generationMode(),
            plan.executionSteps(),
            plan.reviewRounds(),
            review.issues());
    context.complete(completed);
    return new Outcome(completed, review);
  }

  private TravelPlan withMapRoutesAndBudget(TravelPlan plan, TravelMapData mapData) {
    TravelPlan mapped =
        new TravelPlan(
            plan.brief(),
            plan.forecast(),
            mapData,
            plan.days(),
            plan.budget(),
            plan.generationMode(),
            plan.executionSteps(),
            plan.reviewRounds(),
            plan.reviewIssues());
    java.util.List<TravelPlan.TravelLeg> legs = routePlanner.plan(mapped, mapData);
    mapped =
        new TravelPlan(
            mapped.brief(),
            mapped.forecast(),
            mapped.mapData(),
            mapped.days(),
            legs,
            mapped.budget(),
            mapped.generationMode(),
            mapped.executionSteps(),
            mapped.reviewRounds(),
            mapped.reviewIssues());
    TravelPlan.Budget budget = budgetEngine.calculate(plan.brief(), mapped, mapData);
    return new TravelPlan(
        mapped.brief(),
        mapped.forecast(),
        mapped.mapData(),
        mapped.days(),
        mapped.cityLegs(),
        budget,
        mapped.generationMode(),
        mapped.executionSteps(),
        mapped.reviewRounds(),
        mapped.reviewIssues());
  }

  public record Outcome(TravelPlan plan, TravelPlanReviewSkill.ReviewResult review) {}
}
