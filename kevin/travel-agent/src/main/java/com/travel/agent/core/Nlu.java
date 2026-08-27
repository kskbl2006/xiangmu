package com.travel.agent.core;

import com.travel.agent.util.Json;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 需求解析层（NLU）：意图识别 + 实体抽取 + 参数补全 → 结构化需求。
 *
 * 设计取舍（省 token）：正则规则优先，LLM 兜底——
 * 常规旅行需求 0 次 LLM 调用即可完成解析；仅当规则无法判定意图时才询问一次 LLM。
 */
public final class Nlu {

    private static final Map<String, Integer> CN_NUM = Map.ofEntries(
            Map.entry("一", 1), Map.entry("二", 2), Map.entry("两", 2), Map.entry("三", 3),
            Map.entry("四", 4), Map.entry("五", 5), Map.entry("六", 6), Map.entry("七", 7),
            Map.entry("八", 8), Map.entry("九", 9), Map.entry("十", 10));

    /** 知识库已覆盖城市（poi 工具对未覆盖城市自动降级为通用推荐）。 */
    private static final List<String> KNOWN_CITIES =
            List.of("三亚", "上海", "北京", "成都", "杭州", "西安", "重庆", "广州", "南京", "苏州");

    private static final List<String> TRAVEL_KEYWORDS =
            List.of("旅行", "旅游", "游", "行程", "攻略", "出发", "度假", "自由行", "跟团", "玩");

    /** 偏好关键词（LinkedHashMap 保持输出顺序稳定）。 */
    private static final Map<String, List<String>> PREF_KEYWORDS = new LinkedHashMap<>() {{
        put("美食", List.of("美食", "吃", "小吃", "海鲜"));
        put("亲子", List.of("亲子", "孩子", "儿童", "一家", "熊猫"));
        put("文化", List.of("博物馆", "历史", "文化", "古迹", "寺庙"));
        put("自然", List.of("自然", "风景", "山水", "海滩", "海边", "爬山", "公园"));
        put("购物", List.of("购物", "逛街", "商场", "免税"));
        put("夜生活", List.of("夜市", "夜景", "酒吧", "夜生活", "演出"));
    }};

    private static final String HAN = "[\\u4e00-\\u9fff]";

    private static final Pattern P_DEST_1 =
            Pattern.compile("去(" + HAN + "{2,4}?)(?=\\d+\\s*天|\\d*日|玩|旅|游|行|度|，|\\s|$)");
    private static final Pattern P_DEST_2 =
            Pattern.compile("(" + HAN + "{2,3})(?:\\d+|一|二|两|三|四|五|六|七|八|九|十)?日游");
    private static final Pattern P_DAYS_1 = Pattern.compile("(\\d+)\\s*天");
    private static final Pattern P_DAYS_2 = Pattern.compile("(\\d+)\\s*日");
    private static final Pattern P_DAYS_3 = Pattern.compile("([一二两三四五六七八九十])日游");
    private static final Pattern P_DAYS_4 = Pattern.compile("玩([一二两三四五六七八九十\\d]+)天");
    private static final Pattern P_BUDGET_1 = Pattern.compile("预算\\s*(\\d+(?:\\.\\d+)?)\\s*(万|元|块)?");
    private static final Pattern P_BUDGET_2 = Pattern.compile("(\\d{4,6})\\s*(元|块|RMB)");
    private static final Pattern P_PEOPLE_1 = Pattern.compile("(\\d+)\\s*大\\s*(\\d+)\\s*小");
    private static final Pattern P_PEOPLE_2 = Pattern.compile("(\\d+)\\s*(?:个)?人");
    private static final Pattern P_PEOPLE_3 = Pattern.compile("([一二两三四五六七八九十])\\s*(?:个)?人");
    private static final Pattern P_DEPART_1 = Pattern.compile("从(" + HAN + "{2,4}?)(?=出发)");
    private static final Pattern P_DEPART_2 = Pattern.compile("(" + HAN + "{2,3})出发");
    private static final Pattern P_DATE_MD = Pattern.compile("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]?");
    private static final Pattern P_DATE_WEEK = Pattern.compile("周([一二三四五六日天])");

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private Nlu() {
    }

    /** 意图识别：关键词规则优先，命中失败才调用一次 LLM 兜底。 */
    public static boolean isTravelIntent(String text) {
        for (String kw : TRAVEL_KEYWORDS) {
            if (text.contains(kw)) {
                return true;
            }
        }
        try {
            return "是".equals(Llm.get().complete("判断用户输入是否为旅行规划意图，回答是或否。用户输入：" + text));
        } catch (Exception e) {
            return false;
        }
    }

    private static int num(String cn) {
        return CN_NUM.getOrDefault(cn, 0);
    }

