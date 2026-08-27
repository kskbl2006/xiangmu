# 智能旅行规划助手 Agent（Java 版）

自主规划型长任务 Agent：用户只输入一句话（如 *"北京出发去三亚5天，预算5000元，2大1小"*），
Agent 自主拆解子任务、调用多种工具（天气 API / RAG 知识库 / 预算测算 / 行程编排 / 文档生成 Skill），
自动校验冲突并修复，最终产出一份结构化、可直接使用的《旅行方案》文档。

技术栈：Java 21 + Maven + Jackson；HTTP 与 Web 控制台均用 JDK 内置能力（`java.net.http.HttpClient`、
`com.sun.net.httpserver.HttpServer`、`java.util.zip`），离线可完整跑通闭环（真实 API + Mock 双兜底）。

## 快速开始

```bash
# 构建（首次）
mvn -DskipTests package
# 一句话 → 完整方案
java -jar target/travel-agent.jar "北京出发去三亚5天，预算5000元，2大1小"
# 检查完成情况
java -jar target/travel-agent.jar --status                 # 所有运行总览（步骤数/方案是否生成）
java -jar target/travel-agent.jar --status run_20260826_103000   # 某次运行详情
# Web 控制台（实时进度/方案预览/文档下载，127.0.0.1:8765）
java -jar target/travel-agent.jar web
# 性能与 token 基准
java -jar target/travel-agent.jar bench
# 验收演示（逐步演示 5 大特性，--auto 自动连跑）
java -jar target/travel-agent.jar demo --auto
start.bat                                                # Windows 一键演示
```

产出：`workspace/runs/<run_id>/旅行方案_三亚5日_<run_id>.md` + 同名 `.docx`（纯 JDK 实现 OOXML 导出，Word/WPS 可直接打开）
（含需求概览 / 逐日天气 / 每日行程表（含高德导航链接）/ 预算明细 / 自检与自动修复记录 / 质量自评 / 运行统计）

## 四个进阶特性及演示方法

### 1. 多轮任务断点续跑
每个子任务完成后 checkpoint 原子落盘（`workspace/runs/<run_id>/checkpoint.json`），
任何一步中断后可从断点继续，已完成步骤不重复执行：

```bash
java -jar target/travel-agent.jar --step 3 "北京出发去三亚5天，预算5000元，2大1小"   # 只跑前3步：nlu→[weather∥poi] 后暂停
java -jar target/travel-agent.jar --resume run_20260826_103000                       # 从断点续跑补完剩余 4 步
```

### 2. 定时任务
定时重跑规划：出行前每天早上自动刷新天气、重出方案（清天气缓存、保留景点缓存）：

```bash
java -jar target/travel-agent.jar --schedule "08:00" "北京出发去三亚5天，预算5000元，2大1小"    # 每天早上8点
java -jar target/travel-agent.jar --schedule "*/30 * * * *" "..."                              # 每30分钟
java -jar target/travel-agent.jar --schedule "10m" "..." --max-runs 2                          # 演示：跑2轮即停
```

### 3. 性能优化（加速响应）
- **DAG 并行**：天气与景点检索无依赖，线程池并行执行（日志可见 `并行执行无依赖子任务`）；
- **磁盘缓存**：天气 TTL 3h、景点 TTL 7 天，命中缓存 0 网络 0 token（第二次运行明显变快）；
- **超时控制**：网络工具 8s 超时，失败自动重试 2 次，仍失败降级 Mock，流程不中断；
- **故障注入演示**：`java -jar target/travel-agent.jar --fail-at weather "..."`（首次失败→自动重试成功）。

### 4. 减少 token 消耗
- **规则优先、LLM 兜底**：NLU 解析用正则（0 token），仅意图存疑时才调一次 LLM；
- **结构化压缩**：工具输出只保留下游所需字段、描述截断（`MAX_POI_IN_ITINERARY_STEP`）；
- **缓存即零 token**：命中缓存不再进入任何 LLM/网络调用；
- **全链路记账**：TokenMeter 统计 LLM 调用数 / token 量 / 缓存节省，运行结束与报告文档内均输出。

