package com.github.wechat.ilink.sdk.core.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.retry.RetryPolicy;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.Test;

class HttpClientFacadeTest {
  @Test
  void retriesTransientUploadFailure() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
      server.enqueue(new MockResponse().setResponseCode(200).setHeader("x-encrypted-param", "ok"));
      HttpClientFacade facade =
          new HttpClientFacade(
              ILinkConfig.builder().build(), new RetryPolicy(2, ignored -> 0L));

      String encryptedParam = facade.uploadBytes(server.url("/upload").toString(), new byte[] {1});

      assertEquals("ok", encryptedParam);
      assertEquals(2, server.getRequestCount());
    }
  }
}
