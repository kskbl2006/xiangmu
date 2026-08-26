package com.github.wechat.ilink.bot.agent;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runtime implementation for the input-normalization portion of cn-trip-planner. */
public final class TravelBriefSkill {
  private static final Logger log = LoggerFactory.getLogger(TravelBriefSkill.class);
  private static final List<String> SUPPORTED_CITIES = List.of("上海", "杭州", "苏州");
  private static final Set<String> PLAN_WORDS = Set.of("规划", "行程", "攻略", "几日游", "一日游", "两日游", "三日游", "旅行方案");
  private static final Pattern ARABIC_DAYS = Pattern.compile("(\\d{1,2})\\s*(?:天|日游)");
  private static final Pattern PEOPLE = Pattern.compile("(\\d{1,2})\\s*(?:个)?人");
  private static final Pattern CHINESE_PEOPLE =
      Pattern.compile("([一二两三四五六七八九十]{1,3})\\s*(?:个)?人");
  private static final Pattern ORIGIN_WITH_FROM =
      Pattern.compile("从\\s*([\\p{IsHan}·]{2,12}?)(?:市)?\\s*(?:出发|去|到|前往)");
  private static final Pattern ORIGIN_WITH_DEPARTURE =
      Pattern.compile("([\\p{IsHan}·]{2,8}?)(?:市)?\\s*出发");
  private static final Pattern BUDGET = Pattern.compile("(?:预算(?:是|为|约|大概)?\\s*)?(\\d{1,7})\\s*(?:元|块)");
  private static final Pattern FULL_DATE =
      Pattern.compile("(20\\d{2})\\s*(?:[-/.年])\\s*(\\d{1,2})\\s*(?:[-/.月])\\s*(\\d{1,2})\\s*(?:日|号)?");
  private static final Pattern MONTH_DAY =
      Pattern.compile("(?<!\\d)(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*(?:日|号)?");
  private static final List<String> INTERESTS =
      List.of("文化", "历史", "美食", "亲子", "自然", "夜景", "购物", "博物馆", "园林", "拍照");

  private final SkillDefinition definition = SkillDefinition.load("cn-trip-planner");
  private final CityResolver cityResolver;
  private final Clock clock;

  @FunctionalInterface
  public interface CityResolver {
    String resolve(String message);
  }

  public TravelBriefSkill() {
    this(null, Clock.system(ZoneId.of("Asia/Shanghai")));
  }

  public TravelBriefSkill(CityResolver cityResolver) {
    this(cityResolver, Clock.system(ZoneId.of("Asia/Shanghai")));
  }

  TravelBriefSkill(CityResolver cityResolver, Clock clock) {
    this.cityResolver = cityResolver;
    this.clock = clock;
  }

  public SkillDefinition definition() {
    return definition;
  }

  public boolean supports(String message) {
    if (message == null || message.isBlank()) return false;
    return PLAN_WORDS.stream().anyMatch(message::contains)
        || message.matches(".*(?:去|到).{2,12}(?:玩|旅游|旅行|度假).*")
        || (message.matches(".*(?:玩|旅游|旅行|度假).*")
            && (ARABIC_DAYS.matcher(message).find()
                || PEOPLE.matcher(message).find()
                || BUDGET.matcher(message).find()))
        || (SUPPORTED_CITIES.stream().anyMatch(message::contains)
            && (message.contains("天") || message.contains("旅游") || message.contains("旅行")));
  }

  public TravelBrief normalize(String message) {
    if (message == null || message.isBlank()) {
      throw new IllegalArgumentException("旅行目标不能为空");
    }
    List<String> assumptions = new ArrayList<>();
    String origin = extractOrigin(message);
    String destination =
        SUPPORTED_CITIES.stream()
            .filter(message::contains)
            .filter(city -> !city.equals(origin))
            .findFirst()
            .orElse("");
    if (destination.isBlank() && cityResolver != null && !explicitlyOmitsDestination(message)) {
      try {
        destination = normalizeDestination(cityResolver.resolve(message));
      } catch (RuntimeException e) {
        log.warn("Travel destination resolution failed: {}", e.getMessage());
      }
    }

    Integer parsedDays = firstInt(ARABIC_DAYS, message);
    int days = parsedDays == null ? chineseDays(message) : parsedDays;
    if (days <= 0) {
      days = 3;
      assumptions.add("未提供天数，按3天规划");
    } else if (days > 7) {
      days = 7;
      assumptions.add("当前MVP最多生成7天，已按7天规划");
    }

    Integer parsedTravelers = firstInt(PEOPLE, message);
    int travelers =
        parsedTravelers == null ? chineseTravelers(message) : parsedTravelers;
    if (travelers <= 0) {
      travelers = 2;
      assumptions.add("未提供人数，按2人规划");
    } else if (travelers > 20) {
      travelers = 20;
      assumptions.add("当前MVP最多支持20人，已按20人规划");
    }

    Integer parsedBudget = firstInt(BUDGET, message);
    boolean unlimitedBudget = message.contains("预算不限") || message.contains("预算不限制");
    int budget =
        parsedBudget == null
            ? travelers * days * (unlimitedBudget ? 1_000 : 600)
            : parsedBudget;
    if (unlimitedBudget && parsedBudget == null) {
      assumptions.add("预算未设上限，按每人每天1000元的舒适型落地支出生成预算参考");
    } else if (parsedBudget == null) {
      assumptions.add("未提供预算，按每人每天600元落地支出估算");
    }
    assumptions.add("预算按目的地落地支出估算，不含往返大交通");

    String pace =
        message.contains("轻松") || message.contains("少走路")
            ? "relaxed"
            : message.contains("紧凑") || message.contains("多玩") ? "packed" : "balanced";
    LinkedHashSet<String> interests = new LinkedHashSet<>();
    for (String interest : INTERESTS) {
      if (message.contains(interest)) interests.add(interest);
    }
    if (interests.isEmpty()) {
      interests.add("城市经典");
      assumptions.add("未提供兴趣偏好，优先选择城市经典项目");
    }
    LocalDate today = LocalDate.now(clock);
    LocalDate startDate = resolveStartDate(message, today);
    boolean startDateExplicit = containsExplicitStartDate(message);
    if (!startDateExplicit) {
      assumptions.add("未提供出发日期，按今天（" + today + "）开始规划");
    }
    return new TravelBrief(
        origin,
        destination,
        days,
        travelers,
        budget,
        pace,
        List.copyOf(interests),
        assumptions,
        startDate,
        startDateExplicit);
  }

