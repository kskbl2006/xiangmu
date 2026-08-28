package com.github.wechat.ilink.bot.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class ProviderCircuitBreakerTest {
  @Test
  void opensAfterThresholdAndAllowsOneHalfOpenProbe() {
    MutableClock clock = new MutableClock();
    ProviderCircuitBreaker breaker =
        new ProviderCircuitBreaker(2, Duration.ofMinutes(5), clock);

    breaker.recordFailure();
    assertTrue(breaker.allowRequest());
    breaker.recordFailure();
    assertEquals(ProviderCircuitBreaker.State.OPEN, breaker.state());
    assertFalse(breaker.allowRequest());

    clock.advance(Duration.ofMinutes(5));
    assertEquals(ProviderCircuitBreaker.State.HALF_OPEN, breaker.state());
    assertTrue(breaker.allowRequest());
    assertFalse(breaker.allowRequest());
    breaker.recordSuccess();
    assertEquals(ProviderCircuitBreaker.State.CLOSED, breaker.state());
  }

  private static final class MutableClock extends Clock {
    private Instant instant = Instant.parse("2026-08-28T00:00:00Z");

    void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
