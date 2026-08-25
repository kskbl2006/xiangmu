package com.github.wechat.ilink.bot.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** A locally executed function that can be exposed to the LLM. */
public interface BotTool {
  String name();

  String description();

  ObjectNode parametersSchema();

  String execute(JsonNode arguments) throws Exception;
}
