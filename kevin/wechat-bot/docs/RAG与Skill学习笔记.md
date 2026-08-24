# RAG 与 Skill 学习笔记（8月24日任务）

> 作者：kevin　|　项目：wechat-bot（微信智能机器人，JDK21 + 智谱 GLM）
> 本文配套代码：`skill/`、`rag/` 包 + `RagCompareDemo`、`MessageRouteDemo`，全部实测通过。

---

## 一、基础概念

### 1.1 RAG（Retrieval-Augmented Generation，检索增强生成）

**是什么**：让 LLM 回答问题前，先从外部知识库中检索相关资料，把资料拼进 Prompt 再生成回答。本质是「开卷考试」——模型不用死记硬背，照着参考资料答题。

**为什么需要**：LLM 有三个天然缺陷，RAG 逐一补上：

| LLM 缺陷 | 表现 | RAG 的解法 |
|---|---|---|
| 知识过时 | 训练截止后的信息一概不知 | 知识库随时可更新，改 JSON 即生效 |
| 私有知识缺失 | 公司制度、内部 FAQ 不在训练集 | 私有资料放知识库即可被引用 |
| 幻觉 | 不知道也硬编一个答案 | Prompt 里明确「依据资料回答，资料没有就说不知道」 |

**实现逻辑（三步）**：

```
用户问题 ──①检索 Retrieval──> 知识库中召回 Top-K 相关文档
         ──②增强 Augmentation> 把文档正文拼进系统 Prompt
         ──③生成 Generation──> LLM 基于增强 Prompt 回答
```

**生产级 vs 本次极简版**：

| 环节 | 生产级 | 本项目极简版 |
|---|---|---|
| 文档切分 | 按语义/固定 token 分块 | 每条 FAQ 就是一个文档，不切分 |
| 向量化 | Embedding 模型编码 | 无 |
| 检索 | 向量余弦相似度（语义级） | **关键词计数**：用户问题包含文档关键词的个数即得分 |
| 存储 | 向量数据库（Milvus/Faiss…） | classpath 下的 `knowledge-base.json` |

> 关键词检索的局限：问「无线网连不上」检索不到关键词为「wifi」的文档（同义词不命中）。
> 这正是要用 Embedding 语义检索的原因——语义相近即可召回，不依赖字面相同。

### 1.2 Skill（自定义技能）

**是什么**：由**本地关键词规则直接路由**、命中即执行的轻量能力单元。实现见 `skill/Skill.java`——只需提供「触发关键词 + 执行逻辑」。

**与现有 Function Calling 工具（Tool）的区别**——这是理解为什么要引入 Skill 的关键：

| 维度 | Tool（Function Calling） | Skill |
|---|---|---|
| 谁决策 | **LLM 决定**是否调用（模型看描述自行判断） | **本地规则**决定（关键词匹配） |
| 执行路径 | LLM→返回tool_calls→本地执行→结果回传→LLM 再生成 | 关键词命中→本地执行→直接回复 |
| LLM 调用次数 | ≥ 2 次（判断一次 + 总结一次） | **0 次** |
| 延迟 | 秒级（两次网络往返） | **毫秒级**（实测 8 ms） |
| Token 成本 | 有 | **零** |
| 结果确定性 | 模型可能不调用/参数出错 | 100% 确定 |
| 适用场景 | 需要语义理解、参数提取的任务 | 规则明确、无需推理、要求秒回的任务 |

## 二、业务意义：为什么要加 Skill 和 RAG

### 2.1 现有的工具类满足条件吗？——不满足，存在三个空白

改造前的架构是：`意图识别 → Function Calling 循环（天气/时间工具）→ 纯 LLM 闲聊`。实测发现：

1. **闲聊也背着工具跑**：每条普通消息都要把工具定义发给 LLM、至少两次 LLM 往返，
   慢（2~4 秒）且浪费 token——但工具只覆盖天气/时间，覆盖面极窄。
2. **内部知识完全靠模型编**：问「公司 WiFi 密码」，glm-4-flash 直接编造「12345678」
   （实测截图见下文对比）。工具类解决的是「实时数据」，解决不了「私有知识」。
3. **高频固定问答没有任何捷径**：FAQ 类问题（密码、报销、年假）答案固定，
   却每次都要完整走一遍 LLM，既慢又可能每次答案不一样。

### 2.2 加入 Skill 与 RAG 之后，变得更智能了吗？——是，且更快更省

新的三级消息路由（`BotMessageHandler.dispatchIntent`）：

```
用户消息
 → 命中 Skill 关键词？ → Skill 本地执行 → 回复（0 次 LLM，8 ms）
 → 命中 RAG 关键词？  → 检索知识库 → 增强 Prompt → LLM 回复（有依据）
 → 都没命中？        → LLM 兜底闲聊（保留 Function Calling 能力）
```

「智能」的三个提升点：

1. **准确**（RAG 的贡献）：回答有出处。同样问 WiFi 密码，关闭 RAG 编造「12345678」，
   开启后准确答出「Kf2026#work，连不上找 IT 分机 8888」——这正是知识库里的内容。
