package com.example.sounding.domain;

public record RayLayerPass(
        String layerId,
        int ordinal,
        double enteredDepthM,
        double leftDepthM,
        double enteredAcrossM,
        double leftAcrossM,
        double oneWayTimeS
) {
}

