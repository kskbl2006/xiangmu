package com.wechat.bot;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.wechat.bot.config.AppConfig;
import com.wechat.bot.handler.BotMessageHandler;
import com.wechat.bot.intent.IntentRecognizer;
import com.wechat.bot.llm.LlmClient;
import com.wechat.bot.tool.DateTimeTool;
import com.wechat.bot.tool.ToolRegistry;
import com.wechat.bot.tool.WeatherTool;
import com.wechat.bot.util.QrCodeUtil;
import com.wechat.bot.voice.VoiceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 微信智能机器人主入口。
 * <p>启动流程：
 * <ol>
 *   <li>加载配置与日志系统（多级别日志自检）</li>
 *   <li>组装 LLM 客户端、意图识别、工具注册中心等组件</li>
 *   <li>发起微信扫码登录（终端渲染二维码）</li>
 *   <li>登录成功后进入长轮询消息循环（JDK21 虚拟线程驱动）</li>
 * </ol>
 */
public class BotApplication {

    private static final Logger log = LoggerFactory.getLogger(BotApplication.class);

    private static final AtomicBoolean running = new AtomicBoolean(true);

    public static void main(String[] args) throws Exception {
        // ---------- 1. 配置与日志初始化（多级别日志自检） ----------
        log.info("========== 微信智能机器人启动中 ==========");
        AppConfig config = AppConfig.load();
        logSelfTestLogs();

        if (!config.isLlmApiKeyValid()) {
            log.error("未配置 llm.api-key（或仍为占位符「你的API_KEY」）！"
                    + "请编辑 src/main/resources/application.properties 填入智谱 API Key 后重启");
            log.warn("LLM 相关功能（对话/意图识别/工具调用）将不可用，仅微信收发消息可用");
        }

        // ---------- 2. 组装业务组件 ----------
        LlmClient llmClient = new LlmClient(config);
        IntentRecognizer intentRecognizer = new IntentRecognizer(llmClient);
        WeatherTool weatherTool = new WeatherTool(config);
        ToolRegistry toolRegistry = new ToolRegistry()
                .register(weatherTool)
                .register(new DateTimeTool());
        VoiceService voiceService = new VoiceService(llmClient, config.voiceReplyEnabled());

        BotMessageHandler handler = new BotMessageHandler(
                config, llmClient, intentRecognizer, toolRegistry, weatherTool, voiceService);

        // ---------- 3. 创建微信客户端并注册监听 ----------
        CountDownLatch loginLatch = new CountDownLatch(1);

        ILinkClient wechatClient = ILinkClient.builder()
                .config(ILinkConfig.builder()
                        .connectTimeoutMs(35000)
                        .readTimeoutMs(35000)
                        .writeTimeoutMs(35000)
                        .heartbeatEnabled(true)
                        .heartbeatIntervalMs(30000)
                        .build())
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext context) {
                        log.info("微信登录成功，botId = {}", context.getBotId());
                        loginLatch.countDown();
                    }

                    @Override
                    public void onLoginFailure(Throwable throwable) {
                        log.error("微信登录失败", throwable);
                        loginLatch.countDown();
                    }
                })
                // 消息监听：SDK 长轮询拉到消息后回调（虚拟线程异步处理）
                .onMessage(messages -> {
                    Thread.ofVirtual().name("msg-handler").start(() -> {
                        try {
                            handler.handle(messages);
                        } catch (Exception e) {
                            log.error("消息处理异常", e);
                        }
                    });
                })
                .build();

        handler.bindWechatClient(wechatClient);

        // ---------- 4. 扫码登录与消息循环 ----------
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running.set(false);
            log.info("收到退出信号，机器人关闭");
        }));

        try (wechatClient) {
            log.info("开始获取登录二维码...");
            String qrContent = wechatClient.executeLogin();
            System.out.println("\n========= 请用微信扫描下方二维码登录 =========\n");
            System.out.println(QrCodeUtil.render(qrContent));
            System.out.println("=================================================\n");

            loginLatch.await();
            if (!wechatClient.isLoggedIn()) {
                log.error("登录未成功，进程退出");
                System.exit(1);
            }

            // ---------- 5. 消息长轮询循环（JDK21 虚拟线程工厂） ----------
            startPollLoop(wechatClient);

            log.info("机器人已就绪：私聊发送消息即可对话；发送「#chain 城市」体验串行链式调用；"
                    + "发送「#multi 城市1 城市2」体验多工具并行协作");
            log.info("已注册工具：{}", toolRegistry.toToolDefinitions().stream()
                    .map(d -> d.getFunction().getName()).toList());

            // 主线程阻塞等待退出信号
            while (running.get()) {
                Thread.sleep(1000);
            }
        }
    }

    /**
     * 消息长轮询循环：使用 JDK21 虚拟线程工厂的执行器驱动。
     */
    private static void startPollLoop(ILinkClient client) {
        ExecutorService pollExecutor = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("poll-loop", 0).factory());
        pollExecutor.submit(() -> {
            while (running.get()) {
                try {
                    client.getUpdates();
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("消息轮询异常：{}", e.getMessage());
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        });
        log.info("消息轮询循环已启动（JDK21 虚拟线程驱动）");
    }

    /**
     * 日志系统自检：验证 error/warn/info/debug 各级别输出正常。
     */
    private static void logSelfTestLogs() {
        log.debug("日志自检 [DEBUG]：调试级别输出正常");
        log.info("日志自检 [INFO]：信息级别输出正常");
        log.warn("日志自检 [WARN]：警告级别输出正常");
        log.error("日志自检 [ERROR]：错误级别输出正常（仅自检，非真实错误）");
    }
}
