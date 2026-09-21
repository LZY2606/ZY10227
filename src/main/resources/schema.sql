PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS sound_speed_profile (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    valid_from TEXT NOT NULL,
    valid_to TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS sound_speed_layer (
    profile_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL,
    depth_top REAL NOT NULL,
    depth_bottom REAL NOT NULL,
    speed_top REAL NOT NULL,
    speed_bottom REAL NOT NULL,
    PRIMARY KEY (profile_id, ordinal),
    FOREIGN KEY (profile_id) REFERENCES sound_speed_profile(id)
);

CREATE TABLE IF NOT EXISTS attitude_version (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    delay_millis INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS attitude_sample (
    id TEXT PRIMARY KEY,
    event_time TEXT NOT NULL,
    roll_deg REAL NOT NULL,
    pitch_deg REAL NOT NULL,
    heave_m REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS ping (
    id TEXT PRIMARY KEY,
    line_id TEXT NOT NULL,
    event_time TEXT NOT NULL,
    east_m REAL NOT NULL,
    north_m REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS beam_sample (
    id TEXT PRIMARY KEY,
    ping_id TEXT NOT NULL,
    beam_index INTEGER NOT NULL,
    launch_angle_deg REAL NOT NULL,
    two_way_time_s REAL NOT NULL,
    FOREIGN KEY (ping_id) REFERENCES ping(id)
);

CREATE TABLE IF NOT EXISTS decision_run (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at TEXT NOT NULL,
    profile_id TEXT NOT NULL,
    attitude_version_id TEXT NOT NULL,
    max_beam_angle_deg REAL NOT NULL,
    total_samples INTEGER NOT NULL,
    valid_points INTEGER NOT NULL,
    excluded_points INTEGER NOT NULL,
    failed_points INTEGER NOT NULL,
    profile_gap_points INTEGER NOT NULL,
    parameters_hash TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS sounding_result (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id INTEGER NOT NULL,
    beam_sample_id TEXT NOT NULL,
    ping_id TEXT NOT NULL,
    status TEXT NOT NULL,
    input_sample_identity TEXT NOT NULL,
    depth_m REAL,
    east_m REAL,
    north_m REAL,
    corrected_angle_deg REAL,
    residual_time_s REAL,
    numerical_tolerance REAL,
    iterations INTEGER,
    layer_ids TEXT,
    attitude_input_sample_ids TEXT,
    profile_id TEXT,
    attitude_version_id TEXT,
    input_two_way_time_s REAL,
    FOREIGN KEY (run_id) REFERENCES decision_run(id)
);

CREATE TABLE IF NOT EXISTS ray_vertex (
    result_id INTEGER NOT NULL,
    vertex_order INTEGER NOT NULL,
    across_m REAL NOT NULL,
    depth_m REAL NOT NULL,
    owning_layer_id TEXT,
    PRIMARY KEY (result_id, vertex_order),
    FOREIGN KEY (result_id) REFERENCES sounding_result(id)
);

CREATE TABLE IF NOT EXISTS cross_difference (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id INTEGER NOT NULL,
    left_result_id INTEGER NOT NULL,
    right_result_id INTEGER NOT NULL,
    east_gap_m REAL NOT NULL,
    depth_delta_m REAL NOT NULL,
    FOREIGN KEY (run_id) REFERENCES decision_run(id)
);
