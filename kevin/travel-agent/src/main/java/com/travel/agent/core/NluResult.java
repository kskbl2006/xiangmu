package com.travel.agent.core;

import java.util.List;
import java.util.Map;

/** NLU 解析结果：结构化需求 + 需要追问的问题。 */
public record NluResult(Map<String, Object> request, List<String> questions) {
}
