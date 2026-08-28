package com.github.wechat.ilink.bot.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PerUserTaskExecutorTest {
  @Test
  void preservesPerUserOrderWhileAllowingDifferentUsersToRunConcurrently() throws Exception {
    try (PerUserTaskExecutor executor = new PerUserTaskExecutor()) {
      CountDownLatch firstStarted = new CountDownLatch(1);
      CountDownLatch releaseFirst = new CountDownLatch(1);
      CountDownLatch otherUserFinished = new CountDownLatch(1);
      CountDownLatch sameUserFinished = new CountDownLatch(1);
      List<String> order = new CopyOnWriteArrayList<>();

      executor.submit(
          "user-a",
          () -> {
            order.add("a1-start");
            firstStarted.countDown();
            await(releaseFirst);
            order.add("a1-end");
          });
      assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
      executor.submit(
          "user-a",
          () -> {
            order.add("a2");
            sameUserFinished.countDown();
          });
      executor.submit(
          "user-b",
          () -> {
            order.add("b1");
            otherUserFinished.countDown();
          });

      assertTrue(otherUserFinished.await(2, TimeUnit.SECONDS));
      assertEquals(List.of("a1-start", "b1"), order);
      releaseFirst.countDown();
      assertTrue(sameUserFinished.await(2, TimeUnit.SECONDS));
      assertEquals(List.of("a1-start", "b1", "a1-end", "a2"), order);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(2, TimeUnit.SECONDS)) throw new AssertionError("timed out");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }
}