  private static boolean explicitlyOmitsDestination(String message) {
    return message != null
        && message.matches(".*(?:去|到|前往)\\s*(?:玩|旅游|旅行|度假)(?:\\s|，|。|！|？|\\d).*");
  }

  private static String extractOrigin(String message) {
    Matcher from = ORIGIN_WITH_FROM.matcher(message);
    if (from.find()) return normalizeDestination(from.group(1));
    Matcher departure = ORIGIN_WITH_DEPARTURE.matcher(message);
    return departure.find() ? normalizeDestination(departure.group(1)) : "";
  }

  private static Integer firstInt(Pattern pattern, String message) {
    Matcher matcher = pattern.matcher(message);
    return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
  }

  private static int chineseDays(String message) {
    if (message.contains("两日游") || message.contains("两天")) return 2;
    String[] values = {"一", "二", "三", "四", "五", "六", "七"};
    for (int index = 0; index < values.length; index++) {
      if (message.contains(values[index] + "日游") || message.contains(values[index] + "天")) {
        return index + 1;
      }
    }
    return 0;
  }

  private static int chineseTravelers(String message) {
    if (message.contains("一家三口")) return 3;
    if (message.contains("一家四口")) return 4;
    if (message.contains("情侣") || message.contains("两人") || message.contains("两个人")) return 2;
    if (message.contains("独自") || message.contains("一个人") || message.contains("单人")) return 1;
    Matcher matcher = CHINESE_PEOPLE.matcher(message);
    return matcher.find() ? chineseNumber(matcher.group(1)) : 0;
  }

  private static int chineseNumber(String value) {
    if (value == null || value.isBlank()) return 0;
    if ("十".equals(value)) return 10;
    int ten = value.indexOf('十');
    if (ten >= 0) {
      int tens = ten == 0 ? 1 : chineseDigit(value.charAt(ten - 1));
      int units = ten == value.length() - 1 ? 0 : chineseDigit(value.charAt(ten + 1));
      return tens * 10 + units;
    }
    return value.length() == 1 ? chineseDigit(value.charAt(0)) : 0;
  }

  private static int chineseDigit(char value) {
    return switch (value) {
      case '一' -> 1;
      case '二', '两' -> 2;
      case '三' -> 3;
      case '四' -> 4;
      case '五' -> 5;
      case '六' -> 6;
      case '七' -> 7;
      case '八' -> 8;
      case '九' -> 9;
      default -> 0;
    };
  }

  private static String normalizeDestination(String value) {
    if (value == null) return "";
    String destination = value.trim().replaceAll("[\\s，。！？、]+", "");
    if (destination.endsWith("市") && destination.length() > 2) {
      destination = destination.substring(0, destination.length() - 1);
    }
    return destination.matches("[\\p{IsHan}·]{2,20}") ? destination : "";
  }

  static LocalDate resolveStartDate(String message, LocalDate today) {
    Matcher full = FULL_DATE.matcher(message);
    if (full.find()) {
      try {
        LocalDate parsed =
            LocalDate.of(
                Integer.parseInt(full.group(1)),
                Integer.parseInt(full.group(2)),
                Integer.parseInt(full.group(3)));
        return parsed.isBefore(today) ? today : parsed;
      } catch (DateTimeException ignored) {
        return today;
      }
    }
    Matcher monthDay = MONTH_DAY.matcher(message);
    if (monthDay.find()) {
      try {
        MonthDay parsed =
            MonthDay.of(Integer.parseInt(monthDay.group(1)), Integer.parseInt(monthDay.group(2)));
        LocalDate candidate = parsed.atYear(today.getYear());
        return candidate.isBefore(today) ? parsed.atYear(today.getYear() + 1) : candidate;
      } catch (DateTimeException ignored) {
        return today;
      }
    }
    if (message.contains("后天")) return today.plusDays(2);
    if (message.contains("明天")) return today.plusDays(1);
    if (message.contains("今天") || message.contains("今日")) return today;
    return today;
  }

  private static boolean containsExplicitStartDate(String message) {
    return message.contains("今天")
        || message.contains("今日")
        || message.contains("明天")
        || message.contains("后天")
        || FULL_DATE.matcher(message).find()
        || MONTH_DAY.matcher(message).find();
  }
}
