package com.example.svp;

import com.example.svp.core.RayTracer;
import com.example.svp.core.SvpProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RayTracerTest {

    private final SvpProfile profile = new SvpProfile(
            "SVP-A", "A", 0L, 9000L,
            List.of(0.0, 10.0, 25.0, 50.0),
            List.of(1480.0, 1475.0, 1500.0, 1510.0));
    private final RayTracer tracer = new RayTracer();

    @Test
    void nadirTraceIsDepthOverSpeedPerLayer() throws Exception {
        RayTracer.TraceResult r = tracer.trace(profile, 0, 48);
        double expect = 10.0 / 1480 + 15.0 / 1475 + 23.0 / 1500;
        assertEquals(expect, r.time, 1e-12);
        assertEquals(0.0, r.horizontal, 1e-12);
        assertEquals(List.of(0, 1, 2), r.segments.stream().map(s -> s.layerIndex).toList());
    }

    @Test
    void endpointExactlyOnInterfaceBelongsToSingleLayer() throws Exception {
        RayTracer.TraceResult r = tracer.trace(profile, 30, 25.0);
        assertEquals(List.of(0, 1), r.segments.stream().map(s -> s.layerIndex).toList(),
                "终点正好在 25m 分界面：只经过层0与层1，层2 不得出现（端点只归一层）");
        RayTracer.Segment last = r.segments.get(r.segments.size() - 1);
        assertEquals(1, last.layerIndex);
        assertEquals(25.0, last.z1, 0.0);
        assertEquals(25.0, last.z0 + (last.z1 - last.z0), 0.0);
    }

    @Test
    void snellsLawHoldsAcrossLayers() throws Exception {
        RayTracer.TraceResult r = tracer.trace(profile, 60, 48);
        double p = Math.sin(Math.toRadians(60)) / 1480.0;
        for (RayTracer.Segment s : r.segments) {
            assertEquals(p * s.speed, Math.sin(s.thetaRad), 1e-12);
        }
        assertEquals(3, r.segments.size());
    }

    @Test
    void rayBeyondMaxDepthIsRejectedNotAveraged() {
        RayTracer.Failure f = assertThrows(RayTracer.Failure.class,
                () -> tracer.trace(profile, 0, 51));
        assertEquals(RayTracer.Failure.DEPTH_EXCEEDED, f.code);
    }

    @Test
    void solveDepthRoundTripsTravelTime() throws Exception {
        double half = 10.0 / 1480 + 15.0 / 1475 + 23.0 / 1500;
        RayTracer.SolveResult r = tracer.solveDepth(profile, 0, half, 1e-9);
        assertEquals(48.0, r.depth, 1e-6);
        assertTrue(r.residualSec <= 1e-9, "残差必须达到给定容差");
    }

    @Test
    void solveBeyondMaxDepthReportsFailureWithoutFallback() {
        RayTracer.Failure f = assertThrows(RayTracer.Failure.class,
                () -> tracer.solveDepth(profile, 55, 0.06565035, 1e-9));
        assertEquals(RayTracer.Failure.DEPTH_EXCEEDED, f.code);
        assertTrue(f.getMessage().contains("未支持"));
        double naive = 0.06565035 * 1490;
        assertTrue(naive > 50.0, "平均声速会伪造出一个深度，但裁决必须拒绝这种回退");
    }

    @Test
    void strictIncreasingDepthEnforced() {
        assertThrows(IllegalArgumentException.class, () -> new SvpProfile(
                "x", "x", 0, 1, List.of(0.0, 10.0, 10.0, 50.0),
                List.of(1480.0, 1475.0, 1500.0, 1510.0)));
    }

    @Test
    void totalReflectionReported() {
        RayTracer.Failure f = assertThrows(RayTracer.Failure.class,
                () -> tracer.solveDepth(profile, 85, 0.05, 1e-9));
        assertEquals(RayTracer.Failure.TOTAL_REFLECTION, f.code);
    }
}
