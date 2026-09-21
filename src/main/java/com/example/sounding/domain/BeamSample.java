package com.example.sounding.domain;

public record BeamSample(
        String id,
        String pingId,
        int beamIndex,
        double launchAngleDeg,
        double twoWayTimeS
) {
}