2. **快且稳**（Skill 的贡献）：运势类高频娱乐消息从「2 次 LLM 调用、秒级」
   降到「0 次调用、8 ms」，且结果确定（同人同日固定，防刷）。
3. **分层可控**：确定性请求走规则、知识型请求走 RAG、开放请求才走 LLM——
   每条消息走最便宜的够用路径，LLM 调用量与 token 成本显著下降。

> 结论：**Tool 没有被取代**——天气/时间等需要语义理解和实时数据的任务仍走 Function Calling；
> Skill 与 RAG 补上的是「高频固定请求的快路径」和「私有知识的准确回答」两个空白。

## 三、本项目的落地实现

### 3.1 新增代码结构

```
src/main/java/com/wechat/bot/
├── skill/
│   ├── Skill.java              # Skill 接口：triggerKeywords() + execute()
│   ├── SkillRegistry.java      # 注册中心：关键词匹配路由 + 安全执行
│   └── FortuneSkill.java       # ★ 自定义 Skill：今日运势（同人同日结果固定）
├── rag/
│   ├── KnowledgeDoc.java       # 知识文档模型（id/keywords/question/answer）
│   ├── KeywordKnowledgeBase.java # 关键词检索：得分=命中关键词数，Top-K 降序
│   └── RagService.java         # 编排层：hit() 路由判断 + augmentSystemPrompt() 增强
└── demo/
    ├── RagCompareDemo.java     # RAG 开/关对比测试
    └── MessageRouteDemo.java   # 三级路由全流程验证
resources/knowledge-base.json   # 知识库：7 条公司内部 FAQ
```

配置项（application.properties）：`rag.enabled`（总开关）、`rag.knowledge-base`、`rag.top-k`。

### 3.2 自定义 Skill：FortuneSkill（今日运势）

- 触发词：`运势 / 占卜 / 抽签 / 锦鲤`
- 核心设计：`new Random((userId + "|" + today).hashCode())`——随机种子绑定「用户+日期」，
  同一人同一天反复问结果不变（防止不停抽签刷「大吉」），不同用户/日期各自独立。

### 3.3 RAG 关键词检索实现（10 行核心逻辑）

```java
int score = 0;
for (String keyword : doc.keywords())
    if (question.contains(keyword)) score++;   // 命中一个关键词得 1 分
if (score > 0) scored.add(...);                // 得分>0 视为相关
// 按得分降序取 Top-K
```

增强 Prompt 的格式（RagService.augmentSystemPrompt）：

```
{基础系统提示词}
以下是知识库中检索到的相关资料（请优先依据资料回答，资料未涉及的内容如实说明，不要编造）：
【资料1】公司无线网络：办公网 SSID 为 Corp-Office，密码 Kf2026#work……
```

## 四、实测结果（2026-08-24，glm-4-flash）

### 4.1 RAG 开/关对比测试（RagCompareDemo）

| 问题 | RAG 关闭（裸 LLM） | RAG 开启（检索增强） |
|---|---|---|
| 公司WiFi密码是多少？ | ❌ 编造：「12345678」 | ✅ 「Kf2026#work，连不上联系 IT 分机 8888」 |
| 年假有几天？怎么申请？ | ⚠️ 泛泛：「通常 5 到 15 天不等，看公司规定」 | ✅ 「满1年5天、满3年10天、满5年15天；OA 提交、提前 3 个工作日」 |
| 工资几号发？ | ⚠️ 猜测：「比如 15 号或者月底」 | ✅ 「每月 10 日发上月工资，遇节假日提前」 |

结论：RAG 把「内部知识问答」从**不可用（编造）**变成**可用且准确**，代价仅是检索耗时 <1 ms + Prompt 增加 ~180 字符。

### 4.2 三级路由全流程验证（MessageRouteDemo）

| 用户消息 | 路由分支 | 实测表现 |
|---|---|---|
| 今天运势怎么样 | ① Skill（fortune） | 本地执行 8 ms，0 次 LLM 调用 |
| 公司WiFi密码是多少 | ② RAG（faq-004） | 检索命中 1 条 → 增强 → 准确回答 |
| 帮我查一下北京天气 | ③ LLM 兜底 | 未命中知识库（0/7），走 LLM（真实 Bot 中由 Function Calling 调天气工具） |
| 你好呀，给我讲个笑话 | ③ LLM 兜底 | 正常闲聊讲笑话 |

## 五、遗留与展望

1. **关键词→向量检索**：接入 Embedding 模型（如 embedding-2），解决同义词召回问题（「无线网」↔「wifi」）。
2. **Skill 生态**：现有一个 FortuneSkill 示范了写法，后续可加掷骰子、待办提醒等；Skill 也可升级为「关键词命中后交给 LLM 提参再执行」的混合模式。
3. **知识库运营**：目前改 JSON 需重启，可加管理命令热更新；文档多后需切分与去重。
