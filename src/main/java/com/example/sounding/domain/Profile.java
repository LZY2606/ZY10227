package com.example.sounding.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record Profile(
        String id,
        String name,
        Instant validFrom,
        Instant validTo,
        List<SoundSpeedLayer> layers
) {
    public Profile {
        Objects.requireNonNull(id, "profile id");
        Objects.requireNonNull(validFrom, "validFrom");
        Objects.requireNonNull(validTo, "validTo");
        if (!validFrom.isBefore(validTo)) {
            throw new IllegalArgumentException("剖面有效时段必须非空: " + id);
        }
        layers = List.copyOf(layers);
        validateLayers(layers);
    }

    public static void validateLayers(List<SoundSpeedLayer> layers) {
        if (layers == null || layers.isEmpty()) {
            throw new IllegalArgumentException("声速剖面至少包含一层");
        }
        List<SoundSpeedLayer> ordered = layers.stream()
                .sorted(Comparator.comparingInt(SoundSpeedLayer::ordinal))
                .toList();
        double previousBottom = ordered.get(0).depthTop();
        for (SoundSpeedLayer layer : ordered) {
            if (layer.depthTop() >= layer.depthBottom()) {
                throw new IllegalArgumentException("分层深度必须严格递增: " + layer.id());
            }
            if (Double.compare(previousBottom, layer.depthTop()) != 0) {
                throw new IllegalArgumentException("分层必须首尾相接: " + layer.id());
            }
            if (layer.speedTop() <= 0 || layer.speedBottom() <= 0) {
                throw new IllegalArgumentException("声速必须为正: " + layer.id());
            }
            previousBottom = layer.depthBottom();
        }
    }

    public double maxDepth() {
        return layers.get(layers.size() - 1).depthBottom();
    }
}

