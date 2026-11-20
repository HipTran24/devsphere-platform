package com.project.devsphere.resource.api;

import java.time.Instant;
import java.util.Objects;

public record TimeOff(Instant startAt, Instant endAt) {
    public TimeOff {
        Objects.requireNonNull(startAt); Objects.requireNonNull(endAt);
        if (!endAt.isAfter(startAt)) throw new IllegalArgumentException("Time off must have positive duration");
    }
}
