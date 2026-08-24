package com.wechat.bot.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Skill 注册中心：统一管理所有自定义 Skill，提供「按消息关键词匹配」的路由能力。
 * <p>消息路由链的最前置环节：命中即直接执行回复，不再走 RAG / LLM。
 */
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private final Map<String, Skill> skills = new LinkedHashMap<>();

    public SkillRegistry register(Skill skill) {
        skills.put(skill.name(), skill);
        log.info("注册 Skill：{}（关键词：{}）- {}", skill.name(), skill.triggerKeywords(), skill.description());
        return this;
    }

    /** 已注册的 Skill 列表（帮助文档用） */
    public List<Skill> all() {
        return new ArrayList<>(skills.values());
    }

    /**
     * 按消息内容匹配 Skill：遍历所有已注册 Skill 的触发关键词，
     * 返回第一个命中者（注册顺序即优先级）。
     */
    public Optional<Skill> match(String text) {
        for (Skill skill : skills.values()) {
            if (skill.match(text)) {
                log.info("Skill 命中：{}（消息：{}）", skill.name(), mask(text));
                return Optional.of(skill);
            }
        }
        return Optional.empty();
    }

    /** 执行命中的 Skill：异常统一转为友好错误文本，不中断消息流程 */
    public String execute(Skill skill, String userId, String text) {
        try {
            long start = System.currentTimeMillis();
            String result = skill.execute(userId, text);
            log.info("Skill {} 执行完成，耗时 {} ms", skill.name(), System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            log.error("Skill {} 执行失败", skill.name(), e);
            return "Skill 执行出错了：" + e.getMessage();
        }
    }

    private String mask(String text) {
        return text.length() <= 20 ? text : text.substring(0, 20) + "...";
    }
}
