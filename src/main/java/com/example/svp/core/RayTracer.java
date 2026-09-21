package com.example.svp.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 分层常声速射线积分器（Snell 定律 + 自适应 Simpson 数值积分）。
 *
 * <p>层 k 区间为 (z_k, z_{k+1}]，终点恰好等于分界面 z_{k+1} 时，
 * 该折射点只计入层 k（上侧、先到达的一层）。
 *
 * <p>求解失败一律以 {@link Failure} 形式显式报告：
 * 波束超出剖面最大深度报告 {@link Failure#DEPTH_EXCEEDED}，
 * 全反射报告 {@link Failure#TOTAL_REFLECTION}，
 * 数值迭代不能在容差内到达给定往返时间报告 {@link Failure#NO_CONVERGENCE}。
 * 任何情况下都不会回退到平均声速“假装成功”。
 */
public final class RayTracer {

    public static final class Segment {
        public final int layerIndex;
        public final double z0;
        public final double z1;
        public final double speed;
        public final double thetaRad;
        public final double dt;
        public final double dx;

        Segment(int layerIndex, double z0, double z1, double speed,
                double thetaRad, double dt, double dx) {
            this.layerIndex = layerIndex;
            this.z0 = z0;
            this.z1 = z1;
            this.speed = speed;
            this.thetaRad = thetaRad;
            this.dt = dt;
            this.dx = dx;
        }
    }

    public static final class TraceResult {
        public final double time;
        public final double horizontal;
        public final List<Segment> segments;

        TraceResult(double time, double horizontal, List<Segment> segments) {
            this.time = time;
            this.horizontal = horizontal;
            this.segments = List.copyOf(segments);
        }
    }

    public static final class SolveResult {
        public final double depth;
        public final double horizontal;
        public final double residualSec;
        public final List<Segment> segments;

        SolveResult(double depth, double horizontal, double residualSec, List<Segment> segments) {
            this.depth = depth;
            this.horizontal = horizontal;
            this.residualSec = residualSec;
            this.segments = List.copyOf(segments);
        }
    }

    public static final class Failure extends Exception {
        public static final String DEPTH_EXCEEDED = "DEPTH_EXCEEDED";
        public static final String TOTAL_REFLECTION = "TOTAL_REFLECTION";
        public static final String NO_CONVERGENCE = "NO_CONVERGENCE";
        public final String code;

        public Failure(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static final int MAX_SIMPSON_DEPTH = 24;
    private static final int MAX_BISECTIONS = 100;

    /**
     * 以发射角 thetaDeg（相对垂直向下方向，带符号）追踪射线至 zEnd。
     */
    public TraceResult trace(SvpProfile profile, double thetaDeg, double zEnd) throws Failure {
        if (zEnd < 0) {
            throw new Failure(Failure.NO_CONVERGENCE, "目标深度为负");
        }
        double p = Math.sin(Math.toRadians(thetaDeg)) / profile.surfaceSpeed();
        double zCur = 0.0;
        double tAcc = 0.0;
        double xAcc = 0.0;
        List<Segment> segs = new ArrayList<>();
        for (SvpProfile.Layer layer : profile.layers()) {
            double s = p * layer.speed;
            if (s >= 1.0) {
                throw new Failure(Failure.TOTAL_REFLECTION,
                        "射线在层 " + layer.index + " 发生全反射 (sinθ=" + s + ")");
            }
            double theta = Math.asin(s);
            double cos = Math.cos(theta);
            double tan = s / cos;
            double z1 = Math.min(zEnd, layer.zBottom);
            if (z1 > zCur) {
                double dt = integrateTime(layer, s, zCur, z1);
                double dz = z1 - zCur;
                double dx = dz * tan;
                tAcc += dt;
                xAcc += dx;
                segs.add(new Segment(layer.index, zCur, z1, layer.speed, theta, dt, dx));
                zCur = z1;
            }
            if (zCur >= zEnd - Math.ulp(zEnd) * 8) {
                return new TraceResult(tAcc, xAcc, segs);
            }
        }
        throw new Failure(Failure.DEPTH_EXCEEDED,
                "射线终点 " + zEnd + " m 超出剖面最大深度 " + profile.maxDepth() + " m");
    }

    /** 单程到达剖面最大深度所需时间；全反射时返回空。 */
    public Optional<Double> timeToMaxDepth(SvpProfile profile, double thetaDeg) {
        double p = Math.sin(Math.toRadians(thetaDeg)) / profile.surfaceSpeed();
        double t = 0.0;
        for (SvpProfile.Layer layer : profile.layers()) {
            double s = p * layer.speed;
            if (s >= 1.0) {
                return Optional.empty();
            }
            double cos = Math.sqrt(1.0 - s * s);
            t += integrateTime(layer, s, layer.zTop, layer.zBottom);
        }
        return Optional.of(t);
    }

    /**
     * 按给定单程时间 halfTravelTimeSec 反演海底深度。
     *
     * @param tolSec 数值积分/迭代容差（秒），与结果一并保留
     */
    public SolveResult solveDepth(SvpProfile profile, double thetaDeg,
                                  double halfTravelTimeSec, double tolSec) throws Failure {
        if (halfTravelTimeSec < 0) {
            throw new Failure(Failure.NO_CONVERGENCE, "往返时间为负");
        }
        Optional<Double> tMaxOpt = timeToMaxDepth(profile, thetaDeg);
        if (tMaxOpt.isEmpty()) {
            throw new Failure(Failure.TOTAL_REFLECTION, "射线在达到海底前发生全反射");
        }
        double tMax = tMaxOpt.get();
        if (halfTravelTimeSec > tMax + tolSec) {
            throw new Failure(Failure.DEPTH_EXCEEDED, String.format(
                    "需要单程时间 %.9f s，但射线到达剖面最大深度 %.1f m 仅需 %.9f s；"
                            + "超出剖面深度，未支持（不回退平均声速）",
                    halfTravelTimeSec, profile.maxDepth(), tMax));
        }
        double lo = 0.0;
        double hi = profile.maxDepth();
        for (int i = 0; i < MAX_BISECTIONS; i++) {
            double mid = (lo + hi) / 2.0;
            TraceResult tr = trace(profile, thetaDeg, mid);
            if (tr.time < halfTravelTimeSec) {
                lo = mid;
            } else {
                hi = mid;
            }
            if (hi - lo <= tolSec * profile.surfaceSpeed() * 0.5) {
                break;
            }
        }
        double depth = (lo + hi) / 2.0;
        TraceResult tr = trace(profile, thetaDeg, depth);
        double residual = Math.abs(tr.time - halfTravelTimeSec);
        if (residual > Math.max(tolSec, 1e-7)) {
            throw new Failure(Failure.NO_CONVERGENCE, String.format(
                    "迭代 %d 次后残差 %.3e s 仍大于容差 %.3e s",
                    MAX_BISECTIONS, residual, tolSec));
        }
        return new SolveResult(depth, tr.horizontal, residual, tr.segments);
    }

    /**
     * 层内单程时间积分：dt = dz / (c(z)·cosθ(z))，sinθ = p·c(z)。
     * 本 fixture 为层内常声速，被积函数为常数；仍采用自适应 Simpson，
     * 以便保留数值积分口径与容差并兼容更一般的层内变化实现。
     */
    private double integrateTime(SvpProfile.Layer layer, double pSin, double a, double b) {
        double c = layer.speed;
        double baseS = pSin;
        double whole = simpson(a, b, c, baseS);
        return adaptive(a, b, c, baseS, whole, 0);
    }

    private double adaptive(double a, double b, double c, double s0,
                            double whole, int depth) {
        double m = (a + b) / 2.0;
        double left = simpson(a, m, c, s0);
        double right = simpson(m, b, c, s0);
        double err = Math.abs(left + right - whole) / 15.0;
        double tol = 1e-10 * Math.max(1.0, Math.abs(whole));
        if (depth >= MAX_SIMPSON_DEPTH || err <= tol) {
            return left + right + (left + right - whole) / 15.0;
        }
        return adaptive(a, m, c, s0, left, depth + 1)
                + adaptive(m, b, c, s0, right, depth + 1);
    }

    private double simpson(double a, double b, double c, double s0) {
        double fa = integrand(a, c, s0);
        double fm = integrand((a + b) / 2.0, c, s0);
        double fb = integrand(b, c, s0);
        return (b - a) / 6.0 * (fa + 4 * fm + fb);
    }

    private double integrand(double z, double c, double s0) {
        double cos = Math.sqrt(1.0 - s0 * s0);
        return 1.0 / (c * cos);
    }
}
