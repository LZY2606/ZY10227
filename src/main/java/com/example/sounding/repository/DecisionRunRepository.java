package com.example.sounding.repository;

import com.example.sounding.domain.RayLayerPass;
import com.example.sounding.domain.RayPoint;
import com.example.sounding.domain.RaySolution;
import com.example.sounding.domain.SolutionStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
public class DecisionRunRepository {
    private final JdbcTemplate jdbcTemplate;

    public DecisionRunRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public long insertRun(Instant createdAt, String profileId, String attitudeVersionId,
                          double maxBeamAngleDeg, Counts counts, String parametersHash) {
        jdbcTemplate.update("""
                INSERT INTO decision_run(created_at,profile_id,attitude_version_id,max_beam_angle_deg,
                total_samples,valid_points,excluded_points,failed_points,profile_gap_points,parameters_hash)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, createdAt.toString(), profileId, attitudeVersionId, maxBeamAngleDeg,
                counts.total(), counts.valid(), counts.excluded(), counts.failed(),
                counts.profileGap(), parametersHash);
        return jdbcTemplate.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    @Transactional
    public long insertResult(long runId, PersistedResult result) {
        jdbcTemplate.update("""
                INSERT INTO sounding_result(run_id,beam_sample_id,ping_id,status,input_sample_identity,
                depth_m,east_m,north_m,corrected_angle_deg,residual_time_s,numerical_tolerance,
                iterations,layer_ids,attitude_input_sample_ids,profile_id,attitude_version_id,
                input_two_way_time_s)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, runId, result.beamSampleId(), result.pingId(), result.status().name(),
                result.inputIdentity(), nullable(result.depthM()), nullable(result.eastM()),
                nullable(result.northM()), nullable(result.correctedAngleDeg()),
                nullable(result.residualTimeS()), nullable(result.numericalTolerance()),
                result.iterations() == null ? null : result.iterations(),
                String.join(";", result.layerIds()), result.attitudeInputSampleIds(),
                result.profileId(), result.attitudeVersionId(), result.inputTwoWayTimeS());
        long resultId = jdbcTemplate.queryForObject("SELECT last_insert_rowid()", Long.class);
        for (RayPoint point : result.solution().points()) {
            jdbcTemplate.update("""
                    INSERT INTO ray_vertex(result_id,vertex_order,across_m,depth_m,owning_layer_id)
                    VALUES (?,?,?,?,?)
                    """, resultId, point.order(), point.acrossM(), point.depthM(),
                    point.owningLayerId());
        }
        return resultId;
    }

    @Transactional
    public void insertCrossDifference(long runId, long leftId, long rightId, double eastGap,
                                      double depthDelta) {
        jdbcTemplate.update("""
                INSERT INTO cross_difference(run_id,left_result_id,right_result_id,east_gap_m,depth_delta_m)
                VALUES (?,?,?,?,?)
                """, runId, leftId, rightId, eastGap, depthDelta);
    }

    private Double nullable(Double value) {
        return value == null || Double.isNaN(value) ? null : value;
    }

    public record Counts(int total, int valid, int excluded, int failed, int profileGap) {
    }

    public record PersistedResult(
            String beamSampleId,
            String pingId,
            SolutionStatus status,
            String inputIdentity,
            Double depthM,
            Double eastM,
            Double northM,
            Double correctedAngleDeg,
            Double residualTimeS,
            Double numericalTolerance,
            Integer iterations,
            List<String> layerIds,
            RaySolution solution,
            String attitudeInputSampleIds,
            String profileId,
            String attitudeVersionId,
            double inputTwoWayTimeS
    ) {
        public static PersistedResult failure(String beamSampleId, String pingId, SolutionStatus status,
                                              String profileId, String attitudeVersionId,
                                              Double correctedAngleDeg, double tolerance,
                                              double inputTwoWayTimeS,
                                              String attitudeInputSampleIds) {
            return new PersistedResult(beamSampleId, pingId, status,
                    "beam:" + beamSampleId + ";ping:" + pingId, null, null, null, correctedAngleDeg,
                    null, tolerance, null, List.of(),
                    com.example.sounding.domain.RaySolution.failure(status,
                            correctedAngleDeg == null ? Double.NaN : correctedAngleDeg, tolerance),
                    attitudeInputSampleIds, profileId, attitudeVersionId, inputTwoWayTimeS);
        }
    }
}

