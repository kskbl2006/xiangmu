# 智能旅行规划助手 Agent

自主规划型长任务 Agent：用户只输入一句话（如 *"北京出发去三亚5天，预算5000元，2大1小"*），
Agent 自主拆解子任务、调用多种工具（天气 API / RAG 知识库 / 预算测算 / 行程编排 / 文档生成 Skill），
自动校验冲突并修复，最终产出一份结构化、可直接使用的《旅行方案》文档。

零第三方依赖（纯 Python 标准库），离线可完整跑通闭环（真实 API + Mock 双兜底）。

## 快速开始

```bash
python main.py "北京出发去三亚5天，预算5000元，2大1小"   # 一句话 → 完整方案
python main.py --status                                 # 检查完成情况：所有运行总览（步骤数/方案是否生成）
python main.py --status <run_id>                        # 某次运行详情：逐步✔/…、方案路径、自检修复与风险
python main.py --list-runs                              # 查看历史运行
```

产出：`workspace/runs/<run_id>/旅行方案_三亚5日_<run_id>.md`
（含需求概览 / 逐日天气 / 每日行程表 / 预算明细 / 自检与自动修复记录 / 运行统计）

## 四个进阶特性（26-27 日任务）及演示方法

### 1. 多轮任务断点续跑
每个子任务完成后 checkpoint 原子落盘（`workspace/runs/<run_id>/checkpoint.json`），
任何一步中断后可从断点继续，已完成步骤不重复执行：

```bash
python main.py --step 3 "北京出发去三亚5天，预算5000元，2大1小"   # 只跑前3步：nlu→[weather∥poi] 后暂停
python main.py --resume <run_id>                                   # 从断点续跑补完剩余 4 步
```

### 2. 定时任务
定时重跑规划：出行前每天早上自动刷新天气、重出方案（清天气缓存、保留景点缓存）：

```bash
python main.py --schedule "08:00" "北京出发去三亚5天，预算5000元，2大1小"    # 每天早上8点
python main.py --schedule "*/30 * * * *" "..."                              # 每30分钟
python main.py --schedule "10m" "..." --max-runs 2                          # 演示：跑2轮即停
```

### 3. 性能优化（加速响应）
- **DAG 并行**：天气与景点检索无依赖，线程池并行执行（日志可见 `并行执行无依赖子任务`）；
- **磁盘缓存**：天气 TTL 3h、景点 TTL 7 天，命中缓存 0 网络 0 token（第二次运行明显变快）；
- **超时控制**：网络工具 8s 超时，失败自动重试 2 次，仍失败降级 Mock，流程不中断；
- **故障注入演示**：`python main.py --fail-at weather "..."`（首次失败→自动重试成功）。

### 4. 减少 token 消耗
- **规则优先、LLM 兜底**：NLU 解析用正则（0 token），仅意图存疑时才调一次 LLM；
- **结构化压缩**：工具输出只保留下游所需字段、描述截断（`MAX_POI_IN_ITINERARY_STEP`）；
- **缓存即零 token**：命中缓存不再进入任何 LLM/网络调用；
- **全链路记账**：TokenMeter 统计 LLM 调用数 / token 量 / 缓存节省，运行结束与报告文档内均输出。

## 架构（与小组架构图逐层对应）

| 架构图分层 | 代码模块 | 说明 |
|---|---|---|
| 用户输入层 | `main.py` | CLI 一句话输入、--resume/--schedule 等演示开关 |
| 需求解析层 NLU | `core/nlu.py` | 意图识别 + 实体抽取（目的地/日期/天数/预算/人数）+ 参数补全 |
| 任务规划层 | `core/planner.py` + `core/agent.py` | 子任务 DAG 拆解；串/并行调度、超时、重试、Mock 兜底、断点续跑 |
| 工具层 | `tools/`（weather/poi/budget/itinerary） | 统一 Tool Registry + 磁盘缓存；天气走 Open-Meteo，景点走 RAG |
| 输出层 | `tools/validate.py` + `tools/report.py` | 冲突检查（预算超支/雨天户外）+ 自动修复；方案文档渲染 Skill |
| 工程支撑 | `core/llm.py` + `core/scheduler.py` + `rag/kb.py` | LLM 引擎（真实 API+Mock）、TokenMeter、定时调度、BM25-lite 检索 |
| 数据源 | `knowledge/*.md` + Mock | 5 城旅行知识库；未覆盖城市自动降级通用推荐 |

## 子任务链路（7 步）

```
nlu（需求解析）
 → [weather ∥ poi]（并行：天气查询 / RAG 检索景点·美食·酒店）
 → budget（预算分配测算）
 → itinerary（天气感知的逐日行程编排）
 → validate（冲突校验与自动修复：雨天换室内 / 超支降档砍景点）
 → report（渲染《旅行方案》Markdown，附运行统计）
```

## 接入真实 LLM（可选）

默认使用 MockLLM 离线兜底。设置环境变量即切换真实模型（OpenAI 兼容接口）：

```bash
set LLM_BASE_URL=https://open.bigmodel.cn/api/paas/v4
set LLM_API_KEY=你的key
set LLM_MODEL=glm-4-flash
python main.py "北京出发去三亚5天，预算5000元，2大1小"
```

## 测试

```bash
python tests/test_smoke.py
```

覆盖：NLU 解析、RAG 相关性、预算测算、端到端闭环（预算紧张触发自动修复）、断点续跑（已完成步骤不重复执行）。

## 目录结构

```
travel-agent/
├── main.py               # CLI 入口
├── config.py             # 路径/缓存TTL/LLM/执行器配置
├── core/
│   ├── llm.py            # LLM 引擎 + TokenMeter 记账
│   ├── nlu.py            # 需求解析（正则优先，LLM 兜底）
│   ├── planner.py        # 子任务 DAG
│   ├── agent.py          # 调度执行器（并行/重试/断点续跑）
│   ├── checkpoint.py     # 断点落盘（原子写）
│   └── scheduler.py      # 定时任务
├── tools/
│   ├── base.py           # Tool Registry + 磁盘缓存
│   ├── weather.py        # 天气（Open-Meteo → Mock）
│   ├── poi.py            # 景点/美食/酒店（RAG 排序）
│   ├── budget.py         # 预算测算
│   ├── itinerary.py      # 逐日行程编排
│   ├── validate.py       # 冲突校验与自动修复
│   └── report.py         # 方案文档生成 Skill
├── rag/kb.py             # BM25-lite 检索
├── knowledge/            # 5 城旅行知识库
├── workspace/            # runs/<id>/（checkpoint+step+方案）与 cache/
└── tests/test_smoke.py
```

## 扩展点（后续可做）

- 接入 docx/PDF 导出、Web 前端进度可视化
- 联网搜索真实游记/票务数据源（当前为本地知识库 + Mock 兜底）
- 车票/酒店比价与下单链接
- 多目的地路线优化（TSP 近似）