## 架构（与小组架构图逐层对应）

| 架构图分层 | 代码模块 | 说明 |
|---|---|---|
| 用户输入层 | `Main` | CLI 一句话输入、--resume/--schedule 等演示开关 |
| 需求解析层 NLU | `core/Nlu` | 意图识别 + 实体抽取（目的地/日期/天数/预算/人数）+ 参数补全 |
| 任务规划层 | `core/Planner` + `core/TravelAgent` | 子任务 DAG 拆解；串/并行调度、超时、重试、Mock 兜底、断点续跑 |
| 工具层 | `tools/`（weather/poi/budget/itinerary） | 统一 Tool Registry + 磁盘缓存；天气走 Open-Meteo，景点走 RAG |
| 输出层 | `tools/ValidateTool` + `tools/ReportTool` | 冲突检查（预算超支/雨天户外）+ 自动修复；方案文档渲染 Skill |
| 工程支撑 | `core/RealLlm`+`core/MockLlm` + `core/Scheduler` + `rag/Kb` | LLM 引擎（真实 API+Mock）、TokenMeter、定时调度、BM25-lite 检索 |
| 数据源 | `knowledge/*.md` + Mock | 10 城旅行知识库（三亚/上海/北京/成都/杭州/西安/重庆/广州/南京/苏州）；未覆盖城市自动降级通用推荐 |

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
java -jar target/travel-agent.jar "北京出发去三亚5天，预算5000元，2大1小"
```

## 测试

```bash
mvn test
```

覆盖：NLU 解析、RAG 相关性、预算测算、docx 导出、端到端闭环（预算紧张触发自动修复）、断点续跑（已完成步骤不重复执行）。

## 目录结构

```
travel-agent/
├── pom.xml                       # Maven（Java 21 + Jackson + JUnit5 + shade fat-jar）
├── src/main/java/com/travel/agent/
│   ├── Main.java                 # CLI 入口
│   ├── Bench.java / Demo.java   # 性能基准 / 验收演示
│   ├── config/Config.java        # 路径/缓存TTL/LLM/执行器配置
│   ├── core/
│   │   ├── TravelAgent.java      # 调度执行器（并行/重试/断点续跑）
│   │   ├── Nlu.java              # 需求解析（正则优先，LLM 兜底）
│   │   ├── Planner.java          # 子任务 DAG
│   │   ├── Checkpoint.java       # 断点落盘（原子写）
│   │   ├── Scheduler.java        # 定时任务
│   │   ├── RealLlm/MockLlm/Llm   # LLM 引擎 + TokenMeter 记账
│   ├── tools/
│   │   ├── ToolRegistry.java     # Tool Registry + 磁盘缓存
│   │   ├── WeatherTool.java      # 天气（Open-Meteo → Mock）
│   │   ├── PoiTool.java          # 景点/美食/酒店（RAG 排序）
│   │   ├── BudgetTool.java       # 预算测算
│   │   ├── ItineraryTool.java    # 逐日行程编排
│   │   ├── ValidateTool.java     # 冲突校验与自动修复
│   │   ├── ReportTool.java       # 方案文档生成 Skill
│   │   └── DocxExport.java       # Markdown → docx（纯 JDK OOXML）
│   ├── rag/Kb.java               # BM25-lite 检索
│   └── web/WebConsole.java       # Web 控制台（JDK HttpServer）
├── knowledge/                    # 10 城旅行知识库
├── workspace/                    # runs/<id>/（checkpoint+step+方案）与 cache/（运行时自动生成）
└── src/test/.../SmokeTest.java
```

## 扩展点（后续可做）

- 联网搜索真实游记/票务数据源（当前为本地知识库 + Mock 兜底）
- 车票/酒店比价与下单链接
- 多目的地路线优化（TSP 近似）
