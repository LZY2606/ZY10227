package com.example.sounding.domain;

import java.util.ArrayList;
import java.util.List;

public class RaySolver {
    private static final int MAX_BISECTIONS = 60;
    private static final int MAX_SIMPSON_DEPTH = 16;
    private static final double DEPTH_EPSILON = 1.0e-9;

    private final double timeTolerance;
    private final double integralTolerance;

    public RaySolver() {
        this(1.0e-10, 1.0e-12);
    }

    public RaySolver(double timeTolerance, double integralTolerance) {
        if (!(timeTolerance > 0 && integralTolerance > 0)) {
            throw new IllegalArgumentException("数值积分容差必须为正");
        }
        this.timeTolerance = timeTolerance;
        this.integralTolerance = integralTolerance;
    }

    public RaySolution solve(Profile profile, double launchAngleDeg, double twoWayTimeS) {
        if (!(twoWayTimeS > 0)) {
            return RaySolution.failure(SolutionStatus.INVALID_INPUT, launchAngleDeg, timeTolerance);
        }

        double targetOneWay = twoWayTimeS / 2.0;
        double launchRad = Math.toRadians(launchAngleDeg);
        double rayParameter = Math.sin(launchRad) / profile.layers().get(0).speedTop();
        double elapsed = 0.0;
        double across = 0.0;
        List<RayLayerPass> passes = new ArrayList<>();
        List<RayPoint> points = new ArrayList<>();
        for (int index = 0; index < profile.layers().size(); index++) {
            SoundSpeedLayer layer = profile.layers().get(index);
            double speedAtTop = speedAt(layer, layer.depthTop());
            double topRatio = Math.abs(rayParameter * speedAtTop);
            if (topRatio >= 1.0 - DEPTH_EPSILON) {
                return RaySolution.failure(SolutionStatus.RAY_BLOCKED, launchAngleDeg, timeTolerance);
            }
            if (index == 0) {
                points.add(new RayPoint(0, 0.0, 0.0, layer.id()));
            }

            double reachableBottom = reachableBottom(layer, rayParameter);
            boolean blockedInside = reachableBottom < layer.depthBottom() - DEPTH_EPSILON;
            double bottomForLayer = Math.min(layer.depthBottom(), reachableBottom);
            Integration boundary = integrate(layer, rayParameter, layer.depthTop(), bottomForLayer,
                    elapsed, across);

            if (!blockedInside && index + 1 < profile.layers().size()) {
                SoundSpeedLayer next = profile.layers().get(index + 1);
                double nextSpeed = speedAt(next, next.depthTop());
                if (Math.abs(rayParameter * nextSpeed) >= 1.0 - DEPTH_EPSILON
                        && targetOneWay >= boundary.time() - timeTolerance) {
                    List<RayLayerPass> blockedPasses = new ArrayList<>(passes);
                    blockedPasses.add(new RayLayerPass(layer.id(), layer.ordinal(),
                            layer.depthTop(), layer.depthBottom(), boundary.acrossEntry(),
                            boundary.across(), boundary.time() - boundary.timeEntry()));
                    List<RayPoint> blockedPoints = new ArrayList<>(points);
                    blockedPoints.add(new RayPoint(blockedPoints.size(), across,
                            layer.depthBottom(), layer.id()));
                    return new RaySolution(SolutionStatus.RAY_BLOCKED, null, null,
                            launchAngleDeg, boundary.time() - targetOneWay, timeTolerance, 0,
                            List.copyOf(blockedPasses), List.copyOf(blockedPoints));
                }
            }

            if (boundary.time() >= targetOneWay || approximatelyAtTarget(boundary.time(), targetOneWay)) {
                RaySolution solution = solveWithinLayer(layer, rayParameter, launchAngleDeg,
                        targetOneWay, elapsed, across, passes, points, boundary.time());
                if (solution != null) {
                    return solution;
                }
                if (blockedInside) {
                    return RaySolution.failure(SolutionStatus.RAY_BLOCKED, launchAngleDeg, timeTolerance);
                }
            }

            elapsed = boundary.time();
            across = boundary.across();
            passes.add(new RayLayerPass(layer.id(), layer.ordinal(), layer.depthTop(),
                    bottomForLayer, boundary.acrossEntry(), boundary.across(),
                    boundary.time() - boundary.timeEntry()));
            points.add(new RayPoint(points.size(), across, bottomForLayer, layer.id()));

            if (blockedInside) {
                return RaySolution.failure(SolutionStatus.RAY_BLOCKED, launchAngleDeg, timeTolerance);
            }

            if (index + 1 < profile.layers().size()) {
                SoundSpeedLayer next = profile.layers().get(index + 1);
                double nextSpeed = speedAt(next, next.depthTop());
                if (Math.abs(rayParameter * nextSpeed) >= 1.0 - DEPTH_EPSILON) {
                    return new RaySolution(SolutionStatus.RAY_BLOCKED, null, null, launchAngleDeg,
                            elapsed - targetOneWay, timeTolerance, 0, List.copyOf(passes),
                            List.copyOf(points));
                }
            } else {
                return new RaySolution(SolutionStatus.BELOW_PROFILE_MAX_DEPTH, null, null,
                        launchAngleDeg, elapsed - targetOneWay, timeTolerance, 0,
                        List.copyOf(passes), List.copyOf(points));
            }
        }

        return RaySolution.failure(SolutionStatus.BELOW_PROFILE_MAX_DEPTH, launchAngleDeg, timeTolerance);
    }

