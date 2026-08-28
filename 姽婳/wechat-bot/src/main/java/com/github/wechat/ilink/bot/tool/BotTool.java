package com.github.wechat.ilink.bot.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 可供大模型调用的本地工具。 */
public interface BotTool {
  String name();

  String description();

  ObjectNode parametersSchema();

  String execute(JsonNode arguments) throws Exception;
}
