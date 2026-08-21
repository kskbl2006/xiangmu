# 微信智能机器人（wechat-bot）

基于 `wechat-ilink-sdk` 的微信智能机器人，接入智谱 GLM 大模型，实现文本/图片/语音智能回复、意图识别、天气查询与 Function Calling 工具调用。

## 功能特性

- 微信扫码登录（终端二维码渲染），长轮询收发消息
- LLM 智能对话（智谱 GLM-4-Flash，免费模型，多轮上下文）
- 图片理解（GLM-4V-Flash 多模态）与文生图（CogView-3-Flash）
- 语音识别（微信服务端转写）与语音回复（TTS，可配置）
- 意图识别（规则 + LLM 两级策略）
- Function Calling 工具调用（自定义工具 + JSON Schema 签名描述）
- 多步工具链式调用演示（`#chain 城市`）
- 多级别日志（slf4j + logback：DEBUG/INFO/WARN/ERROR，控制台 + 滚动文件）

## 环境要求

- JDK 21+
- Maven 3.8+
- 智谱开放平台 API Key（https://open.bigmodel.cn ，GLM-4-Flash 免费）

## 快速开始

### 1. 安装微信 SDK 到本地仓库

```bash
cd wechat-ilink-sdk-java-main
mvn clean install -DskipTests
```

### 2. 配置 API Key

编辑 `src/main/resources/application.properties`：

```properties
llm.api-key=你的智谱APIKey
```

### 3. 打包运行

```bash
cd wechat-bot
mvn clean package
java -jar target/wechat-bot.jar
```

启动后终端显示二维码，用微信扫码登录即可。

## 机器人指令

| 指令/消息 | 功能 |
|---|---|
| 任意文本 | LLM 智能对话（自动调用工具，多工具自动并行） |
| "北京天气怎么样" | Function Calling 调用天气工具 |
| "现在几点了" | Function Calling 调用时间工具 |
| "北京和上海哪个热" | 模型一次返回多个 tool_calls，并行执行后汇总 |
| "画一只猫" | 文生图回复 |
| 发送图片 | 多模态图片理解 |
| 发送语音 | 语音识别后智能回复 |
| `#chain 北京` | 串行链式调用演示（城市→坐标→天气→建议） |
| `#multi 北京 上海 广州` | 多工具并行协作演示（多城市天气+时间同时查，LLM 汇总比较） |

## 项目结构

```
src/main/java/com/wechat/bot/
├── BotApplication.java        # 主入口：登录、消息循环（JDK21 虚拟线程）
├── config/AppConfig.java      # 配置加载
├── handler/BotMessageHandler  # 消息处理核心：意图分发 + Function Calling 循环
├── intent/IntentRecognizer    # 意图识别（规则 + LLM）
├── llm/
│   ├── LlmClient              # LLM 客户端（对话/工具调用/图片理解/文生图/TTS）
│   └── model/                 # ChatMessage、ToolCall、ToolDefinition
├── tool/
│   ├── Tool                   # 工具接口（JSON Schema 签名）
│   ├── ToolRegistry           # 工具注册与调度
│   ├── WeatherTool            # 天气工具（Open-Meteo，内部两步链式）
│   └── DateTimeTool           # 日期时间工具
├── voice/VoiceService         # 语音识别与 TTS 回复
└── util/QrCodeUtil            # 终端二维码渲染
```

## Function Calling 工作流程

1. 用户消息 + 工具定义（JSON Schema）发送给 LLM
2. LLM 判断需要工具时返回 `tool_calls`（工具名 + JSON 参数）
3. 本地执行工具，结果以 `tool` 角色回传
4. LLM 基于工具结果继续推理（可再次调用工具，形成多步链式）
5. 循环直到 LLM 输出最终自然语言回复

## 日志说明

- 控制台：全级别输出
- `logs/wechat-bot.log`：INFO 及以上（按天 + 大小滚动）
- `logs/wechat-bot-error.log`：仅 ERROR（便于快速排障）
