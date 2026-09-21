package com.example.svp.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 分段等声速剖面。层 k 占据深度区间 (depth_k, depth_{k+1}]，
 * 即正好落在分界面上的折射点只归属于上侧（先到达的）一层。
 */
public final class SvpProfile {

    public static final class Layer {
        public final int index;
        public final double zTop;
        public final double zBottom;
        public final double speed;

        public Layer(int index, double zTop, double zBottom, double speed) {
            this.index = index;
            this.zTop = zTop;
            this.zBottom = zBottom;
            this.speed = speed;
        }
    }

    private final String id;
    private final String name;
    private final long validStart;
    private final long validEnd;
    private final List<Double> depths;
    private final List<Double> speeds;
    private final List<Layer> layers;

    public SvpProfile(String id, String name, long validStart, long validEnd,
                      List<Double> depths, List<Double> speeds) {
        if (depths.size() != speeds.size() || depths.size() < 2) {
            throw new IllegalArgumentException("剖面至少需要两个深度节点: " + id);
        }
        for (int i = 1; i < depths.size(); i++) {
            if (depths.get(i) <= depths.get(i - 1)) {
                throw new IllegalArgumentException(
                        "剖面深度必须严格递增: " + id + " 节点 " + i);
            }
            if (speeds.get(i - 1) <= 0 || speeds.get(i) <= 0) {
                throw new IllegalArgumentException("声速必须为正: " + id);
            }
        }
        if (validStart > validEnd) {
            throw new IllegalArgumentException("剖面有效时段起止倒置: " + id);
        }
        this.id = id;
        this.name = name;
        this.validStart = validStart;
        this.validEnd = validEnd;
        this.depths = List.copyOf(depths);
        this.speeds = List.copyOf(speeds);
        List<Layer> ls = new ArrayList<>();
        for (int k = 0; k < depths.size() - 1; k++) {
            ls.add(new Layer(k, depths.get(k), depths.get(k + 1), speeds.get(k)));
        }
        this.layers = Collections.unmodifiableList(ls);
    }

    public String id() { return id; }
    public String name() { return name; }
    public long validStart() { return validStart; }
    public long validEnd() { return validEnd; }
    public double maxDepth() { return depths.get(depths.size() - 1); }
    public double surfaceSpeed() { return speeds.get(0); }
    public List<Layer> layers() { return layers; }
    public List<Double> depths() { return depths; }
    public List<Double> speeds() { return speeds; }

    public boolean covers(long tMs) {
        return tMs >= validStart && tMs <= validEnd;
    }
}