    private RaySolution solveWithinLayer(SoundSpeedLayer layer, double rayParameter, double launchAngleDeg,
                                         double targetOneWay, double elapsed, double across,
                                         List<RayLayerPass> passes, List<RayPoint> points,
                                         double layerEndTime) {
        double low = layer.depthTop();
        double high = layer.depthBottom();
        double bestDepth = low;
        double bestTime = elapsed;
        double bestAcross = across;
        double bestResidual = Double.POSITIVE_INFINITY;

        if (Math.abs(layerEndTime - targetOneWay) <= timeTolerance) {
            bestDepth = high;
            Integration end = integrate(layer, rayParameter, low, high, elapsed, across);
            bestTime = end.time();
            bestAcross = end.across();
            bestResidual = Math.abs(bestTime - targetOneWay);
        } else {
            for (int iteration = 1; iteration <= MAX_BISECTIONS; iteration++) {
                double middle = (low + high) / 2.0;
                Integration candidate = integrate(layer, rayParameter, layer.depthTop(), middle,
                        elapsed, across);
                double residual = candidate.time() - targetOneWay;
                if (Math.abs(residual) < bestResidual) {
                    bestResidual = Math.abs(residual);
                    bestDepth = middle;
                    bestTime = candidate.time();
                    bestAcross = candidate.across();
                }
                if (Math.abs(residual) <= timeTolerance) {
                    return buildSolution(layer, launchAngleDeg, elapsed, across, passes, points,
                            middle, candidate, bestResidual, iteration);
                }
                if (residual < 0) {
                    low = middle;
                } else {
                    high = middle;
                }
            }
        }

        if (bestResidual <= timeTolerance) {
            Integration finalIntegration = integrate(layer, rayParameter, layer.depthTop(), bestDepth,
                    elapsed, across);
            return buildSolution(layer, launchAngleDeg, elapsed, across, passes, points,
                    bestDepth, finalIntegration, bestResidual, MAX_BISECTIONS);
        }
        return null;
    }

    private RaySolution buildSolution(SoundSpeedLayer layer, double launchAngleDeg, double elapsed,
                                      double across, List<RayLayerPass> passes,
                                      List<RayPoint> points, double depth, Integration integration,
                                      double residual, int iterations) {
        List<RayLayerPass> resultPasses = new ArrayList<>(passes);
        resultPasses.add(new RayLayerPass(layer.id(), layer.ordinal(), layer.depthTop(), depth,
                integration.acrossEntry(), integration.across(),
                integration.time() - elapsed));
        List<RayPoint> resultPoints = new ArrayList<>(points);
        resultPoints.add(new RayPoint(resultPoints.size(), integration.across(), depth, layer.id()));
        return new RaySolution(SolutionStatus.VALID, depth, integration.across(), launchAngleDeg,
                residual, timeTolerance, iterations, List.copyOf(resultPasses), List.copyOf(resultPoints));
    }

