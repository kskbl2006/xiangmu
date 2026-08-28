package com.github.wechat.ilink.bot.maps;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Properties;

/** 为低额度服务提供每日请求计数和熔断保护。 */
final class DailyQuotaGuard {
  private final boolean enabled;
  private final int dailyLimit;
  private final Path stateFile;
  private final Clock clock;
  private LocalDate date;
  private int used;
  private boolean circuitOpen;

  DailyQuotaGuard(boolean enabled, int dailyLimit, Path stateFile, Clock clock) {
    this.enabled = enabled;
    this.dailyLimit = Math.max(1, dailyLimit);
    this.stateFile = stateFile;
    this.clock = clock;
    load();
  }

  synchronized boolean available() {
    rollDayIfNeeded();
    return enabled && !circuitOpen && used < dailyLimit;
  }

  synchronized boolean acquire() {
    rollDayIfNeeded();
    if (!enabled || circuitOpen || used >= dailyLimit) return false;
    used++;
    save();
    return true;
  }

  synchronized void openCircuit() {
    rollDayIfNeeded();
    circuitOpen = true;
    save();
  }

  synchronized Status status() {
    rollDayIfNeeded();
    if (!enabled) return Status.DISABLED;
    if (circuitOpen) return Status.CIRCUIT_OPEN;
    if (used >= dailyLimit) return Status.DAILY_LIMIT_REACHED;
    return Status.AVAILABLE;
  }

  synchronized int used() {
    rollDayIfNeeded();
    return used;
  }

  enum Status {
    DISABLED,
    AVAILABLE,
    DAILY_LIMIT_REACHED,
    CIRCUIT_OPEN
  }

  private void rollDayIfNeeded() {
    LocalDate today = LocalDate.now(clock);
    if (today.equals(date)) return;
    date = today;
    used = 0;
    circuitOpen = false;
    save();
  }

  private void load() {
    date = LocalDate.now(clock);
    if (stateFile == null || !Files.isRegularFile(stateFile)) return;
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(stateFile, StandardCharsets.UTF_8)) {
      properties.load(reader);
      LocalDate storedDate = LocalDate.parse(properties.getProperty("date", date.toString()));
      if (!storedDate.equals(date)) return;
      used = Math.max(0, Integer.parseInt(properties.getProperty("used", "0")));
      circuitOpen = Boolean.parseBoolean(properties.getProperty("circuitOpen", "false"));
    } catch (Exception ignored) {
      used = 0;
      circuitOpen = false;
    }
  }

  private void save() {
    if (stateFile == null) return;
    try {
      Path parent = stateFile.getParent();
      if (parent != null) Files.createDirectories(parent);
      Properties properties = new Properties();
      properties.setProperty("date", date.toString());
      properties.setProperty("used", Integer.toString(used));
      properties.setProperty("circuitOpen", Boolean.toString(circuitOpen));
      Path temporary = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
      try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
        properties.store(writer, "Local provider quota state; contains no credentials");
      }
      try {
        Files.move(
            temporary,
            stateFile,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException unsupportedAtomicMove) {
        Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException ignored) {
      // 本地状态无法保存时，额度保护仍在内存中生效。
    }
  }
}
