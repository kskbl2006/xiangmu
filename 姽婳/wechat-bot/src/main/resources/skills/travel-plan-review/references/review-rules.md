# 审校规则

| 问题代码 | 严重级别 | 条件 | 修正动作 |
|---|---|---|---|
| DAY_COUNT_MISMATCH | ERROR | 实际天数与用户要求不同 | 补齐或裁剪天数 |
| EMPTY_DAY | ERROR | 某日没有活动 | 从未使用候选中补充活动 |
| DUPLICATE_ACTIVITY | ERROR | 同一景点重复出现 | 保留首次，后续替换或删除 |
| BUDGET_EXCEEDED | ERROR | 预算分类合计超过总预算 | 按比例压缩非机动分类 |
| DAY_OVERLOADED | WARN | 单日超过 3 个活动 | 删除低优先级活动 |
| RAIN_OUTDOOR_RISK | WARN | 当前雨天且单日户外活动过多 | 优先替换为室内活动 |
| LOW_BUFFER | WARN | 机动金低于总预算 10% | 调整预算分配 |
| DYNAMIC_DATA_UNVERIFIED | WARN | 动态票价、时间、预约未提示核验 | 添加官方渠道核验提示 |
| LIMITED_KNOWLEDGE_SCOPE | WARN | 资料超出上海、杭州、苏州 | 停止引用本地 RAG，说明范围 |

ERROR 触发重规划。WARN 进入最终风险提示，可触发局部优化。审校循环上限为两轮。
