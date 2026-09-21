package com.example.svp.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdjudicationService {

    public static final String OK = "OK";
    public static final String EXCLUDED_ANGLE = "EXCLUDED_ANGLE";
    public static final String NO_PROFILE = "NO_PROFILE";
    public static final String NO_ATTITUDE = "NO_ATTITUDE";
    public static final String SOLVE_FAILED = "SOLVE_FAILED";

    private final JdbcTemplate jdbc;
    private final DataRepository repo;
    private final RayTracer tracer = new RayTracer();
    private final ObjectMapper mapper = new ObjectMapper();

    public AdjudicationService(JdbcTemplate jdbc, DataRepository repo) {
        this.jdbc = jdbc;
        this.repo = repo;
    }

    public record Window(String profileId, long start, long end) {}

    @Transactional
    public long createRun(String label, long attitudeDelayMs, double maxBeamAngle,
                          double tolSec, List<Window> windows) {
        validateWindows(windows);
        String safeLabel = label == null || label.isBlank()
                ? ("方案 " + attitudeDelayMs + "ms") : label;
        jdbc.update("INSERT INTO run_config(label,attitude_delay_ms,max_beam_angle,tol_sec,created_at) "
                        + "VALUES (?,?,?,?,?)",
                safeLabel, attitudeDelayMs, maxBeamAngle, tolSec, Instant.now().toString());
        long runId = jdbc.queryForObject("SELECT last_insert_rowid()", Number.class).longValue();
        for (Window w : windows) {
            jdbc.update("INSERT INTO run_profile_window(run_id,profile_id,win_start,win_end) "
                            + "VALUES (?,?,?,?)", runId, w.profileId(), w.start(), w.end());
        }
        compute(runId, attitudeDelayMs, maxBeamAngle, tolSec, windows);
        return runId;
    }

    private void validateWindows(List<Window> windows) {
        List<Window> sorted = windows.stream().sorted((a, b) -> Long.compare(a.start(), b.start())).toList();
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).start() <= sorted.get(i - 1).end()) {
                throw new IllegalArgumentException(
                        "剖面有效时段不得重叠或跨越空档: "
                                + sorted.get(i - 1).profileId() + " 与 " + sorted.get(i).profileId());
            }
        }
        for (Window w : windows) {
            if (w.start() > w.end()) {
                throw new IllegalArgumentException("剖面时段起止倒置: " + w.profileId());
            }
        }
    }

    private void compute(long runId, long delayMs, double maxAngle, double tol, List<Window> windows) {
        for (String line : repo.lines()) {
            AttitudeInterpolator att = new AttitudeInterpolator(repo.attitude(line), delayMs);
            for (DataRepository.BeamRow beam : repo.beams(line)) {
                adjudicate(runId, beam, att, maxAngle, tol, windows);
            }
        }
        computeCrossovers(runId);
    }

    private void adjudicate(long runId, DataRepository.BeamRow beam,
                            AttitudeInterpolator att, double maxAngle,
                            double tol, List<Window> windows) {
        AttitudeInterpolator.Attitude a = att.at(beam.pingT());
        if (a == null) {
            insert(runId, beam, null, null, null, null, null, null, null,
                    null, NO_ATTITUDE, "ATTITUDE_GAP",
                    "姿态锚点不覆盖 t=" + beam.pingT() + "（延迟 " + att.version() + "），不外推");
            return;
        }
        double theta = beam.mountAngle() + a.roll;
        Window w = pickWindow(beam.pingT(), windows);
        if (w == null) {
            insert(runId, beam, null, a, beam.mountAngle(), theta, null, null, null,
                    null, NO_PROFILE, null,
                    "t=" + beam.pingT() + " 位于剖面时段空档，不跨越插值");
            return;
        }
        if (Math.abs(theta) > maxAngle + 1e-9) {
            insert(runId, beam, w.profileId(), a, beam.mountAngle(), theta, null, null,
                    null, null, EXCLUDED_ANGLE, null,
                    "水中角 " + String.format("%.2f", theta) + "° 超出波束角限制 "
                            + maxAngle + "°，样本排除");
            return;
        }
        SvpProfile profile = loadProfile(w.profileId());
        try {
            RayTracer.SolveResult r = tracer.solveDepth(
                    profile, theta, beam.twtt() / 2.0, tol);
            String layersJson = mapper.writeValueAsString(layersPayload(r.segments));
            insert(runId, beam, w.profileId(), a, beam.mountAngle(), theta,
                    r.depth, r.horizontal, r.residualSec, layersJson,
                    OK, null, "积分容差 " + tol + " s");
        } catch (RayTracer.Failure f) {
            insert(runId, beam, w.profileId(), a, beam.mountAngle(), theta, null, null,
                    null, null, SOLVE_FAILED, f.code, f.getMessage());
        } catch (Exception e) {
            throw new IllegalStateException("裁决序列化失败", e);
        }
    }

    private Window pickWindow(long t, List<Window> windows) {
        for (Window w : windows) {
            if (t >= w.start() && t <= w.end()) {
                return w;
            }
        }
        return null;
    }

    private SvpProfile loadProfile(String profileId) {
        DataRepository.ProfileRow pr = repo.profiles().stream()
                .filter(p -> p.id().equals(profileId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知剖面 " + profileId));
        List<DataRepository.NodeRow> nodes = repo.nodes(profileId);
        List<Double> depths = nodes.stream().map(DataRepository.NodeRow::depth).toList();
        List<Double> speeds = nodes.stream().map(DataRepository.NodeRow::c).toList();
        return new SvpProfile(pr.id(), pr.name(), pr.validStart(), pr.validEnd(),
                depths, speeds);
    }

    private List<Map<String, Object>> layersPayload(List<RayTracer.Segment> segs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RayTracer.Segment s : segs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("layer", s.layerIndex);
            m.put("z0", round(s.z0));
            m.put("z1", round(s.z1));
            m.put("c", s.speed);
            m.put("thetaDeg", round(Math.toDegrees(s.thetaRad)));
            m.put("dt", s.dt);
            m.put("dx", round(s.dx));
            out.add(m);
        }
        return out;
    }

    private void insert(long runId, DataRepository.BeamRow b, String profileId,
                        AttitudeInterpolator.Attitude a, Double mount, Double theta,
                        Double depth, Double across, Double residual, String layersJson,
                        String status, String failureCode, String message) {
        jdbc.update("""
                        INSERT INTO sounding(run_id,beam_sample_id,line,ping_t,beam_idx,
                          profile_id,attitude_version,mount_angle,in_water_angle,roll,
                          depth,across,residual_sec,layers_json,status,failure_code,
                          attitude_anchors,message)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                runId, b.id(), b.line(), b.pingT(), b.beamIdx(),
                profileId, a == null ? null : a.version, mount, theta,
                a == null ? null : a.roll, depth, across, residual, layersJson,
                status, failureCode, a == null ? null : a.anchors, message);
    }

    private void computeCrossovers(long runId) {
        Long ct = jdbc.query("SELECT v FROM fixture_meta WHERE k='crossing.pingT'",
                rs -> rs.next() ? Long.valueOf(rs.getLong(1)) : null);
        String line1 = jdbc.query("SELECT v FROM fixture_meta WHERE k='crossing.line1'",
                rs -> rs.next() ? rs.getString(1) : "L1");
        String line2 = jdbc.query("SELECT v FROM fixture_meta WHERE k='crossing.line2'",
                rs -> rs.next() ? rs.getString(1) : "L2");
        if (ct == null) {
            jdbc.update("""
                            INSERT INTO crossover(run_id,line1,line2,ping_t,status,message)
                            VALUES (?,?,?,?,?,?)""",
                    runId, line1, line2, -1, "NO_CROSSING",
                    "fixture 未指定交叉点 ping");
            return;
        }
        long pingT = ct;
        Double z1 = interpolateDepthAtZero(runId, line1, pingT);
        Double z2 = interpolateDepthAtZero(runId, line2, pingT);
        if (z1 == null || z2 == null) {
            jdbc.update("""
                            INSERT INTO crossover(run_id,line1,line2,ping_t,z1,z2,status,message)
                            VALUES (?,?,?,?,?,?,?,?)""",
                    runId, line1, line2, pingT, z1, z2, "INSUFFICIENT_BEAMS",
                    "交叉点跨轨零点两侧缺少可用波束");
            return;
        }
        jdbc.update("""
                        INSERT INTO crossover(run_id,line1,line2,ping_t,z1,z2,diff_m,status,message)
                        VALUES (?,?,?,?,?,?,?,?,?)""",
                runId, line1, line2, pingT, z1, z2, z1 - z2, "OK",
                "跨轨零点线性插值深度差");
    }

    private Double interpolateDepthAtZero(long runId, String line, long pingT) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT across,depth FROM sounding WHERE run_id=? AND line=? AND ping_t=? "
                        + "AND status='OK' ORDER BY across",
                runId, line, pingT);
        if (rows.isEmpty()) {
            return null;
        }
        for (int i = 0; i < rows.size() - 1; i++) {
            double x0 = ((Number) rows.get(i).get("across")).doubleValue();
            double x1 = ((Number) rows.get(i + 1).get("across")).doubleValue();
            double z0 = ((Number) rows.get(i).get("depth")).doubleValue();
            double z1 = ((Number) rows.get(i + 1).get("depth")).doubleValue();
            if (x0 <= 0.0 && x1 >= 0.0) {
                double w = x1 == x0 ? 0 : -x0 / (x1 - x0);
                return z0 + w * (z1 - z0);
            }
        }
        return null;
    }

    private static double round(double v) {
        return Math.round(v * 1e6) / 1e6;
    }
}
