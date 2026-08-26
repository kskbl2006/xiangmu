package com.github.wechat.ilink.bot.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TravelBriefSkillTest {
  private final TravelBriefSkill skill = new TravelBriefSkill();

  @Test
  void normalizesOneSentenceGoalWithoutInterviewingUser() {
    TravelBrief brief =
        skill.normalize("帮我规划上海三日游，2人预算5000元，喜欢历史、美食和夜景，节奏轻松");

    assertEquals("上海", brief.destination());
    assertEquals(3, brief.days());
    assertEquals(2, brief.travelers());
    assertEquals(5000, brief.budgetYuan());
    assertEquals("relaxed", brief.pace());
    assertTrue(brief.interests().contains("历史"));
    assertTrue(skill.supports("做一份杭州三日游行程"));
    assertEquals(2, skill.normalize("杭州两日游，一个人，节奏轻松").days());
    TravelBrief withOrigin = skill.normalize("8月30日从常州出发去上海玩3天，2人预算5000元");
    assertEquals("常州", withOrigin.origin());
    assertEquals("上海", withOrigin.destination());
    assertEquals(3, skill.normalize("三个人去上海玩三天").travelers());
    assertEquals(3, skill.normalize("3个人去上海玩三天").travelers());
    assertEquals(4, skill.normalize("一家四口去杭州两日游").travelers());
    assertEquals(12, skill.normalize("十二人去苏州玩2天").travelers());
  }

  @Test
  void recordsConservativeDefaults() {
    TravelBrief brief = skill.normalize("帮我做苏州旅行攻略");

    assertEquals(3, brief.days());
    assertEquals(2, brief.travelers());
    assertEquals(3600, brief.budgetYuan());
    assertTrue(brief.assumptions().stream().anyMatch(value -> value.contains("天数")));
    assertTrue(brief.assumptions().stream().anyMatch(value -> value.contains("预算")));
  }

  @Test
  void capsMvpBoundariesAndHandlesUnlimitedBudgetAsAnEstimate() {
    TravelBrief capped = skill.normalize("30个人去上海玩12天，预算50000元");
    assertEquals(20, capped.travelers());
    assertEquals(7, capped.days());
    assertTrue(capped.assumptions().stream().anyMatch(value -> value.contains("最多支持20人")));
    assertTrue(capped.assumptions().stream().anyMatch(value -> value.contains("最多生成7天")));

    TravelBrief unlimited = skill.normalize("三个人去杭州玩3天，预算不限");
    assertEquals(9000, unlimited.budgetYuan());
    assertTrue(unlimited.assumptions().stream().anyMatch(value -> value.contains("预算未设上限")));
  }

  @Test
  void resolvesCitiesOutsideBundledKnowledgeWithModelResolver() {
    TravelBriefSkill broadSkill = new TravelBriefSkill(message -> "北京市");

    TravelBrief brief = broadSkill.normalize("两个人去北京玩三天，预算5000元");

    assertEquals("北京", brief.destination());
    assertTrue(brief.supported());
    assertTrue(broadSkill.supports("两个人去北京玩三天，预算5000元"));
  }

  @Test
  void neverMistakesSupportedOriginForDestination() {
    TravelBriefSkill broadSkill = new TravelBriefSkill(message -> "西安");

    TravelBrief supplemented =
        broadSkill.normalize("从苏州出发；2026年10月20日去西安玩4天，2人预算5000元");
    assertEquals("苏州", supplemented.origin());
    assertEquals("西安", supplemented.destination());

    TravelBrief local = skill.normalize("从上海出发去苏州玩3天，2人预算3000元");
    assertEquals("上海", local.origin());
    assertEquals("苏州", local.destination());
  }

  @Test
  void keepsIncompleteNewGoalSeparateFromPreviousDestination() {
    AtomicInteger resolverCalls = new AtomicInteger();
    TravelBriefSkill broadSkill =
        new TravelBriefSkill(
            message -> {
              resolverCalls.incrementAndGet();
              return "苏州";
            });

    String withOrigin = "从常州去玩4天，2人预算5000元，喜欢历史和美食";
    TravelBrief first = broadSkill.normalize(withOrigin);
    assertTrue(broadSkill.supports(withOrigin));
    assertEquals("常州", first.origin());
    assertEquals("", first.destination());

    String withoutLocations = "去玩4天，2人预算5000元，喜欢历史和美食";
    TravelBrief second = broadSkill.normalize(withoutLocations);
    assertTrue(broadSkill.supports(withoutLocations));
    assertEquals("", second.origin());
    assertEquals("", second.destination());
    assertEquals(0, resolverCalls.get());
  }

  @Test
  void resolvesExplicitAndDefaultTripDates() {
    Clock clock =
        Clock.fixed(Instant.parse("2026-08-25T04:00:00Z"), ZoneId.of("Asia/Shanghai"));
    TravelBriefSkill datedSkill = new TravelBriefSkill(null, clock);

    assertEquals(
        LocalDate.of(2026, 9, 3),
        datedSkill.normalize("9月3日去上海玩3天").startDate());
    assertEquals(
        LocalDate.of(2026, 9, 3),
        datedSkill.normalize("今天帮我规划9月3日出发的上海3天行程").startDate());
    assertEquals(
        LocalDate.of(2026, 8, 26),
        datedSkill.normalize("明天去杭州两日游").startDate());
    TravelBrief defaultDate = datedSkill.normalize("规划苏州三日游");
    assertEquals(LocalDate.of(2026, 8, 25), defaultDate.startDate());
    assertTrue(defaultDate.assumptions().stream().anyMatch(value -> value.contains("按今天")));
  }
}
