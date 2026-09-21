package com.example.sounding.domain;

import java.util.List;

public record RaySolution(
        SolutionStatus status,
        Double depthM,
        Double acrossM,
        double correctedAngleDeg,
        double residualTimeS,
        double numericalTolerance,
        int iterations,
        List<RayLayerPass> layerPasses,
        List<RayPoint> points
) {
    public static RaySolution failure(SolutionStatus status, double correctedAngleDeg, double tolerance) {
        return new RaySolution(status, null, null, correctedAngleDeg, Double.NaN, tolerance,
                0, List.of(), List.of());
    }
}

