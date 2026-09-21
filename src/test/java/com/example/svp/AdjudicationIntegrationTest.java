package com.example.svp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AdjudicationIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired com.example.svp.config.FixtureService fixtureService;
    private final ObjectMapper mapper = new ObjectMapper();

    static {
        new java.io.File("data").mkdirs();
        for (String n : new String[]{"data/svp-test.db", "data/svp-test.db-wal",
                "data/svp-test.db-shm"}) {
            java.io.File f = new java.io.File(n);
            if (f.exists()) f.delete();
        }
    }

    private JsonNode json(String s) throws Exception { return mapper.readTree(s); }

    private long createRun(long delay) throws Exception {
        String body = """
                {"label":"test-%d","attitudeDelayMs":%d,"maxBeamAngle":65,"tolSec":1e-9,"windows":[
                  {"profileId":"SVP-A","start":0,"end":9000},
                  {"profileId":"SVP-B","start":10000,"end":20000}]}
                """.formatted(delay, delay);
        String resp = mvc.perform(post("/api/runs")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json(resp).get("runId").asLong();
    }

    @Test
    void fixtureLoadedWithGapAndThreeLayers() throws Exception {
        String s = mvc.perform(get("/api/state")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode st = json(s);
        assertEquals(2, st.get("profiles").size());
        assertEquals(110, st.get("counts").get("beams").asInt());
    }

    @Test
    void twoAttitudeDelaysGiveDifferentOutcomeAtPing5000() throws Exception {
        long r0 = createRun(0);
        long r6 = createRun(600);
        JsonNode s0 = json(mvc.perform(get("/api/runs/" + r0)).andReturn()
                .getResponse().getContentAsString());
        JsonNode s6 = json(mvc.perform(get("/api/runs/" + r6)).andReturn()
                .getResponse().getContentAsString());

        JsonNode outer0 = find(s0, "L1-P02-B4");
        JsonNode outer6 = find(s6, "L1-P02-B4");
        assertEquals("OK", outer0.get("status").asText(),
                "对齐方案 t=5000 最外侧波束水中角 59°，仍在 50m 剖面内");
        assertTrue(outer0.get("depth").asDouble() > 49.0 && outer0.get("depth").asDouble() < 50.0);
        assertEquals("SOLVE_FAILED", outer6.get("status").asText(),
                "姿态时标错位 (+600ms) 使水中角变为 58.4°（更陡），往返时间需深入超过 50m，未支持");
        assertEquals("DEPTH_EXCEEDED", outer6.get("failure_code").asText());
        assertTrue(outer6.get("depth").isNull(),
                "失败时不得回填任何由平均声速伪造的深度");
    }

    @Test
    void gapPingIsNotInterpolatedAcrossProfiles() throws Exception {
        long id = createRun(0);
        JsonNode run = json(mvc.perform(get("/api/runs/" + id)).andReturn()
                .getResponse().getContentAsString());
        JsonNode gap = find(run, "L1-P05-B2");
        assertEquals("NO_PROFILE", gap.get("status").asText());
        assertTrue(gap.get("message").asText().contains("空档"));
        assertTrue(gap.get("depth").isNull());
    }

    @Test
    void failedSolveNeverFallsBackToMeanSpeedAndKeepsProvenance() throws Exception {
        long id = createRun(0);
        JsonNode run = json(mvc.perform(get("/api/runs/" + id)).andReturn()
                .getResponse().getContentAsString());
        JsonNode failed = find(run, "L1-P00-B4");
        assertTrue(failed.get("depth").isNull(), "失败样本不得写入任何（如平均声速反算的）深度");
        assertEquals("ATT-DELAY-0ms", failed.get("attitude_version").asText());
        assertNotNull(failed.get("attitude_anchors"));
        assertEquals("L1-P00-B4", failed.get("beam_sample_id").asText());

        JsonNode anyFailed = null;
        for (JsonNode n : run.get("soundings")) {
            if ("SOLVE_FAILED".equals(n.get("status").asText())) { anyFailed = n; break; }
        }
        assertNotNull(anyFailed, "0ms 方案早期最外侧波束应存在失败样本");
        assertTrue(anyFailed.get("depth").isNull());
        JsonNode ok = find(run, "L1-P02-B2");
        assertEquals("OK", ok.get("status").asText());
        JsonNode layers = json(ok.get("layers_json").asText());
        assertTrue(layers.size() >= 1 && layers.size() <= 3);
        assertTrue(ok.get("residual_sec").asDouble() <= 1e-9);
    }

    @Test
    void beamAngleExclusionApplies() throws Exception {
        String body = """
                {"label":"narrow","attitudeDelayMs":0,"maxBeamAngle":58,"tolSec":1e-9,"windows":[
                  {"profileId":"SVP-A","start":0,"end":9000},
                  {"profileId":"SVP-B","start":10000,"end":20000}]}
                """;
        String resp = mvc.perform(post("/api/runs").contentType("application/json").content(body))
                .andReturn().getResponse().getContentAsString();
        long id = json(resp).get("runId").asLong();
        JsonNode run = json(mvc.perform(get("/api/runs/" + id)).andReturn()
                .getResponse().getContentAsString());
        boolean anyExcluded = false;
        for (JsonNode s : run.get("soundings")) {
            if ("EXCLUDED_ANGLE".equals(s.get("status").asText())) {
                anyExcluded = true;
                assertTrue(Math.abs(s.get("in_water_angle").asDouble()) > 58);
            }
        }
        assertTrue(anyExcluded, "58° 限制下应排除超出波束角的样本");
    }

    @Test
    void crossoverDiffersBetweenDelays() throws Exception {
        long r0 = createRun(0);
        long r6 = createRun(600);
        double d0 = crossoverDiff(r0);
        double d6 = crossoverDiff(r6);
        assertNotEquals(d0, d6, 1e-9);
        assertTrue(Math.abs(d0) > 0.005 && Math.abs(d6) > 0.005);
        assertEquals(-0.0193, d0, 0.002, "对齐方案交叉差约 1.9cm");
        assertEquals(-0.0373, d6, 0.002, "错位方案交叉差约 3.7cm");
        assertTrue(Math.abs(d6) > Math.abs(d0), "+600ms 错位放大系统交叉差");
    }

    @Test
    void overlappingWindowsRejected() throws Exception {
        String body = """
                {"label":"bad","attitudeDelayMs":0,"maxBeamAngle":65,"tolSec":1e-9,"windows":[
                  {"profileId":"SVP-A","start":0,"end":12000},
                  {"profileId":"SVP-B","start":10000,"end":20000}]}
                """;
        mvc.perform(post("/api/runs").contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void wipeAndReimportAllowsReproducibleReplay() throws Exception {
        createRun(0);
        mvc.perform(post("/api/admin/reimport")).andExpect(status().isOk());
        String s = mvc.perform(get("/api/state")).andReturn().getResponse().getContentAsString();
        JsonNode st = json(s);
        assertEquals(110, st.get("counts").get("beams").asInt());
        assertEquals(0, st.get("runs").size(), "重导后旧方案应清空");
        long id0 = createRun(0);
        long id6 = createRun(600);
        JsonNode r0 = json(mvc.perform(get("/api/runs/" + id0)).andReturn().getResponse().getContentAsString());
        JsonNode r6 = json(mvc.perform(get("/api/runs/" + id6)).andReturn().getResponse().getContentAsString());
        assertEquals("OK", find(r0, "L1-P02-B4").get("status").asText());
        assertEquals("SOLVE_FAILED", find(r6, "L1-P02-B4").get("status").asText(),
                "清空重导后两方案对比结果必须可复现");
    }

    @Test
    void exportContainsRunRecord() throws Exception {
        long id = createRun(0);
        mvc.perform(get("/api/runs/" + id + "/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("run-")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("soundings")));
    }

    private JsonNode find(JsonNode run, String beamSampleId) {
        for (JsonNode s : run.get("soundings")) {
            if (beamSampleId.equals(s.get("beam_sample_id").asText())) {
                return s;
            }
        }
        throw new AssertionError("未找到样本 " + beamSampleId);
    }

    private double crossoverDiff(long runId) throws Exception {
        JsonNode run = json(mvc.perform(get("/api/runs/" + runId)).andReturn()
                .getResponse().getContentAsString());
        JsonNode c = run.get("crossovers").get(0);
        assertEquals("OK", c.get("status").asText(), c.path("message").asText());
        return c.get("diff_m").asDouble();
    }
}
