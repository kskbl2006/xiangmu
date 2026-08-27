package com.travel.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.agent.config.Config;
import com.travel.agent.util.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** 真实 LLM：OpenAI 兼容 /chat/completions 接口。 */
public final class RealLlm implements Llm {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(Config.LLM_TIMEOUT))
            .build();

    private final String url = Config.LLM_BASE_URL.replaceAll("/+$", "") + "/chat/completions";

    @Override
    public String complete(String prompt, double temperature) {
        try {
            Map<String, Object> body = Json.obj(
                    "model", Config.LLM_MODEL,
                    "messages", Json.arr(Json.obj("role", "user", "content", prompt)),
                    "temperature", temperature);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(Config.LLM_TIMEOUT))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + Config.LLM_API_KEY)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            Json.MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = CLIENT.send(req,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                throw new RuntimeException("LLM HTTP " + resp.statusCode());
            }
            JsonNode data = Json.MAPPER.readTree(resp.body());
            String text = data.path("choices").path(0).path("message").path("content").asText("");
            TokenMeter.INSTANCE.record(prompt, text);
            return text;
        } catch (Exception e) {
            throw new RuntimeException("LLM 调用失败: " + e.getMessage(), e);
        }
    }
}
