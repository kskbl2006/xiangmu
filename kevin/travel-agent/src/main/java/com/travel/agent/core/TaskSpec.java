package com.travel.agent.core;

import java.util.List;

/** DAG 子任务定义：名称 / 依赖 / 描述。 */
public record TaskSpec(String name, List<String> deps, String desc) {
}
