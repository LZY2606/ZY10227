package com.example.sounding.domain;

import java.time.Instant;

public record Ping(
        String id,
        String lineId,
        Instant eventTime,
        double eastM,
        double northM
) {
}