    private double reachableBottom(SoundSpeedLayer layer, double rayParameter) {
        double top = speedAt(layer, layer.depthTop());
        double bottom = speedAt(layer, layer.depthBottom());
        double maximumSpeed = Math.max(Math.abs(top), Math.abs(bottom));
        if (Math.abs(rayParameter) * maximumSpeed < 1.0) {
            return layer.depthBottom();
        }
        if (rayParameter == 0.0) {
            return layer.depthBottom();
        }
        double turningDepth = layer.depthTop()
                + ((1.0 / Math.abs(rayParameter) - top) / ((bottom - top)
                / (layer.depthBottom() - layer.depthTop())));
        if (turningDepth <= layer.depthTop() || turningDepth >= layer.depthBottom()) {
            return layer.depthBottom();
        }
        return turningDepth;
    }

    private Integration integrate(SoundSpeedLayer layer, double rayParameter, double fromDepth,
                                  double toDepth, double timeEntry, double acrossEntry) {
        if (toDepth <= fromDepth) {
            return new Integration(timeEntry, acrossEntry, timeEntry, acrossEntry);
        }
        double time = adaptiveSimpson(depth -> timeDerivative(layer, rayParameter, depth),
                fromDepth, toDepth, integralTolerance, 0);
        double distance = adaptiveSimpson(depth -> acrossDerivative(rayParameter, speedAt(layer, depth)),
                fromDepth, toDepth, integralTolerance, 0);
        return new Integration(timeEntry, acrossEntry, timeEntry + time, acrossEntry + distance);
    }

    private double timeDerivative(SoundSpeedLayer layer, double rayParameter, double depth) {
        double speed = speedAt(layer, depth);
        double acrossFactor = rayParameter * speed;
        return 1.0 / (speed * Math.sqrt(1.0 - acrossFactor * acrossFactor));
    }

    private double acrossDerivative(double rayParameter, double speed) {
        double acrossFactor = rayParameter * speed;
        return acrossFactor / Math.sqrt(1.0 - acrossFactor * acrossFactor);
    }

    private double adaptiveSimpson(DoubleFunction function, double a, double b, double tolerance,
                                   int recursionDepth) {
        double h = b - a;
        double fa = function.apply(a);
        double fb = function.apply(b);
        double fm = function.apply((a + b) / 2.0);
        double whole = h * (fa + 4.0 * fm + fb) / 6.0;
        double middle = (a + b) / 2.0;
        double flm = function.apply((a + middle) / 2.0);
        double frm = function.apply((middle + b) / 2.0);
        double left = h * (fa + 4.0 * flm + fm) / 12.0;
        double right = h * (fm + 4.0 * frm + fb) / 12.0;
        double refined = left + right;
        if (recursionDepth >= MAX_SIMPSON_DEPTH || Math.abs(refined - whole) <= 15.0 * tolerance) {
            return refined + (refined - whole) / 15.0;
        }
        return adaptiveSimpson(function, a, middle, tolerance / 2.0, recursionDepth + 1)
                + adaptiveSimpson(function, middle, b, tolerance / 2.0, recursionDepth + 1);
    }

    private boolean approximatelyAtTarget(double value, double target) {
        return Math.abs(value - target) <= timeTolerance;
    }

    private double speedAt(SoundSpeedLayer layer, double depth) {
        if (layer.depthBottom() == layer.depthTop()) {
            return layer.speedTop();
        }
        double fraction = (depth - layer.depthTop()) / (layer.depthBottom() - layer.depthTop());
        return layer.speedTop() + fraction * (layer.speedBottom() - layer.speedTop());
    }

    @FunctionalInterface
    private interface DoubleFunction {
        double apply(double value);
    }

    private record Integration(double timeEntry, double acrossEntry, double time, double across) {
    }
}

