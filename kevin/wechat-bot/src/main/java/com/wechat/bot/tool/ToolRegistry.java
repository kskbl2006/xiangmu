package com.wechat.bot.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.wechat.bot.llm.model.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册中心：统一管理所有自定义工具，
 * 提供「LLM 工具定义列表」导出与「按名执行」调度能力。
 */
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry register(Tool tool) {
        tools.put(tool.name(), tool);
        log.info("注册工具：{} - {}", tool.name(), tool.description());
        return this;
    }

    /** 是否存在指定名称的工具 */
    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    /**
     * 导出为 OpenAI 兼容的 tools 定义列表（含 JSON Schema 参数签名）。
     */
    public List<ToolDefinition> toToolDefinitions() {
        List<ToolDefinition> list = new ArrayList<>();
        for (Tool tool : tools.values()) {
            list.add(ToolDefinition.of(tool.name(), tool.description(), tool.parametersSchema()));
        }
        return list;
    }

    /**
     * 按名称执行工具，异常统一转为错误结果文本（避免中断对话流程）。
     */
    public String execute(String name, JsonNode arguments) {
        Tool tool = tools.get(name);
        if (tool == null) {
            log.warn("未注册的工具被调用：{}", name);
            return "错误：未知的工具 " + name;
        }
        try {
            long start = System.currentTimeMillis();
            String result = tool.execute(arguments);
            log.info("工具 {} 执行完成，耗时 {} ms，结果长度 {}",
                    name, System.currentTimeMillis() - start,
                    result == null ? 0 : result.length());
            return result;
        } catch (Exception e) {
            log.error("工具 {} 执行失败", name, e);
            return "工具执行失败：" + e.getMessage();
        }
    }
}
