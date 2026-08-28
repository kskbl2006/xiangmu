package com.github.wechat.ilink.bot.maps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DailyQuotaGuardTest {
  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private static final Clock DAY_ONE =
      Clock.fixed(Instant.parse("2026-08-28T00:00:00Z"), ZONE);

  @TempDir Path temporaryDirectory;

  @Test
  void persistsUsageAndStopsAtDailyLimit() {
    Path state = temporaryDirectory.resolve("quota.properties");
    DailyQuotaGuard first = new DailyQuotaGuard(true, 2, state, DAY_ONE);

    assertTrue(first.acquire());
    assertTrue(first.acquire());
    assertFalse(first.acquire());
    assertEquals(DailyQuotaGuard.Status.DAILY_LIMIT_REACHED, first.status());

    DailyQuotaGuard restored = new DailyQuotaGuard(true, 2, state, DAY_ONE);
    assertEquals(2, restored.used());
    assertFalse(restored.available());
  }

  @Test
  void keepsDisabledProviderClosedAndResetsCircuitOnNextDay() {
    Path state = temporaryDirectory.resolve("quota.properties");
    DailyQuotaGuard disabled = new DailyQuotaGuard(false, 10, state, DAY_ONE);
    assertFalse(disabled.acquire());
    assertEquals(DailyQuotaGuard.Status.DISABLED, disabled.status());

    DailyQuotaGuard enabled = new DailyQuotaGuard(true, 10, state, DAY_ONE);
    enabled.openCircuit();
    assertEquals(DailyQuotaGuard.Status.CIRCUIT_OPEN, enabled.status());

    Clock nextDay = Clock.fixed(Instant.parse("2026-08-29T00:00:00Z"), ZONE);
    DailyQuotaGuard reset = new DailyQuotaGuard(true, 10, state, nextDay);
    assertEquals(DailyQuotaGuard.Status.AVAILABLE, reset.status());
    assertEquals(0, reset.used());
  }
}
