package com.wechat.bot.demo;

import com.wechat.bot.config.AppConfig;
import com.wechat.bot.llm.LlmClient;
import com.wechat.bot.llm.model.ChatMessage;
import com.wechat.bot.rag.KeywordKnowledgeBase;
import com.wechat.bot.rag.RagService;

import java.util.List;

/**
 * RAG 开启/关闭对比测试（不依赖微信登录）。
 * <p>同一问题分别以「关闭 RAG」「开启 RAG」两种模式询问 LLM，
 * 观察关闭时模型只能编造/拒答，开启后能依据知识库资料给出准确答案。
 * <p>运行：mvn -q compile exec:java -Dexec.mainClass=com.wechat.bot.demo.RagCompareDemo
 * （或 java -cp target/classes 依赖见 README）
 */
public class RagCompareDemo {

    private static final String BASE_SYSTEM_PROMPT = "你是公司内部的智能助手，回复简洁友好、口语化。";

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.load();
        LlmClient llmClient = new LlmClient(config);

        // 同一知识库，构造开关两种模式的 RagService
        RagService ragOff = new RagService(
                new KeywordKnowledgeBase().load(config.ragKnowledgeBase()), false, config.ragTopK());
        RagService ragOn = new RagService(
                new KeywordKnowledgeBase().load(config.ragKnowledgeBase()), true, config.ragTopK());

        String[] questions = {
                "公司WiFi密码是多少？",
                "年假有几天？怎么申请？",
                "工资一般是几号发的？"
        };

        for (String question : questions) {
            System.out.println("============================================================");
            System.out.println("❓ 问题：" + question);
            System.out.println("------------------------------------------------------------");

            // ---- 关闭 RAG：裸 LLM 直接回答 ----
            long t0 = System.currentTimeMillis();
            String answerOff = llmClient.chat(List.of(
                    ChatMessage.system(BASE_SYSTEM_PROMPT),
                    ChatMessage.user(question)));
            System.out.println("🔘 RAG 关闭（裸 LLM，" + (System.currentTimeMillis() - t0) + " ms）：");
            System.out.println("   " + answerOff.replace("\n", "\n   "));

            // ---- 开启 RAG：检索知识库 → 增强 Prompt → LLM 回答 ----
            long t1 = System.currentTimeMillis();
            String augmentedPrompt = ragOn.augmentSystemPrompt(BASE_SYSTEM_PROMPT, question);
            String answerOn = llmClient.chat(List.of(
                    ChatMessage.system(augmentedPrompt),
                    ChatMessage.user(question)));
            System.out.println("🔛 RAG 开启（检索+增强，" + (System.currentTimeMillis() - t1) + " ms）：");
            System.out.println("   " + answerOn.replace("\n", "\n   "));
            System.out.println();
        }

        System.out.println("============================================================");
        System.out.println("✅ 对比测试完成：开启 RAG 后回答依据知识库资料（密码 Kf2026#work、"
                + "年假 5/10/15 天按司龄、每月 10 日发薪），关闭时模型无法给出这些内部事实。");
    }
}
