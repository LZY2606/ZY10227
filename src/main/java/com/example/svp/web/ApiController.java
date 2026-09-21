package com.example.svp.web;

import com.example.svp.config.FixtureService;
import com.example.svp.core.AdjudicationService;
import com.example.svp.core.DataRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final JdbcTemplate jdbc;
    private final DataRepository repo;
    private final AdjudicationService adjudication;
    private final FixtureService fixtures;

    public ApiController(JdbcTemplate jdbc, DataRepository repo,
                         AdjudicationService adjudication, FixtureService fixtures) {
        this.jdbc = jdbc;
        this.repo = repo;
        this.adjudication = adjudication;
        this.fixtures = fixtures;
    }

    public record WindowReq(String profileId, Long start, Long end) {}

    public record RunReq(String label, Long attitudeDelayMs, Double maxBeamAngle,
                         Double tolSec, List<WindowReq> windows) {}

    @GetMapping("/state")
    public Map<String, Object> state() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("description", fixtures.bundledFixtureDescription().asText());
        out.put("profiles", jdbc.queryForList("""
                SELECT p.id,p.name,p.valid_start,p.valid_end,p.win_start,p.win_end,p.ord,
                       (SELECT json_group_array(json_object('depth',n.depth,'c',n.c,'ord',n.ord))
                          FROM svp_node n WHERE n.profile_id=p.id) AS nodes
                FROM svp_profile p ORDER BY p.ord"""));
        out.put("attitude", jdbc.queryForList(
                "SELECT id,line,t,roll,pitch,heading,ord FROM attitude_sample ORDER BY line,t"));
        out.put("beamStats", jdbc.queryForList(
                "SELECT line, COUNT(*) n, MIN(ping_t) t0, MAX(ping_t) t1 "
                        + "FROM beam_sample GROUP BY line ORDER BY line"));
        out.put("runs", jdbc.queryForList(
                "SELECT id,label,attitude_delay_ms,max_beam_angle,tol_sec,created_at "
                        + "FROM run_config ORDER BY id"));
        out.put("counts", Map.of(
                "soundings", jdbc.queryForObject("SELECT COUNT(*) FROM sounding", Integer.class),
                "beams", jdbc.queryForObject("SELECT COUNT(*) FROM beam_sample", Integer.class)));
        return out;
    }

    @GetMapping("/runs/{id}")
    public Map<String, Object> run(@PathVariable long id) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run", jdbc.queryForMap(
                "SELECT * FROM run_config WHERE id=?", id));
        out.put("windows", jdbc.queryForList(
                "SELECT profile_id,win_start,win_end FROM run_profile_window WHERE run_id=?", id));
        out.put("soundings", jdbc.queryForList(
                "SELECT * FROM sounding WHERE run_id=? ORDER BY line,ping_t,beam_idx", id));
        out.put("crossovers", jdbc.queryForList(
                "SELECT * FROM crossover WHERE run_id=?", id));
        return out;
    }

    @PostMapping("/runs")
    public Map<String, Object> createRun(@RequestBody RunReq req) {
        long delay = req.attitudeDelayMs() == null ? 0 : req.attitudeDelayMs();
        double maxAngle = req.maxBeamAngle() == null ? 65.0 : req.maxBeamAngle();
        double tol = req.tolSec() == null ? 1e-9 : req.tolSec();
        if (tol <= 0) {
            throw new IllegalArgumentException("积分容差必须为正");
        }
        if (maxAngle <= 0 || maxAngle > 90) {
            throw new IllegalArgumentException("波束角限制需在 (0,90] 度之间");
        }
        List<AdjudicationService.Window> windows = (req.windows() == null ? List.<WindowReq>of() : req.windows())
                .stream()
                .map(w -> new AdjudicationService.Window(w.profileId(), w.start(), w.end()))
                .toList();
        if (windows.isEmpty()) {
            windows = repo.profiles().stream()
                    .map(p -> new AdjudicationService.Window(p.id(), p.validStart(), p.validEnd()))
                    .toList();
        }
        long runId = adjudication.createRun(req.label(), delay, maxAngle, tol, windows);
        return Map.of("runId", runId);
    }

    @PutMapping("/profiles/{id}/window")
    public Map<String, Object> updateWindow(@PathVariable String id, @RequestBody WindowReq w) {
        if (w.start() == null || w.end() == null || w.start() > w.end()) {
            throw new IllegalArgumentException("剖面时段无效");
        }
        repo.updateProfileWindow(id, w.start(), w.end());
        return Map.of("ok", true);
    }

    @PostMapping("/admin/reimport")
    public Map<String, Object> reimport() {
        int beams = fixtures.wipeAndReload();
        return Map.of("reloaded", true, "beamSamples", beams);
    }

    @GetMapping("/runs/{id}/export")
    public ResponseEntity<String> export(@PathVariable long id) {
        Map<String, Object> payload = run(id);
        payload.put("exportedAt", java.time.Instant.now().toString());
        String body;
        try {
            body = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        String filename = "run-" + id + "-" + java.time.LocalDate.now() + ".json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''"
                                + URLEncoder.encode(filename, StandardCharsets.UTF_8))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
