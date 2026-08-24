package com.wechat.bot.demo;

import com.wechat.bot.config.AppConfig;
import com.wechat.bot.llm.LlmClient;
import com.wechat.bot.llm.model.ChatMessage;
import com.wechat.bot.rag.KeywordKnowledgeBase;
import com.wechat.bot.rag.RagService;
import com.wechat.bot.skill.FortuneSkill;
import com.wechat.bot.skill.Skill;
import com.wechat.bot.skill.SkillRegistry;

import java.util.List;
import java.util.Optional;

/**
 * 完整消息路由验证 Demo（不依赖微信登录，控制台模拟）。
 * <p>验证三级路由全流程：
 * <pre>
 * 用户消息
 *   → 命中 Skill 关键词？ → Skill 执行 → 回复
 *   → 命中 RAG 关键词？   → 增强 Prompt → LLM 回复
 *   → 都没命中？          → 直接 LLM 闲聊回复
 * </pre>
 */
public class MessageRouteDemo {

    private static final String BASE_SYSTEM_PROMPT = "你是微信群里的智能小助手，回复简洁友好、口语化。";

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.load();
        LlmClient llmClient = new LlmClient(config);
        SkillRegistry skillRegistry = new SkillRegistry().register(new FortuneSkill());
        RagService ragService = new RagService(
                new KeywordKnowledgeBase().load(config.ragKnowledgeBase()),
                config.ragEnabled(), config.ragTopK());

        String userId = "demo-user";

        String[] messages = {
                "今天运势怎么样",              // 预期：Skill 层（fortune）
                "公司WiFi密码是多少",           // 预期：RAG 层（faq-004）
                "帮我查一下北京天气",           // 预期：LLM 兜底（Function Calling 走天气工具）
                "你好呀，给我讲个笑话"          // 预期：LLM 兜底（闲聊）
        };

        for (String message : messages) {
            System.out.println("============================================================");
            System.out.println("👤 用户：" + message);
            route(userId, message, skillRegistry, ragService, llmClient);
            System.out.println();
        }
        System.out.println("✅ 三级消息路由全流程验证完成");
    }

    /**
     * 与 BotMessageHandler.dispatchIntent 相同的三级路由逻辑（控制台版）。
     */
    private static void route(String userId, String text,
                              SkillRegistry skillRegistry,
                              RagService ragService,
                              LlmClient llmClient) throws Exception {
        // ---- 第一级：Skill 关键词 ----
        Optional<Skill> skill = skillRegistry.match(text);
        if (skill.isPresent()) {
            System.out.println("🔀 [路由] 命中 Skill 关键词 → 执行 " + skill.get().name());
            long start = System.currentTimeMillis();
            String reply = skill.get().execute(userId, text);
            System.out.println("🤖 回复（Skill 本地执行 " + (System.currentTimeMillis() - start)
                    + " ms，零 LLM 调用）：\n" + indent(reply));
            return;
        }

        // ---- 第二级：RAG 关键词 ----
        boolean ragHit = ragService.hit(text);
        if (ragHit) {
            System.out.println("🔀 [路由] 命中 RAG 关键词 → 检索知识库 → 增强 Prompt → LLM 回复");
            String augmented = ragService.augmentSystemPrompt(BASE_SYSTEM_PROMPT, text);
            String reply = llmClient.chat(List.of(
                    ChatMessage.system(augmented),
                    ChatMessage.user(text)));
            System.out.println("🤖 回复（RAG 增强）：\n" + indent(reply));
            return;
        }

        // ---- 第三级：LLM 兜底闲聊 ----
        System.out.println("🔀 [路由] Skill/RAG 均未命中 → LLM 兜底闲聊");
        String reply = llmClient.chat(List.of(
                ChatMessage.system(BASE_SYSTEM_PROMPT),
                ChatMessage.user(text)));
        System.out.println("🤖 回复（LLM 直接生成）：\n" + indent(reply));
    }

    private static String indent(String text) {
        return "   " + text.replace("\n", "\n   ");
    }
}
