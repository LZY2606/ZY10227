package com.example.sounding.domain;

public record RayPoint(
        int order,
        double acrossM,
        double depthM,
        String owningLayerId
) {
}

