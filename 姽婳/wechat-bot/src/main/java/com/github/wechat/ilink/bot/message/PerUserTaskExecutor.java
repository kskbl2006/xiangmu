package com.github.wechat.ilink.bot.message;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 不同用户并发执行，同一用户保持消息顺序。 */
final class PerUserTaskExecutor implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(PerUserTaskExecutor.class);
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  private final ConcurrentHashMap<String, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();

  void submit(String userId, Runnable task) {
    tails.compute(
        userId,
        (key, previous) -> {
          CompletableFuture<Void> base =
              previous == null
                  ? CompletableFuture.completedFuture(null)
                  : previous.exceptionally(
                      error -> {
                        log.warn("Previous task failed for userId={}: {}", key, error.getMessage());
                        return null;
                      });
          CompletableFuture<Void> next = base.thenRunAsync(task, workers);
          next.whenComplete((ignored, error) -> tails.remove(key, next));
          return next;
        });
  }

  @Override
  public void close() {
    workers.close();
  }
}
