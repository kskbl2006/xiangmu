package com.wechat.bot.skill;

import java.time.LocalDate;
import java.util.List;
import java.util.Random;

/**
 * 自定义 Skill 之「今日运势」（kevin 独立实现）。
 * <p>触发关键词：运势 / 占卜 / 抽签 / 锦鲤。
 * <p>实现要点：
 * <ul>
 *   <li>纯本地执行，不调用 LLM——展示 Skill「关键词直达、零 token、毫秒级响应」的价值</li>
 *   <li>随机种子 = 用户ID + 日期：同一人同一天反复问结果不变（防「不停抽签刷大吉」），
 *       不同用户/不同日期各自独立</li>
 * </ul>
 */
public class FortuneSkill implements Skill {

    private static final String[] GRADES = {"大吉 ⭐⭐⭐⭐⭐", "中吉 ⭐⭐⭐⭐", "小吉 ⭐⭐⭐", "平 ⭐⭐", "小凶 ⭐"};
    private static final String[] COLORS = {"红色", "橙色", "金色", "绿色", "青色", "蓝色", "紫色", "白色", "黑色"};
    private static final String[] DOS = {"埋头搞钱", "大胆表达", "整理工位", "约朋友吃饭", "早睡早起", "学习新东西", "运动半小时"};
    private static final String[] DONTS = {"熬夜刷手机", "和人争论", "冲动消费", "拖欠工作", "喝奶茶", "乱立 flag"};
    private static final String[] ADVICES = {
            "稳住节奏，把最重要的一件事做完就是胜利。",
            "遇事多想十秒钟，今天的直觉不太靠谱。",
            "主动一点，好消息藏在一次打招呼里。",
            "别贪多，今天适合收尾而不是开新坑。",
            "多喝水多走动，状态会自己回来的。"};

    @Override
    public String name() {
        return "fortune";
    }

    @Override
    public String description() {
        return "今日运势占卜：每天为每位用户生成确定的运势等级、幸运数字/颜色与宜忌建议";
    }

    @Override
    public List<String> triggerKeywords() {
        return List.of("运势", "占卜", "抽签", "锦鲤");
    }

    @Override
    public String execute(String userId, String text) {
        String today = LocalDate.now().toString();
        // 种子绑定「用户 + 日期」：同人同日结果稳定，跨人/跨日独立
        Random random = new Random((userId + "|" + today).hashCode());

        String grade = GRADES[random.nextInt(GRADES.length)];
        int luckyNumber = random.nextInt(9) + 1;
        String luckyColor = COLORS[random.nextInt(COLORS.length)];
        String doThing = DOS[random.nextInt(DOS.length)];
        String dontThing = DONTS[random.nextInt(DONTS.length)];
        String advice = ADVICES[random.nextInt(ADVICES.length)];

        return "🔮 今日运势（" + today + "）\n"
                + "综合运势：" + grade + "\n"
                + "幸运数字：" + luckyNumber + "　幸运颜色：" + luckyColor + "\n"
                + "宜：" + doThing + "\n"
                + "忌：" + dontThing + "\n"
                + "💡 " + advice + "\n"
                + "（同一天内重复占卜结果不变哦，明天再来～）";
    }
}
