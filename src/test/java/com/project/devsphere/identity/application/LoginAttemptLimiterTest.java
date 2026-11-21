package com.project.devsphere.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptLimiterTest {
    @Test
    void changingPeersCannotBypassEmailBudgetAndBucketsExpire() {
        var clock = new MutableClock();
        var limiter = new LoginAttemptLimiter(clock);
        for (int i = 0; i < 8; i++) {
            assertThat(limiter.retryAfter("user@example.test", "192.0.2." + i)).isZero();
        }
        assertThat(limiter.retryAfter("user@example.test", "192.0.2.9")).isEqualTo(Duration.ofMinutes(5));
        assertThat(limiter.retryAfter("another@example.test", "192.0.2.9")).isZero();

        clock.now = clock.now.plus(Duration.ofMinutes(5));
        assertThat(limiter.retryAfter("user@example.test", "192.0.2.9")).isZero();
    }

    @Test
    void changingEmailsCannotBypassPeerBudget() {
        var limiter = new LoginAttemptLimiter(new MutableClock());
        for (int i = 0; i < 80; i++) {
            assertThat(limiter.retryAfter(i + "@example.test", "192.0.2.1")).isZero();
        }
        assertThat(limiter.retryAfter("next@example.test", "192.0.2.1")).isEqualTo(Duration.ofMinutes(5));
        assertThat(limiter.retryAfter("next@example.test", "192.0.2.2")).isZero();
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-20T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