    static String extractDestination(String text) {
        for (Pattern pat : List.of(P_DEST_1, P_DEST_2)) {
            Matcher m = pat.matcher(text);
            if (m.find()) {
                String g = m.group(1);
                for (String city : KNOWN_CITIES) {
                    if (g.contains(city)) {
                        return city;
                    }
                }
                // 未覆盖城市也返回，poi 工具会降级为通用推荐
                String cand = g.trim();
                if (cand.length() >= 2) {
                    return cand;
                }
            }
        }
        return null;
    }

    static Integer extractDays(String text) {
        Matcher m = P_DAYS_1.matcher(text);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        m = P_DAYS_2.matcher(text);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        m = P_DAYS_3.matcher(text);
        if (m.find()) {
            int v = num(m.group(1));
            return v > 0 ? v : 3;
        }
        m = P_DAYS_4.matcher(text);
        if (m.find()) {
            String g = m.group(1);
            return g.chars().allMatch(Character::isDigit) ? Integer.parseInt(g) : num(g);
        }
        return null;
    }

    static Integer extractBudget(String text) {
        Matcher m = P_BUDGET_1.matcher(text);
        if (m.find()) {
            double val = Double.parseDouble(m.group(1));
            if ("万".equals(m.group(2))) {
                val *= 10000;
            }
            return (int) val;
        }
        m = P_BUDGET_2.matcher(text);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return null;
    }

    static int[] extractPeople(String text) {
        Matcher m = P_PEOPLE_1.matcher(text);
        if (m.find()) {
            return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        }
        m = P_PEOPLE_2.matcher(text);
        if (m.find()) {
            return new int[]{Integer.parseInt(m.group(1)), 0};
        }
        m = P_PEOPLE_3.matcher(text);
        if (m.find()) {
            int v = num(m.group(1));
            return new int[]{v > 0 ? v : 2, 0};
        }
        return null;
    }

    static String extractDepart(String text) {
        Matcher m = P_DEPART_1.matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }
        m = P_DEPART_2.matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    static String extractStartDate(String text) {
        LocalDate today = LocalDate.now();
        Matcher m = P_DATE_MD.matcher(text);
        if (m.find()) {
            try {
                return LocalDate.of(today.getYear(),
                        Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))).format(ISO);
            } catch (Exception ignored) {
                // 日期非法（如 2 月 30 日）继续走相对日期
            }
        }
        if (text.contains("后天")) {
            return today.plusDays(2).format(ISO);
        }
        if (text.contains("明天")) {
            return today.plusDays(1).format(ISO);
        }
        m = P_DATE_WEEK.matcher(text);
        if (m.find()) {
            Map<String, Integer> offset = Map.of("一", 0, "二", 1, "三", 2, "四", 3,
                    "五", 4, "六", 5, "日", 6, "天", 6);
            int target = offset.get(m.group(1));
            int dow = today.getDayOfWeek().getValue() - 1;   // 周一=0 … 周日=6
            int delta = ((target - dow) % 7 + 7) % 7;
            if (delta == 0) {
                delta = 7;
            }
            return today.plusDays(delta).format(ISO);
        }
        return null;
    }

    /** 需求解析主入口：返回结构化 request + 需要追问的 questions。 */
    public static NluResult parseRequest(String text) {
        List<String> notes = new ArrayList<>();
        List<String> questions = new ArrayList<>();

        String destination = extractDestination(text);
        if (destination == null) {
            destination = "三亚";
            questions.add("请问目的地是哪里？（未识别到目的地，已默认使用三亚演示）");
        }

        int days = extractDays(text) == null ? 3 : extractDays(text);
        Integer budget = extractBudget(text);
        int realBudget = budget == null ? 3000 : budget;

        int[] people = extractPeople(text);
        int adults;
        int children;
        if (people == null) {
            adults = 2;
            children = 0;
            notes.add("未识别人数，默认 2 人");
        } else {
            adults = people[0];
            children = people[1];
        }

        String departCity = extractDepart(text);
        if (departCity == null) {
            departCity = destination;
        }
        if (departCity.equals(destination)) {
            notes.add("出发地与目的地相同，按市内交通估算");
        }

        String startDate = extractStartDate(text);
        if (startDate == null) {
            startDate = LocalDate.now().plusDays(1).format(ISO);
            notes.add("未识别出行日期，默认明天出发");
        }

        List<String> preferences = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : PREF_KEYWORDS.entrySet()) {
            for (String k : e.getValue()) {
                if (text.contains(k)) {
                    preferences.add(e.getKey());
                    break;
                }
            }
        }

        Map<String, Object> request = Json.obj(
                "goal", text,
                "destination", destination,
                "depart_city", departCity,
                "days", days,
                "budget", realBudget,
                "adults", adults,
                "children", children,
                "people", adults + children,
                "start_date", startDate,
                "preferences", preferences,
                "kb_covered", KNOWN_CITIES.contains(destination),
                "notes", notes);
        return new NluResult(request, questions);
    }
}
