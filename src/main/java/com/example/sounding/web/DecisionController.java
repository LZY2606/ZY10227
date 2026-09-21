package com.example.sounding.web;

import com.example.sounding.repository.SoundingRepository;
import com.example.sounding.service.DecisionDtos;
import com.example.sounding.service.DecisionService;
import com.example.sounding.service.FixtureService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DecisionController {
    private final DecisionService decisionService;
    private final FixtureService fixtureService;
    private final SoundingRepository repository;

    public DecisionController(DecisionService decisionService, FixtureService fixtureService,
                              SoundingRepository repository) {
        this.decisionService = decisionService;
        this.fixtureService = fixtureService;
        this.repository = repository;
    }

    @GetMapping("/state")
    public DecisionDtos.IndexData state() {
        return decisionService.indexData();
    }

    @PostMapping("/runs")
    public DecisionDtos.DecisionResponse execute(@RequestBody DecisionDtos.DecisionRequest request) {
        return decisionService.execute(request);
    }

    @GetMapping("/runs/{id}")
    public DecisionDtos.DecisionResponse run(@PathVariable long id) {
        return decisionService.loadRun(id);
    }

    @PostMapping("/replay")
    public Map<String, Object> replay() {
        fixtureService.reimportFixtures();
        return Map.of("replayed", true,
                "profiles", repository.count("sound_speed_profile"),
                "beams", repository.count("beam_sample"),
                "attitudeSamples", repository.count("attitude_sample"));
    }

    @GetMapping(value = "/runs/{id}/export.csv", produces = "text/csv;charset=UTF-8")
    public void exportCsv(@PathVariable long id, HttpServletResponse response) throws IOException {
        DecisionDtos.DecisionResponse data = decisionService.loadRun(id);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"decision-run-" + id + ".csv\"");
        PrintWriter writer = response.getWriter();
        writer.write('\ufeff');
        writer.println("run_id,profile_id,attitude_version_id,max_beam_angle_deg,total,valid,excluded,failed,profile_gap");
        writer.printf("%d,%s,%s,%.3f,%d,%d,%d,%d,%d%n",
                data.run().id(), data.run().profileId(), data.run().attitudeVersionId(),
                data.run().maxBeamAngleDeg(), data.run().totalSamples(),
                data.run().validPoints(), data.run().excludedPoints(),
                data.run().failedPoints(), data.run().profileGapPoints());
        writer.println();
        writer.println("beam_sample_id,ping_id,line_id,ping_time,beam_index,launch_angle_deg,"
                + "corrected_angle_deg,status,depth_m,east_m,north_m,residual_time_s,"
                + "numerical_tolerance,iterations,layer_ids,attitude_input_sample_ids,"
                + "profile_id,attitude_version_id,input_two_way_time_s,input_sample_identity");
        for (DecisionDtos.ResultRecord result : data.results()) {
            writer.println(String.join(",",
                    csv(result.beamSampleId()), csv(result.pingId()), csv(result.lineId()),
                    csv(result.pingTime()), String.valueOf(result.beamIndex()),
                    String.valueOf(result.launchAngleDeg()), value(result.correctedAngleDeg()),
                    result.status(), value(result.depthM()), value(result.eastM()),
                    value(result.northM()), value(result.residualTimeS()),
                    value(result.numericalTolerance()),
                    result.iterations() == null ? "" : result.iterations().toString(),
                    csv(String.join(";", result.layerIds())),
                    csv(result.attitudeInputSampleIds()), result.profileId(),
                    result.attitudeVersionId(), String.valueOf(result.inputTwoWayTimeS()),
                    csv(result.beamSampleId() + ";ping:" + result.pingId())));
        }
        writer.println();
        writer.println("left_sample_id,right_sample_id,left_ping_id,right_ping_id,east_gap_m,depth_delta_m");
        for (DecisionDtos.CrossDifferenceRecord difference : data.crossDifferences()) {
            writer.printf("%s,%s,%s,%s,%.6f,%.6f%n", difference.leftSampleId(),
                    difference.rightSampleId(), difference.leftPingId(), difference.rightPingId(),
                    difference.eastGapM(), difference.depthDeltaM());
        }
    }

    private String value(Double value) {
        return value == null ? "" : value.toString();
    }

    private String csv(String value) {
        if (value == null) {
            return "";
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> badRequest(
            IllegalArgumentException exception) {
        return org.springframework.http.ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", exception.getMessage()));
    }
}

