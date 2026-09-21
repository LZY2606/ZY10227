package com.example.svp.core;

import java.util.List;

/**
 * 姿态线性插值。时标整体平移 {@code delayMs}（波束时刻去姿态历史中取值）。
 * 查询落到锚点序列之外即显式失败，不做外推。
 */
public final class AttitudeInterpolator {

    public static final class Attitude {
        public final double roll;
        public final double pitch;
        public final double heading;
        public final String version;
        public final String anchors;

        Attitude(double roll, double pitch, double heading, String version, String anchors) {
            this.roll = roll;
            this.pitch = pitch;
            this.heading = heading;
            this.version = version;
            this.anchors = anchors;
        }
    }

    private final List<DataRepository.AttitudeRow> rows;
    private final long delayMs;
    private final String version;

    public AttitudeInterpolator(List<DataRepository.AttitudeRow> rows, long delayMs) {
        this.rows = rows;
        this.delayMs = delayMs;
        this.version = "ATT-DELAY-" + delayMs + "ms";
    }

    public String version() {
        return version;
    }

    /** @return 失败时返回 null（锚点越界） */
    public Attitude at(long pingT) {
        long q = pingT - delayMs;
        if (rows.isEmpty() || q < rows.get(0).t() || q > rows.get(rows.size() - 1).t()) {
            return null;
        }
        for (int i = 0; i < rows.size() - 1; i++) {
            DataRepository.AttitudeRow a = rows.get(i);
            DataRepository.AttitudeRow b = rows.get(i + 1);
            if (q >= a.t() && q <= b.t()) {
                double w = b.t() == a.t() ? 0.0 : (double) (q - a.t()) / (b.t() - a.t());
                return new Attitude(
                        a.roll() + w * (b.roll() - a.roll()),
                        a.pitch() + w * (b.pitch() - a.pitch()),
                        a.heading() + w * (b.heading() - a.heading()),
                        version,
                        a.id() + "|" + b.id());
            }
        }
        return null;
    }
}
