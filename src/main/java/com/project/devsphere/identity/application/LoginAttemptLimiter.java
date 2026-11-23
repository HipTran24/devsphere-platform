package com.project.devsphere.identity.application;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class LoginAttemptLimiter {

    private static final int MAX_ENTRIES = 10_000;

    private final Clock clock;
    private final Map<String, Integer> accounts = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
            return size() > MAX_ENTRIES;
        }
    };
    private final Map<String, Integer> peers = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
            return size() > MAX_ENTRIES;
        }
    };
    private long currentWindowStart = Long.MIN_VALUE;

    public LoginAttemptLimiter() {
        this(Clock.systemUTC());
    }

    LoginAttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    public synchronized Duration retryAfter(String email, String peer) {
        Instant now = clock.instant();
        long windowStart = Math.floorDiv(now.getEpochSecond(), 300) * 300;
        if (windowStart != currentWindowStart) {
            currentWindowStart = windowStart;
            accounts.clear();
            peers.clear();
        }

        Duration remaining = Duration.between(now, Instant.ofEpochSecond(windowStart + 300));

        int accountCount = accounts.getOrDefault(email, 0);
        int peerCount = peers.getOrDefault(peer, 0);
        if (accountCount >= 8 || peerCount >= 80) {
            return remaining;
        }

        accounts.put(email, accountCount + 1);
        peers.put(peer, peerCount + 1);
        return Duration.ZERO;
    }
}
