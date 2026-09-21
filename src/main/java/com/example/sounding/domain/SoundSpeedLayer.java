package com.example.sounding.domain;

public record SoundSpeedLayer(
        String profileId,
        String id,
        int ordinal,
        double depthTop,
        double depthBottom,
        double speedTop,
        double speedBottom
) {
}

