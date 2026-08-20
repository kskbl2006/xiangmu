package com.wechat.bot.llm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 模型发起的一次工具调用请求（Function Calling）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ToolCall {

    private String id;

    private String type;

    private FunctionCall function;

    /**
     * 工具调用函数描述：name + JSON 字符串形式的 arguments。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FunctionCall {
        private String name;
        private String arguments;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getArguments() {
            return arguments;
        }

        public void setArguments(String arguments) {
            this.arguments = arguments;
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public FunctionCall getFunction() {
        return function;
    }

    public void setFunction(FunctionCall function) {
        this.function = function;
    }

    /** 工具名 */
    public String name() {
        return function == null ? null : function.getName();
    }

    /** 工具参数原始 JSON 字符串 */
    public String arguments() {
        return function == null ? null : function.getArguments();
    }
}
