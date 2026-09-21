package com.example.svp.core;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class DataRepository {

    public record ProfileRow(String id, String name, long validStart, long validEnd,
                             Long winStart, Long winEnd, int ord) {}

    public record NodeRow(int ord, double depth, double c) {}

    public record AttitudeRow(String id, String line, long t, double roll,
                              double pitch, double heading, int ord) {}

    public record BeamRow(String id, String line, long pingT, int beamIdx,
                          double mountAngle, double twtt) {}

    private final JdbcTemplate jdbc;

    public DataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ProfileRow> profiles() {
        return jdbc.query("SELECT id,name,valid_start,valid_end,win_start,win_end,ord "
                        + "FROM svp_profile ORDER BY ord",
                (rs, i) -> new ProfileRow(rs.getString(1), rs.getString(2),
                        rs.getLong(3), rs.getLong(4),
                        rs.getLong(5), rs.getLong(6), rs.getInt(7)));
    }

    public List<NodeRow> nodes(String profileId) {
        return jdbc.query("SELECT ord,depth,c FROM svp_node WHERE profile_id=? ORDER BY ord",
                (rs, i) -> new NodeRow(rs.getInt(1), rs.getDouble(2), rs.getDouble(3)),
                profileId);
    }

    public List<AttitudeRow> attitude(String line) {
        return jdbc.query("SELECT id,line,t,roll,pitch,heading,ord FROM attitude_sample "
                        + "WHERE line=? ORDER BY t",
                (rs, i) -> new AttitudeRow(rs.getString(1), rs.getString(2), rs.getLong(3),
                        rs.getDouble(4), rs.getDouble(5), rs.getDouble(6), rs.getInt(7)),
                line);
    }

    public List<BeamRow> beams(String line) {
        return jdbc.query("SELECT id,line,ping_t,beam_idx,mount_angle,twtt "
                        + "FROM beam_sample WHERE line=? ORDER BY ping_t,beam_idx",
                (rs, i) -> new BeamRow(rs.getString(1), rs.getString(2), rs.getLong(3),
                        rs.getInt(4), rs.getDouble(5), rs.getDouble(6)),
                line);
    }

    public List<String> lines() {
        return jdbc.queryForList("SELECT DISTINCT line FROM beam_sample ORDER BY line",
                String.class);
    }

    public void updateProfileWindow(String profileId, Long start, Long end) {
        jdbc.update("UPDATE svp_profile SET win_start=?, win_end=? WHERE id=?",
                start, end, profileId);
    }
}
