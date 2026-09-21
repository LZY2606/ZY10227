package com.example.sounding.service;

import java.util.List;

public final class DecisionDtos {
    private DecisionDtos() {
    }

    public record DecisionRequest(
            String profileId,
            String attitudeVersionId,
            double maxBeamAngleDeg
    ) {
    }

    public record RunSummary(
            long id,
            String createdAt,
            String profileId,
            String attitudeVersionId,
            double maxBeamAngleDeg,
            int totalSamples,
            int validPoints,
            int excludedPoints,
            int failedPoints,
            int profileGapPoints,
            String parametersHash
    ) {
    }

    public record VertexRecord(
            int order,
            double acrossM,
            double depthM,
            String owningLayerId
    ) {
    }

    public record ResultRecord(
            long id,
            String beamSampleId,
            String pingId,
            String lineId,
            String pingTime,
            double pingEastM,
            double pingNorthM,
            int beamIndex,
            double launchAngleDeg,
            String status,
            Double depthM,
            Double eastM,
            Double northM,
            Double correctedAngleDeg,
            Double residualTimeS,
            Double numericalTolerance,
            Integer iterations,
            List<String> layerIds,
            List<VertexRecord> vertices,
            String attitudeInputSampleIds,
            String profileId,
            String attitudeVersionId,
            double inputTwoWayTimeS
    ) {
    }

    public record CrossDifferenceRecord(
            long id,
            String leftSampleId,
            String rightSampleId,
            String leftPingId,
            String rightPingId,
            double eastGapM,
            double depthDeltaM
    ) {
    }

    public record DecisionResponse(
            RunSummary run,
            List<ResultRecord> results,
            List<CrossDifferenceRecord> crossDifferences
    ) {
    }

    public record ProfileRecord(
            String id,
            String name,
            String validFrom,
            String validTo,
            double maxDepth,
            List<LayerRecord> layers
    ) {
    }

    public record LayerRecord(
            int ordinal,
            String id,
            double depthTop,
            double depthBottom,
            double speedTop,
            double speedBottom
    ) {
    }

    public record AttitudeVersionRecord(
            String id,
            String name,
            long delayMillis
    ) {
    }

    public record IndexData(
            List<ProfileRecord> profiles,
            List<AttitudeVersionRecord> attitudeVersions,
            List<RunSummary> runs,
            DecisionResponse latestRun
    ) {
    }
}

