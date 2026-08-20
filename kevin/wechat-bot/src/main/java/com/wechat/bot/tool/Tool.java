package com.wechat.bot.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 自定义工具接口（Function Calling / Tool Use）。
 * <p>实现类需要提供：
 * <ul>
 *   <li>工具名与描述：供 LLM 理解何时调用该工具</li>
 *   <li>参数 JSON Schema：描述函数签名的参数字段（type/properties/required 等）</li>
 *   <li>执行逻辑：入参为模型生成的 JSON 参数，返回值为文本形式执行结果</li>
 * </ul>
 */
public interface Tool {

    /** 工具唯一名称，与 LLM tool_calls.function.name 对应 */
    String name();

    /** 工具功能描述，LLM 依据该描述决定是否调用 */
    String description();

    /**
     * 参数的 JSON Schema 定义（OpenAI tools.function.parameters 格式）。
     */
    Map<String, Object> parametersSchema();

    /**
     * 执行工具。
     *
     * @param arguments 模型生成的 JSON 参数
     * @return 工具执行结果（文本，将回传给 LLM 生成最终回复）
     */
    String execute(JsonNode arguments) throws Exception;
}
