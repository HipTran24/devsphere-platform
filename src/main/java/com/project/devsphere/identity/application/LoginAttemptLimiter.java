package com.project.devsphere.identity.application;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Component
public class LoginAttemptLimiter {

    private final Clock clock;
    private final Map<String, Integer> accounts = new HashMap<>();
    private final Map<String, Integer> peers = new HashMap<>();
    private long currentWindowStart = Long.MIN_VALUE;
    private boolean failClosed;

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
            failClosed = false;
        }

        Duration remaining = Duration.between(now, Instant.ofEpochSecond(windowStart + 300));

        int accountCount = accounts.getOrDefault(email, 0);
        int peerCount = peers.getOrDefault(peer, 0);
        if (accountCount >= 8 || peerCount >= 80) {
            return remaining;
        }

        int newEntries = (accounts.containsKey(email) ? 0 : 1) + (peers.containsKey(peer) ? 0 : 1);
        if (failClosed || accounts.size() + peers.size() >= 10_000 || accounts.size() + peers.size() + newEntries > 10_000) {
            failClosed = true;
            return remaining;
        }

        accounts.put(email, accountCount + 1);
        peers.put(peer, peerCount + 1);
        return Duration.ZERO;
    }
}
