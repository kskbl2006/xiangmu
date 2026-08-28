package com.github.wechat.ilink.bot.resilience;

import java.time.Clock;
import java.time.Duration;

/** 面向可选外部服务的轻量线程安全熔断器。 */
public final class ProviderCircuitBreaker {
  private final int failureThreshold;
  private final long cooldownMillis;
  private final Clock clock;
  private int consecutiveFailures;
  private long openUntilEpochMillis;
  private boolean halfOpenTrialInProgress;

  public ProviderCircuitBreaker(int failureThreshold, Duration cooldown, Clock clock) {
    this.failureThreshold = Math.max(1, failureThreshold);
    this.cooldownMillis = Math.max(1, cooldown.toMillis());
    this.clock = clock;
  }

  public synchronized boolean allowRequest() {
    long now = clock.millis();
    if (openUntilEpochMillis == 0) return true;
    if (now < openUntilEpochMillis) return false;
    if (halfOpenTrialInProgress) return false;
    halfOpenTrialInProgress = true;
    return true;
  }

  public synchronized void recordSuccess() {
    consecutiveFailures = 0;
    openUntilEpochMillis = 0;
    halfOpenTrialInProgress = false;
  }

  public synchronized void recordFailure() {
    halfOpenTrialInProgress = false;
    consecutiveFailures++;
    if (consecutiveFailures >= failureThreshold) {
      openUntilEpochMillis = clock.millis() + cooldownMillis;
    }
  }

  public synchronized State state() {
    if (openUntilEpochMillis == 0) return State.CLOSED;
    return clock.millis() < openUntilEpochMillis ? State.OPEN : State.HALF_OPEN;
  }

  public enum State {
    CLOSED,
    OPEN,
    HALF_OPEN
  }
}
