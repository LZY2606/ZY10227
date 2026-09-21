package com.example.sounding.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaySolverTest {
    private final RaySolver solver = new RaySolver();

    @Test
    void requiresStrictlyIncreasingLayerDepths() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> profile(List.of(
                        layer(1, 0, 10, 1500, 1500),
                        layer(2, 10, 10, 1490, 1490),
                        layer(3, 10, 20, 1495, 1495))));
        assertTrue(error.getMessage().contains("严格递增"));
    }

    @Test
    void interfaceEndpointBelongsOnlyToUpperLayer() {
        Profile profile = profile(List.of(
                layer(1, 0, 10, 1500, 1500),
                layer(2, 10, 25, 1488, 1488),
                layer(3, 25, 40, 1494, 1494)));
        double rtt = 2.0 * (10.0 / 1500.0 + 15.0 / 1488.0);

        RaySolution solution = solver.solve(profile, 0.0, rtt);

        assertEquals(SolutionStatus.VALID, solution.status());
        assertEquals(25.0, solution.depthM(), 1.0e-9);
        assertEquals(List.of("P1-L1", "P1-L2"),
                solution.layerPasses().stream().map(RayLayerPass::layerId).distinct().toList());
        assertEquals(1, solution.points().stream()
                .filter(point -> point.depthM() == 25.0).count());
        assertEquals("P1-L2", solution.points().stream()
                .filter(point -> point.depthM() == 25.0).findFirst().orElseThrow().owningLayerId());
        assertFalse(solution.layerPasses().stream().anyMatch(pass -> "P1-L3".equals(pass.layerId())));
    }

    @Test
    void targetBeyondMaximumDepthIsUnsupportedAndNeverUsesAverageSpeed() {
        Profile profile = profile(List.of(
                layer(1, 0, 10, 1500, 1500),
                layer(2, 10, 20, 1490, 1490),
                layer(3, 20, 30, 1498, 1498)));

        RaySolution solution = solver.solve(profile, 55.0, 0.2);

        assertEquals(SolutionStatus.BELOW_PROFILE_MAX_DEPTH, solution.status());
        assertFalse(solution.status() == SolutionStatus.VALID);
        assertTrue(solution.layerPasses().stream()
                .noneMatch(pass -> "BLOCK-L3".equals(pass.layerId())));
    }

    @Test
    void totalReflectionIsReportedAsBlocked() {
        Profile profile = profile("BLOCK", List.of(
                layer("BLOCK", 1, 0, 10, 1500, 1500),
                layer("BLOCK", 2, 10, 20, 1520, 1520),
                layer("BLOCK", 3, 20, 30, 1490, 1490)));

        RaySolution solution = solver.solve(profile, 81.0, 0.10);

        assertEquals(SolutionStatus.RAY_BLOCKED, solution.status());
        assertTrue(solution.points().stream().noneMatch(point -> point.depthM() > 10.0));
    }

    @Test
    void retainsIntegrationToleranceAndInputIdentitySeparatelyAtServiceBoundary() {
        RaySolver customSolver = new RaySolver(1.0e-9, 1.0e-11);
        Profile profile = profile(List.of(
                layer(1, 0, 10, 1500, 1500),
                layer(2, 10, 25, 1488, 1488),
                layer(3, 25, 40, 1494, 1494)));

        RaySolution solution = customSolver.solve(profile, 35.0, 0.040793794);

        assertEquals(SolutionStatus.VALID, solution.status());
        assertEquals(1.0e-9, solution.numericalTolerance(), 0.0);
        assertTrue(solution.residualTimeS() <= 1.0e-9);
    }

    private Profile profile(List<SoundSpeedLayer> layers) {
        return profile("P1", layers);
    }

    private Profile profile(String id, List<SoundSpeedLayer> layers) {
        return new Profile(id, id, Instant.parse("2026-09-22T08:00:00Z"),
                Instant.parse("2026-09-22T09:00:00Z"), layers);
    }

    private SoundSpeedLayer layer(int ordinal, double top, double bottom, double speedTop,
                                  double speedBottom) {
        return layer("P1", ordinal, top, bottom, speedTop, speedBottom);
    }

    private SoundSpeedLayer layer(String profileId, int ordinal, double top, double bottom,
                                  double speedTop, double speedBottom) {
        return new SoundSpeedLayer(profileId, profileId + "-L" + ordinal, ordinal,
                top, bottom, speedTop, speedBottom);
    }
}

