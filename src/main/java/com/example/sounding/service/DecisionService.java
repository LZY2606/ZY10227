package com.example.sounding.service;

import com.example.sounding.domain.AttitudeSample;
import com.example.sounding.domain.AttitudeVersion;
import com.example.sounding.domain.BeamSample;
import com.example.sounding.domain.Ping;
import com.example.sounding.domain.Profile;
import com.example.sounding.domain.RayLayerPass;
import com.example.sounding.domain.RayPoint;
import com.example.sounding.domain.RaySolution;
import com.example.sounding.domain.RaySolver;
import com.example.sounding.domain.SolutionStatus;
import com.example.sounding.repository.DecisionRunRepository;
import com.example.sounding.repository.DecisionRunRepository.Counts;
import com.example.sounding.repository.DecisionRunRepository.PersistedResult;
import com.example.sounding.repository.SoundingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class DecisionService {
    private static final double CROSS_PAIR_MAX_GAP_M = 25.0;

    private final SoundingRepository repository;
    private final DecisionRunRepository runRepository;
    private final RaySolver raySolver;

    public DecisionService(SoundingRepository repository, DecisionRunRepository runRepository) {
        this.repository = repository;
        this.runRepository = runRepository;
        this.raySolver = new RaySolver();
    }

    @Transactional
    public DecisionDtos.DecisionResponse execute(DecisionDtos.DecisionRequest request) {
        Profile profile = repository.findProfile(request.profileId())
                .orElseThrow(() -> new IllegalArgumentException("未知声速剖面: " + request.profileId()));
        AttitudeVersion attitudeVersion = repository.findAttitudeVersion(request.attitudeVersionId())
                .orElseThrow(() -> new IllegalArgumentException("未知姿态版本: " + request.attitudeVersionId()));
        if (!(request.maxBeamAngleDeg() > 0 && request.maxBeamAngleDeg() <= 90)) {
            throw new IllegalArgumentException("波束角排除阈值必须在 0 到 90 度之间");
        }

        List<BeamSample> beams = repository.findBeamSamples();
        Map<String, Ping> pings = repository.findPingMap();
        List<AttitudeSample> attitude = repository.findAttitudeSamples();
        List<PersistedResult> results = new ArrayList<>();
        CountsBuilder counts = new CountsBuilder(beams.size());

        for (BeamSample beam : beams) {
            Ping ping = pings.get(beam.pingId());
            if (ping == null) {
                results.add(PersistedResult.failure(beam.id(), beam.pingId(),
                        SolutionStatus.INVALID_INPUT, profile.id(), attitudeVersion.id(),
                        beam.launchAngleDeg(), raySolverTolerance(), beam.twoWayTimeS(), ""));
                continue;
            }
            Instant attitudeTime = ping.eventTime().plusMillis(attitudeVersion.delayMillis());
            Optional<AttitudeInterpolation> roll = interpolate(attitude, attitudeTime);
            if (roll.isEmpty()) {
                results.add(PersistedResult.failure(beam.id(), ping.id(),
                        SolutionStatus.ATTITUDE_UNSUPPORTED, profile.id(), attitudeVersion.id(),
                        beam.launchAngleDeg(), raySolverTolerance(), beam.twoWayTimeS(), ""));
                continue;
            }
            double correctedAngle = beam.launchAngleDeg() + roll.get().rollDeg();
            if (Math.abs(correctedAngle) > request.maxBeamAngleDeg() + 1.0e-10) {
                results.add(PersistedResult.failure(beam.id(), ping.id(),
                        SolutionStatus.EXCLUDED_BEAM_ANGLE, profile.id(), attitudeVersion.id(),
                        correctedAngle, raySolverTolerance(), beam.twoWayTimeS(),
                        roll.get().sampleIds()));
                continue;
            }
            boolean insideProfilePeriod = !ping.eventTime().isBefore(profile.validFrom())
                    && !ping.eventTime().isAfter(profile.validTo());
            if (!insideProfilePeriod) {
                results.add(PersistedResult.failure(beam.id(), ping.id(),
                        SolutionStatus.PROFILE_GAP, profile.id(), attitudeVersion.id(),
                        correctedAngle, raySolverTolerance(), beam.twoWayTimeS(),
                        roll.get().sampleIds()));
                continue;
            }
            RaySolution solution = raySolver.solve(profile, correctedAngle, beam.twoWayTimeS());
            results.add(toPersisted(beam, ping, solution, profile.id(), attitudeVersion.id(),
                    roll.get().sampleIds()));
        }

        results.forEach(result -> counts.add(result.status()));
        Instant createdAt = Instant.now();
        String parametersHash = sha256(profile.id() + "|" + attitudeVersion.id() + "|"
                + request.maxBeamAngleDeg());
        long runId = runRepository.insertRun(createdAt, profile.id(), attitudeVersion.id(),
                request.maxBeamAngleDeg(), counts.build(), parametersHash);
        List<Long> resultIds = new ArrayList<>();
        for (PersistedResult result : results) {
            resultIds.add(runRepository.insertResult(runId, result));
        }
        saveCrossDifferences(runId, results, resultIds);
        return loadRun(runId);
    }

    public DecisionDtos.DecisionResponse loadRun(long runId) {
        Map<String, Object> runRow = repository.findRun(runId)
                .orElseThrow(() -> new IllegalArgumentException("未知裁决运行: " + runId));
        DecisionDtos.RunSummary run = toRunSummary(runRow);
        List<Map<String, Object>> rows = repository.findResultRows(runId);
        List<DecisionDtos.ResultRecord> resultRecords = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            resultRecords.add(toResultRecord(row));
        }
        List<DecisionDtos.CrossDifferenceRecord> cross = repository.findCrossRows(runId).stream()
                .map(row -> new DecisionDtos.CrossDifferenceRecord(
                        ((Number) row.get("id")).longValue(),
                        (String) row.get("left_sample"),
                        (String) row.get("right_sample"),
                        (String) row.get("left_ping"),
                        (String) row.get("right_ping"),
                        ((Number) row.get("east_gap_m")).doubleValue(),
                        ((Number) row.get("depth_delta_m")).doubleValue()))
                .toList();
        return new DecisionDtos.DecisionResponse(run, List.copyOf(resultRecords), cross);
    }

    public DecisionDtos.IndexData indexData() {
        List<DecisionDtos.ProfileRecord> profiles = repository.findProfiles().stream()
                .map(profile -> new DecisionDtos.ProfileRecord(
                        profile.id(), profile.name(), profile.validFrom().toString(),
                        profile.validTo().toString(), profile.maxDepth(),
                        profile.layers().stream()
                                .map(layer -> new DecisionDtos.LayerRecord(layer.ordinal(),
                                        layer.id(), layer.depthTop(), layer.depthBottom(),
                                        layer.speedTop(), layer.speedBottom()))
                                .toList()))
                .toList();
        List<DecisionDtos.AttitudeVersionRecord> versions = repository.findAttitudeVersions().stream()
                .map(version -> new DecisionDtos.AttitudeVersionRecord(version.id(),
                        version.name(), version.delayMillis()))
                .toList();
        List<DecisionDtos.RunSummary> runs = repository.findRuns().stream()
                .map(this::toRunSummary)
                .toList();
        DecisionDtos.DecisionResponse latest = runs.isEmpty() ? null
                : loadRun(runs.get(runs.size() - 1).id());
        return new DecisionDtos.IndexData(profiles, versions, runs, latest);
    }

    private void saveCrossDifferences(long runId, List<PersistedResult> results, List<Long> ids) {
        List<PositionedResult> valid = new ArrayList<>();
        for (int index = 0; index < results.size(); index++) {
            PersistedResult result = results.get(index);
            if (result.status() == SolutionStatus.VALID) {
                valid.add(new PositionedResult(ids.get(index), result.beamSampleId(),
                        result.pingId(), result.eastM(), result.depthM()));
            }
        }
        for (int leftIndex = 0; leftIndex < valid.size(); leftIndex++) {
            for (int rightIndex = leftIndex + 1; rightIndex < valid.size(); rightIndex++) {
                PositionedResult left = valid.get(leftIndex);
                PositionedResult right = valid.get(rightIndex);
                if (left.pingId().equals(right.pingId())) {
                    continue;
                }
                double gap = Math.abs(left.eastM() - right.eastM());
                if (gap <= CROSS_PAIR_MAX_GAP_M) {
                    runRepository.insertCrossDifference(runId, left.resultId(), right.resultId(),
                            gap, right.depthM() - left.depthM());
                }
            }
        }
    }

    private PersistedResult toPersisted(BeamSample beam, Ping ping, RaySolution solution,
                                        String profileId, String attitudeVersionId,
                                        String attitudeSampleIds) {
        Double depth = solution.depthM();
        Double east = null;
        if (depth != null && solution.acrossM() != null) {
            east = ping.eastM() + solution.acrossM();
        }
        List<String> layerIds = solution.layerPasses().stream()
                .map(RayLayerPass::layerId)
                .toList();
        return new PersistedResult(beam.id(), ping.id(), solution.status(),
                "beam:" + beam.id() + ";ping:" + ping.id() + ";line:" + ping.lineId()
                        + ";ping-time:" + ping.eventTime(),
                depth, east, ping.northM(), solution.correctedAngleDeg(),
                Double.isNaN(solution.residualTimeS()) ? null : solution.residualTimeS(),
                solution.numericalTolerance(), solution.iterations() == 0 ? null : solution.iterations(),
                layerIds, solution, attitudeSampleIds, profileId, attitudeVersionId,
                beam.twoWayTimeS());
    }

    private Optional<AttitudeInterpolation> interpolate(List<AttitudeSample> samples, Instant time) {
        List<AttitudeSample> ordered = samples.stream()
                .sorted(Comparator.comparing(AttitudeSample::eventTime))
                .toList();
        for (int index = 0; index < ordered.size(); index++) {
            AttitudeSample current = ordered.get(index);
            if (current.eventTime().equals(time)) {
                return Optional.of(new AttitudeInterpolation(current.rollDeg(), current.id()));
            }
            if (index + 1 < ordered.size()) {
                AttitudeSample next = ordered.get(index + 1);
                if (current.eventTime().isBefore(time) && next.eventTime().isAfter(time)) {
                    long spanMillis = java.time.Duration.between(current.eventTime(),
                            next.eventTime()).toMillis();
                    double fraction = (double) java.time.Duration.between(current.eventTime(), time).toMillis()
                            / spanMillis;
                    double roll = current.rollDeg()
                            + fraction * (next.rollDeg() - current.rollDeg());
                    return Optional.of(new AttitudeInterpolation(roll,
                            current.id() + "+" + next.id()));
                }
            }
        }
        return Optional.empty();
    }

    private DecisionDtos.ResultRecord toResultRecord(Map<String, Object> row) {
        String layerText = (String) row.get("layer_ids");
        List<String> layerIds = layerText == null || layerText.isBlank()
                ? List.of() : List.of(layerText.split(";"));
        long resultId = ((Number) row.get("id")).longValue();
        List<DecisionDtos.VertexRecord> vertices = repository.findVertices(resultId).stream()
                .map(vertex -> new DecisionDtos.VertexRecord(
                        ((Number) vertex.get("vertex_order")).intValue(),
                        ((Number) vertex.get("across_m")).doubleValue(),
                        ((Number) vertex.get("depth_m")).doubleValue(),
                        (String) vertex.get("owning_layer_id")))
                .toList();
        return new DecisionDtos.ResultRecord(
                resultId,
                (String) row.get("beam_sample_id"),
                (String) row.get("ping_id"),
                (String) row.get("line_id"),
                (String) row.get("ping_time"),
                ((Number) row.get("ping_east_m")).doubleValue(),
                ((Number) row.get("ping_north_m")).doubleValue(),
                ((Number) row.get("beam_index")).intValue(),
                ((Number) row.get("launch_angle_deg")).doubleValue(),
                (String) row.get("status"),
                doubleValue(row.get("depth_m")),
                doubleValue(row.get("east_m")),
                doubleValue(row.get("north_m")),
                doubleValue(row.get("corrected_angle_deg")),
                doubleValue(row.get("residual_time_s")),
                doubleValue(row.get("numerical_tolerance")),
                integerValue(row.get("iterations")),
                layerIds,
                vertices,
                (String) row.get("attitude_input_sample_ids"),
                (String) row.get("profile_id"),
                (String) row.get("attitude_version_id"),
                ((Number) row.get("input_two_way_time_s")).doubleValue());
    }

    private DecisionDtos.RunSummary toRunSummary(Map<String, Object> row) {
        return new DecisionDtos.RunSummary(
                ((Number) row.get("id")).longValue(),
                (String) row.get("created_at"),
                (String) row.get("profile_id"),
                (String) row.get("attitude_version_id"),
                ((Number) row.get("max_beam_angle_deg")).doubleValue(),
                ((Number) row.get("total_samples")).intValue(),
                ((Number) row.get("valid_points")).intValue(),
                ((Number) row.get("excluded_points")).intValue(),
                ((Number) row.get("failed_points")).intValue(),
                ((Number) row.get("profile_gap_points")).intValue(),
                (String) row.get("parameters_hash"));
    }

    private static Double doubleValue(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }

    private static Integer integerValue(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private double raySolverTolerance() {
        return 1.0e-10;
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class CountsBuilder {
        private final int total;
        private int valid;
        private int excluded;
        private int failed;
        private int profileGap;

        private CountsBuilder(int total) {
            this.total = total;
        }

        private void add(SolutionStatus status) {
            switch (status) {
                case VALID -> valid++;
                case EXCLUDED_BEAM_ANGLE -> excluded++;
                case PROFILE_GAP -> profileGap++;
                default -> failed++;
            }
        }

        private Counts build() {
            return new Counts(total, valid, excluded, failed, profileGap);
        }
    }

    private record AttitudeInterpolation(double rollDeg, String sampleIds) {
    }

    private record PositionedResult(long resultId, String beamSampleId, String pingId,
                                    double eastM, double depthM) {
    }
}
