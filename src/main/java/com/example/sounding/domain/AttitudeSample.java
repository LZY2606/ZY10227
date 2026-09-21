package com.example.sounding.domain;

import java.time.Instant;

public record AttitudeSample(
        String id,
        Instant eventTime,
        double rollDeg,
        double pitchDeg,
        double heaveM
) {
}

