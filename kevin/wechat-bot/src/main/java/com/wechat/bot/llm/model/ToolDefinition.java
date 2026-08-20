package com.wechat.bot.llm.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 提交给 LLM 的工具定义（JSON Schema 描述函数签名）。
 * OpenAI 兼容格式：{"type":"function","function":{"name","description","parameters"}}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolDefinition {

    private final String type = "function";

    private FunctionSpec function;

    public static ToolDefinition of(String name, String description, Map<String, Object> parameters) {
        ToolDefinition d = new ToolDefinition();
        d.function = new FunctionSpec();
        d.function.setName(name);
        d.function.setDescription(description);
        d.function.setParameters(parameters);
        return d;
    }

    public String getType() {
        return type;
    }

    public FunctionSpec getFunction() {
        return function;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FunctionSpec {
        private String name;
        private String description;

        /** JSON Schema 格式的参数定义 */
        private Map<String, Object> parameters;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public Map<String, Object> getParameters() {
            return parameters;
        }

        public void setParameters(Map<String, Object> parameters) {
            this.parameters = parameters;
        }
    }
}
