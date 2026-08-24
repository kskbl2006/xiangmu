package com.wechat.bot.skill;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 自定义 Skill 接口：关键词直达的轻量能力单元。
 * <p>与 Function Calling 工具（Tool）的区别：
 * <ul>
 *   <li>Tool：由 LLM 自主决定是否调用，走「LLM→工具→LLM」循环，消耗模型调用</li>
 *   <li>Skill：由本地关键词规则直接路由命中即执行，不经过 LLM，
 *       零延迟、零 token 成本、结果确定可控</li>
 * </ul>
 * <p>适合接入：规则明确、无需推理、要求秒回的场景（查指令、掷骰子、运势等）。
 */
public interface Skill {

    /** Skill 唯一名称（日志与调试用） */
    String name();

    /** 功能描述（日志与帮助文档用） */
    String description();

    /** 触发关键词列表：用户消息包含任一关键词即命中本 Skill */
    List<String> triggerKeywords();

    /**
     * 执行 Skill。
     *
     * @param userId 用户标识（可用于按用户生成确定性结果，如每日运势）
     * @param text   用户原始消息（可从中提取参数）
     * @return 直接回复用户的文本
     */
    String execute(String userId, String text);

    /** 消息是否命中本 Skill 的触发关键词 */
    default boolean match(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return triggerKeywords().stream().anyMatch(text::contains);
    }

    /** 关键词列表编译为正则（供注册中心批量匹配用） */
    default Pattern triggerPattern() {
        return Pattern.compile(String.join("|",
                triggerKeywords().stream()
                        .map(Pattern::quote)
                        .toList()));
    }
}
