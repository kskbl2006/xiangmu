package com.travel.agent.tools;

import java.util.Map;

/**
 * 工具统一接口：所有工具实现 run(params) → 结构化结果（Map），
 * 由 Agent 在执行循环中统一调度（注册 / 缓存 / 重试见 ToolRegistry）。
 */
public interface Tool {

    /** 工具名（与 DAG 子任务名一致，如 weather / poi / budget）。 */
    String name();

    Map<String, Object> run(Map<String, Object> params) throws Exception;
}
