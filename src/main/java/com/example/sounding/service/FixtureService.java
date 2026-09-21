package com.example.sounding.service;

import com.example.sounding.domain.AttitudeSample;
import com.example.sounding.domain.AttitudeVersion;
import com.example.sounding.domain.BeamSample;
import com.example.sounding.domain.Ping;
import com.example.sounding.domain.Profile;
import com.example.sounding.repository.SoundingRepository;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class FixtureService {
    private final SoundingRepository repository;

    public FixtureService(SoundingRepository repository) {
        this.repository = repository;
    }

    public synchronized void importFixturesIfEmpty() {
        if (repository.count("sound_speed_profile") > 0) {
            return;
        }
        importFixtures();
    }

    public synchronized void reimportFixtures() {
        importFixtures();
    }

    private void importFixtures() {
        List<Map<String, String>> profileRows = readCsv("classpath:fixtures/profiles.csv");
        List<Map<String, String>> layerRows = readCsv("classpath:fixtures/sound_speed_layers.csv");
        List<Profile> profiles = new ArrayList<>();
        for (Map<String, String> row : profileRows) {
            String profileId = row.get("id");
            List<com.example.sounding.domain.SoundSpeedLayer> layers = new ArrayList<>();
            layerRows.stream()
                    .filter(layer -> layer.get("profile_id").equals(profileId))
                    .map(layer -> new com.example.sounding.domain.SoundSpeedLayer(
                            profileId,
                            profileId + "-L" + Integer.parseInt(layer.get("ordinal")),
                            Integer.parseInt(layer.get("ordinal")),
                            Double.parseDouble(layer.get("depth_top")),
                            Double.parseDouble(layer.get("depth_bottom")),
                            Double.parseDouble(layer.get("speed_top")),
                            Double.parseDouble(layer.get("speed_bottom"))))
                    .forEach(layers::add);
            profiles.add(new Profile(profileId, row.get("name"),
                    Instant.parse(row.get("valid_from")), Instant.parse(row.get("valid_to")), layers));
        }
        List<AttitudeVersion> versions = readCsv("classpath:fixtures/attitude_versions.csv").stream()
                .map(row -> new AttitudeVersion(row.get("id"), row.get("name"),
                        Long.parseLong(row.get("delay_millis"))))
                .toList();
        List<AttitudeSample> attitude = readCsv("classpath:fixtures/attitude_samples.csv").stream()
                .map(row -> new AttitudeSample(row.get("id"), Instant.parse(row.get("event_time")),
                        Double.parseDouble(row.get("roll_deg")), Double.parseDouble(row.get("pitch_deg")),
                        Double.parseDouble(row.get("heave_m"))))
                .toList();
        List<Ping> pings = readCsv("classpath:fixtures/pings.csv").stream()
                .map(row -> new Ping(row.get("id"), row.get("line_id"),
                        Instant.parse(row.get("event_time")), Double.parseDouble(row.get("east_m")),
                        Double.parseDouble(row.get("north_m"))))
                .toList();
        List<BeamSample> beams = readCsv("classpath:fixtures/beam_samples.csv").stream()
                .map(row -> new BeamSample(row.get("id"), row.get("ping_id"),
                        Integer.parseInt(row.get("beam_index")),
                        Double.parseDouble(row.get("launch_angle_deg")),
                        Double.parseDouble(row.get("two_way_time_s"))))
                .toList();
        repository.replaceFixtures(profiles, versions, attitude, pings, beams);
    }

    private List<Map<String, String>> readCsv(String locationPattern) {
        try {
            Resource resource = new PathMatchingResourcePatternResolver().getResource(locationPattern);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                List<Map<String, String>> rows = new ArrayList<>();
                String headerLine = reader.readLine();
                if (headerLine == null) {
                    return List.of();
                }
                List<String> headers = parseCsvLine(headerLine);
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    List<String> values = parseCsvLine(line);
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int index = 0; index < headers.size(); index++) {
                        row.put(headers.get(index), index < values.size() ? values.get(index) : "");
                    }
                    rows.add(row);
                }
                return rows;
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取固定 fixture: " + locationPattern, exception);
        }
    }

    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (current == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    value.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (current == ',' && !quoted) {
                values.add(value.toString());
                value.setLength(0);
            } else {
                value.append(current);
            }
        }
        values.add(value.toString());
        return List.copyOf(values);
    }
}

