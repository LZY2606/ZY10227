package com.example.svp.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.File;

@Component
public class SchemaInitializer implements CommandLineRunner {

    private final JdbcTemplate jdbc;
    private final FixtureService fixtureService;

    public SchemaInitializer(JdbcTemplate jdbc, FixtureService fixtureService) {
        this.jdbc = jdbc;
        this.fixtureService = fixtureService;
    }

    public static final String DDL = """
            CREATE TABLE IF NOT EXISTS svp_profile (
              id TEXT PRIMARY KEY,
              name TEXT NOT NULL,
              valid_start INTEGER NOT NULL,
              valid_end INTEGER NOT NULL,
              win_start INTEGER,
              win_end INTEGER,
              ord INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS svp_node (
              profile_id TEXT NOT NULL REFERENCES svp_profile(id) ON DELETE CASCADE,
              ord INTEGER NOT NULL,
              depth REAL NOT NULL,
              c REAL NOT NULL,
              PRIMARY KEY (profile_id, ord)
            );
            CREATE TABLE IF NOT EXISTS attitude_sample (
              id TEXT PRIMARY KEY,
              line TEXT NOT NULL,
              t INTEGER NOT NULL,
              roll REAL NOT NULL,
              pitch REAL NOT NULL,
              heading REAL NOT NULL,
              ord INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS beam_sample (
              id TEXT PRIMARY KEY,
              line TEXT NOT NULL,
              ping_t INTEGER NOT NULL,
              beam_idx INTEGER NOT NULL,
              mount_angle REAL NOT NULL,
              twtt REAL NOT NULL
            );
            CREATE TABLE IF NOT EXISTS fixture_meta (
              k TEXT PRIMARY KEY,
              v TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS run_config (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              label TEXT NOT NULL,
              attitude_delay_ms INTEGER NOT NULL,
              max_beam_angle REAL NOT NULL,
              tol_sec REAL NOT NULL,
              created_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS run_profile_window (
              run_id INTEGER NOT NULL REFERENCES run_config(id) ON DELETE CASCADE,
              profile_id TEXT NOT NULL,
              win_start INTEGER NOT NULL,
              win_end INTEGER NOT NULL,
              PRIMARY KEY (run_id, profile_id)
            );
            CREATE TABLE IF NOT EXISTS sounding (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              run_id INTEGER NOT NULL REFERENCES run_config(id) ON DELETE CASCADE,
              beam_sample_id TEXT NOT NULL,
              line TEXT NOT NULL,
              ping_t INTEGER NOT NULL,
              beam_idx INTEGER NOT NULL,
              profile_id TEXT,
              attitude_version TEXT,
              mount_angle REAL,
              in_water_angle REAL,
              roll REAL,
              depth REAL,
              across REAL,
              residual_sec REAL,
              layers_json TEXT,
              status TEXT NOT NULL,
              failure_code TEXT,
              attitude_anchors TEXT,
              message TEXT
            );
            CREATE TABLE IF NOT EXISTS crossover (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              run_id INTEGER NOT NULL REFERENCES run_config(id) ON DELETE CASCADE,
              line1 TEXT NOT NULL,
              line2 TEXT NOT NULL,
              ping_t INTEGER NOT NULL,
              z1 REAL,
              z2 REAL,
              diff_m REAL,
              status TEXT NOT NULL,
              message TEXT
            );
            """;

    @Override
    public void run(String... args) {
        String url = jdbc.getDataSource() != null
                ? "" : "";
        ensureDataDir();
        jdbc.execute("PRAGMA journal_mode=WAL");
        for (String stmt : DDL.split(";")) {
            String sql = stmt.trim();
            if (!sql.isEmpty()) {
                jdbc.execute(sql);
            }
        }
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM svp_profile", Integer.class);
        if (n != null && n == 0) {
            fixtureService.loadBundledFixture();
        }
    }

    private void ensureDataDir() {
        File dir = new File("data");
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }
}
