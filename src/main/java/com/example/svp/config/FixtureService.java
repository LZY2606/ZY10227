package com.example.svp.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;

@Service
public class FixtureService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public FixtureService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int loadBundledFixture() {
        try (InputStream in = new ClassPathResource("fixtures/fixture.json").getInputStream()) {
            return importFixture(in);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取内置 fixture", e);
        }
    }

    /** 清空所有裁决表后重新导入固定 fixture，供清空数据库复核。 */
    @Transactional
    public int wipeAndReload() {
        jdbc.execute("PRAGMA foreign_keys=ON");
        jdbc.update("DELETE FROM crossover");
        jdbc.update("DELETE FROM sounding");
        jdbc.update("DELETE FROM run_profile_window");
        jdbc.update("DELETE FROM run_config");
        jdbc.update("DELETE FROM beam_sample");
        jdbc.update("DELETE FROM fixture_meta");
        jdbc.update("DELETE FROM attitude_sample");
        jdbc.update("DELETE FROM svp_node");
        jdbc.update("DELETE FROM svp_profile");
        return loadBundledFixture();
    }

    @Transactional
    public int importFixture(InputStream in) throws IOException {
        JsonNode root = mapper.readTree(in);
        JsonNode profiles = root.path("profiles");
        int pOrd = 0;
        for (JsonNode p : profiles) {
            String pid = p.path("id").asText();
            jdbc.update("INSERT INTO svp_profile(id,name,valid_start,valid_end,win_start,win_end,ord) "
                            + "VALUES (?,?,?,?,?,?,?)",
                    pid, p.path("name").asText(),
                    p.path("validStart").asLong(), p.path("validEnd").asLong(),
                    p.path("validStart").asLong(), p.path("validEnd").asLong(), pOrd++);
            int nOrd = 0;
            for (JsonNode nd : p.path("nodes")) {
                jdbc.update("INSERT INTO svp_node(profile_id,ord,depth,c) VALUES (?,?,?,?)",
                        pid, nd.path("ord").asInt(nOrd), nd.path("depth").asDouble(),
                        nd.path("c").asDouble());
                nOrd++;
            }
        }
        for (JsonNode a : root.path("attitude")) {
            jdbc.update("INSERT INTO attitude_sample(id,line,t,roll,pitch,heading,ord) "
                            + "VALUES (?,?,?,?,?,?,?)",
                    a.path("id").asText(), a.path("line").asText(), a.path("t").asLong(),
                    a.path("roll").asDouble(), a.path("pitch").asDouble(0),
                    a.path("heading").asDouble(0), a.path("ord").asInt());
        }
        int beams = 0;
        for (JsonNode b : root.path("beamSamples")) {
            jdbc.update("INSERT INTO beam_sample(id,line,ping_t,beam_idx,mount_angle,twtt) "
                            + "VALUES (?,?,?,?,?,?)",
                    b.path("id").asText(), b.path("line").asText(), b.path("pingT").asLong(),
                    b.path("beamIdx").asInt(), b.path("mountAngle").asDouble(),
                    b.path("twtt").asDouble());
            beams++;
        }
        JsonNode cr = root.path("crossing");
        if (!cr.isMissingNode()) {
            jdbc.update("INSERT OR REPLACE INTO fixture_meta(k,v) VALUES (?,?)",
                    "crossing.pingT", String.valueOf(cr.path("pingT").asLong()));
            jdbc.update("INSERT OR REPLACE INTO fixture_meta(k,v) VALUES (?,?)",
                    "crossing.line1", cr.path("line1").asText("L1"));
            jdbc.update("INSERT OR REPLACE INTO fixture_meta(k,v) VALUES (?,?)",
                    "crossing.line2", cr.path("line2").asText("L2"));
        }
        return beams;
    }

    public JsonNode bundledFixtureDescription() {
        try (InputStream in = new ClassPathResource("fixtures/fixture.json").getInputStream()) {
            JsonNode root = mapper.readTree(in);
            return root.path("description");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
