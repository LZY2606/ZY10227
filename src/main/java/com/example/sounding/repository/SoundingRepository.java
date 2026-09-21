package com.example.sounding.repository;

import com.example.sounding.domain.AttitudeSample;
import com.example.sounding.domain.AttitudeVersion;
import com.example.sounding.domain.BeamSample;
import com.example.sounding.domain.Ping;
import com.example.sounding.domain.Profile;
import com.example.sounding.domain.RayLayerPass;
import com.example.sounding.domain.RayPoint;
import com.example.sounding.domain.RaySolution;
import com.example.sounding.domain.SolutionStatus;
import com.example.sounding.domain.SoundSpeedLayer;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class SoundingRepository {
    private final JdbcTemplate jdbcTemplate;

    public SoundingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Profile> findProfiles() {
        List<SoundSpeedLayer> layers = jdbcTemplate.query("""
                SELECT profile_id, ordinal, depth_top, depth_bottom, speed_top, speed_bottom
                FROM sound_speed_layer ORDER BY profile_id, ordinal
                """, (rs, row) -> new SoundSpeedLayer(
                rs.getString("profile_id"),
                rs.getString("profile_id") + "-L" + rs.getInt("ordinal"),
                rs.getInt("ordinal"),
                rs.getDouble("depth_top"),
                rs.getDouble("depth_bottom"),
                rs.getDouble("speed_top"),
                rs.getDouble("speed_bottom")));
        Map<String, List<SoundSpeedLayer>> layersByProfile = new LinkedHashMap<>();
        layers.forEach(layer -> layersByProfile.computeIfAbsent(layer.profileId(), ignored -> new ArrayList<>()).add(layer));
        return jdbcTemplate.query("""
                SELECT id, name, valid_from, valid_to FROM sound_speed_profile ORDER BY valid_from
                """, (rs, row) -> new Profile(
                rs.getString("id"),
                rs.getString("name"),
                Instant.parse(rs.getString("valid_from")),
                Instant.parse(rs.getString("valid_to")),
                layersByProfile.getOrDefault(rs.getString("id"), List.of())));
    }

    public Optional<Profile> findProfile(String id) {
        return findProfiles().stream().filter(profile -> profile.id().equals(id)).findFirst();
    }

    public List<AttitudeVersion> findAttitudeVersions() {
        return jdbcTemplate.query("""
                SELECT id, name, delay_millis FROM attitude_version ORDER BY delay_millis
                """, (rs, row) -> new AttitudeVersion(
                rs.getString("id"), rs.getString("name"), rs.getLong("delay_millis")));
    }

    public Optional<AttitudeVersion> findAttitudeVersion(String id) {
        return jdbcTemplate.query("""
                SELECT id, name, delay_millis FROM attitude_version WHERE id = ?
                """, ps -> ps.setString(1, id), rs -> rs.next()
                ? Optional.of(new AttitudeVersion(rs.getString("id"), rs.getString("name"),
                rs.getLong("delay_millis")))
                : Optional.empty());
    }

    public List<AttitudeSample> findAttitudeSamples() {
        return jdbcTemplate.query("""
                SELECT id, event_time, roll_deg, pitch_deg, heave_m
                FROM attitude_sample ORDER BY event_time
                """, (rs, row) -> new AttitudeSample(
                rs.getString("id"), Instant.parse(rs.getString("event_time")),
                rs.getDouble("roll_deg"), rs.getDouble("pitch_deg"), rs.getDouble("heave_m")));
    }

    public List<Ping> findPings() {
        return jdbcTemplate.query("""
                SELECT id, line_id, event_time, east_m, north_m FROM ping ORDER BY event_time, id
                """, (rs, row) -> new Ping(rs.getString("id"), rs.getString("line_id"),
                Instant.parse(rs.getString("event_time")), rs.getDouble("east_m"),
                rs.getDouble("north_m")));
    }

    public Map<String, Ping> findPingMap() {
        Map<String, Ping> result = new LinkedHashMap<>();
        findPings().forEach(ping -> result.put(ping.id(), ping));
        return result;
    }

    public List<BeamSample> findBeamSamples() {
        return jdbcTemplate.query("""
                SELECT id, ping_id, beam_index, launch_angle_deg, two_way_time_s
                FROM beam_sample ORDER BY ping_id, beam_index
                """, (rs, row) -> new BeamSample(rs.getString("id"), rs.getString("ping_id"),
                rs.getInt("beam_index"), rs.getDouble("launch_angle_deg"),
                rs.getDouble("two_way_time_s")));
    }

    @Transactional
    public void replaceFixtures(List<Profile> profiles, List<AttitudeVersion> versions,
                                List<AttitudeSample> attitudeSamples, List<Ping> pings,
                                List<BeamSample> beamSamples) {
        deleteAllData();
        profiles.forEach(profile -> {
            jdbcTemplate.update("INSERT INTO sound_speed_profile(id,name,valid_from,valid_to) VALUES (?,?,?,?)",
                    profile.id(), profile.name(), profile.validFrom().toString(), profile.validTo().toString());
            profile.layers().forEach(layer -> jdbcTemplate.update("""
                    INSERT INTO sound_speed_layer(profile_id,ordinal,depth_top,depth_bottom,speed_top,speed_bottom)
                    VALUES (?,?,?,?,?,?)
                    """, layer.profileId(), layer.ordinal(), layer.depthTop(), layer.depthBottom(),
                    layer.speedTop(), layer.speedBottom()));
        });
        versions.forEach(version -> jdbcTemplate.update(
                "INSERT INTO attitude_version(id,name,delay_millis) VALUES (?,?,?)",
                version.id(), version.name(), version.delayMillis()));
        attitudeSamples.forEach(sample -> jdbcTemplate.update("""
                INSERT INTO attitude_sample(id,event_time,roll_deg,pitch_deg,heave_m)
                VALUES (?,?,?,?,?)
                """, sample.id(), sample.eventTime().toString(), sample.rollDeg(),
                sample.pitchDeg(), sample.heaveM()));
        pings.forEach(ping -> jdbcTemplate.update(
                "INSERT INTO ping(id,line_id,event_time,east_m,north_m) VALUES (?,?,?,?,?)",
                ping.id(), ping.lineId(), ping.eventTime().toString(), ping.eastM(), ping.northM()));
        beamSamples.forEach(sample -> jdbcTemplate.update("""
                INSERT INTO beam_sample(id,ping_id,beam_index,launch_angle_deg,two_way_time_s)
                VALUES (?,?,?,?,?)
                """, sample.id(), sample.pingId(), sample.beamIndex(), sample.launchAngleDeg(),
                sample.twoWayTimeS()));
    }

    @Transactional
    public void deleteAllData() {
        jdbcTemplate.update("DELETE FROM cross_difference");
        jdbcTemplate.update("DELETE FROM ray_vertex");
        jdbcTemplate.update("DELETE FROM sounding_result");
        jdbcTemplate.update("DELETE FROM decision_run");
        jdbcTemplate.update("DELETE FROM beam_sample");
        jdbcTemplate.update("DELETE FROM ping");
        jdbcTemplate.update("DELETE FROM attitude_sample");
        jdbcTemplate.update("DELETE FROM sound_speed_layer");
        jdbcTemplate.update("DELETE FROM sound_speed_profile");
        jdbcTemplate.update("DELETE FROM attitude_version");
    }

    public long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    public java.util.List<java.util.Map<String, Object>> findRuns() {
        return jdbcTemplate.queryForList("""
                SELECT * FROM decision_run ORDER BY id
                """);
    }

    public java.util.Optional<java.util.Map<String, Object>> findRun(long id) {
        return jdbcTemplate.query("SELECT * FROM decision_run WHERE id = ?",
                ps -> ps.setLong(1, id),
                rs -> rs.next() ? java.util.Optional.of(new ColumnMapRowMapper().mapRow(rs, 1))
                        : java.util.Optional.empty());
    }

    public java.util.List<java.util.Map<String, Object>> findResultRows(long runId) {
        return jdbcTemplate.queryForList("""
                SELECT sr.*, p.line_id, p.event_time AS ping_time, p.east_m AS ping_east_m, p.north_m AS ping_north_m,
                       b.beam_index, b.launch_angle_deg
                FROM sounding_result sr
                JOIN ping p ON p.id = sr.ping_id
                JOIN beam_sample b ON b.id = sr.beam_sample_id
                WHERE sr.run_id = ?
                ORDER BY p.event_time, p.id, b.beam_index
                """, runId);
    }

    public java.util.List<java.util.Map<String, Object>> findVertices(long resultId) {
        return jdbcTemplate.queryForList("""
                SELECT vertex_order, across_m, depth_m, owning_layer_id
                FROM ray_vertex WHERE result_id = ? ORDER BY vertex_order
                """, resultId);
    }

    public java.util.List<java.util.Map<String, Object>> findCrossRows(long runId) {
        return jdbcTemplate.queryForList("""
                SELECT cd.id, cd.east_gap_m, cd.depth_delta_m,
                       lb.id AS left_sample, rb.id AS right_sample,
                       lp.id AS left_ping, rp.id AS right_ping
                FROM cross_difference cd
                JOIN sounding_result ls ON ls.id = cd.left_result_id
                JOIN sounding_result rs ON rs.id = cd.right_result_id
                JOIN beam_sample lb ON lb.id = ls.beam_sample_id
                JOIN beam_sample rb ON rb.id = rs.beam_sample_id
                JOIN ping lp ON lp.id = ls.ping_id
                JOIN ping rp ON rp.id = rs.ping_id
                WHERE cd.run_id = ?
                ORDER BY cd.id
                """, runId);
    }
}
