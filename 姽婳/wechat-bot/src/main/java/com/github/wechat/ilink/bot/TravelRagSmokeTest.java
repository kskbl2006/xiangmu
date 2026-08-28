package com.github.wechat.ilink.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.bot.config.AppConfig;
import com.github.wechat.ilink.bot.travelrag.TravelRagService;
import java.util.Locale;

/** 独立测试语义检索，不连接微信或调用聊天模型。 */
public final class TravelRagSmokeTest {
  private TravelRagSmokeTest() {}

  public static void main(String[] args) throws Exception {
    String query = args.length == 0 ? "上海下雨天带孩子适合去哪里游玩？" : String.join(" ", args);
    TravelRagService service =
        TravelRagService.fromBundledIndex(AppConfig.fromEnvironment(), new ObjectMapper());
    var hits = service.retrieve(query);
    System.out.println("query=" + query);
    System.out.println("hits=" + hits.size());
    for (var hit : hits) {
      System.out.printf(
          Locale.ROOT,
          "%.4f\t%s\t%s\t%s%n",
          hit.score(),
          hit.chunk().id(),
          hit.chunk().city(),
          hit.chunk().title());
    }
    if (hits.isEmpty()) {
      throw new IllegalStateException("travel retrieval returned no hits");
    }
  }
}
