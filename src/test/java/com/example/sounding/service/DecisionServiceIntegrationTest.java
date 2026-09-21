package com.example.sounding.service;

import com.example.sounding.SoundingApplication;
import com.example.sounding.domain.SolutionStatus;
import com.example.sounding.repository.SoundingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = SoundingApplication.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:./target/integration-sounding.sqlite"
})
class DecisionServiceIntegrationTest {
    @Autowired
    private DecisionService decisionService;
    @Autowired
    private FixtureService fixtureService;
    @Autowired
    private SoundingRepository repository;

    @AfterEach
    void cleanRuns() {
        repository.deleteAllData();
        fixtureService.reimportFixtures();
    }

    @Test
    void comparesTwoAttitudeDelaysAndPreservesAttitudeInputs() {
        DecisionDtos.DecisionResponse v0 = decisionService.execute(
                new DecisionDtos.DecisionRequest("P1", "V0", 60.0));
        DecisionDtos.ResultRecord v0Outer = bySample(v0, "P1-A-B04");
        assertEquals(SolutionStatus.VALID.name(), v0Outer.status());
        assertEquals(48.0, v0Outer.correctedAngleDeg(), 1.0e-10);
        assertTrue(v0Outer.attitudeInputSampleIds().contains("ATT-P1-A-V0"));

        DecisionDtos.DecisionResponse v250 = decisionService.execute(
                new DecisionDtos.DecisionRequest("P1", "V250", 60.0));
        DecisionDtos.ResultRecord delayedOuter = bySample(v250, "P1-A-B04");
        assertEquals(SolutionStatus.VALID.name(), delayedOuter.status());
        assertEquals(49.0, delayedOuter.correctedAngleDeg(), 1.0e-10);
        assertTrue(delayedOuter.attitudeInputSampleIds().contains("ATT-P1-A-V250"));
        assertFalse(v0Outer.depthM().equals(delayedOuter.depthM()));

        DecisionDtos.ResultRecord boundary = bySample(v0, "P1-A-B03");
        assertEquals(List.of("P1-L1", "P1-L2"), boundary.layerIds());
        assertEquals(1, boundary.vertices().stream()
                .filter(vertex -> Math.abs(vertex.depthM() - 25.0) < 1.0e-8).count());
    }

    @Test
    void excludesOutsideBeamAngleButDoesNotRayTraceIt() {
        DecisionDtos.DecisionResponse response = decisionService.execute(
                new DecisionDtos.DecisionRequest("P1", "V0", 60.0));
        DecisionDtos.ResultRecord excluded = bySample(response, "P1-A-B06");

        assertEquals(SolutionStatus.EXCLUDED_BEAM_ANGLE.name(), excluded.status());
        assertEquals(62.0, excluded.correctedAngleDeg(), 1.0e-10);
        assertNull(excluded.depthM());
        assertTrue(excluded.layerIds().isEmpty());
    }

    @Test
    void profileGapIsNotInterpolatedAndBeamBeyondMaxDepthStaysUnsupported() {
        DecisionDtos.DecisionResponse response = decisionService.execute(
                new DecisionDtos.DecisionRequest("P2", "V0", 60.0));
        DecisionDtos.ResultRecord gap = bySample(response, "GAP-1-B01");
        DecisionDtos.ResultRecord beyond = bySample(response, "P2-A-B05");

        assertEquals(SolutionStatus.PROFILE_GAP.name(), gap.status());
        assertNull(gap.depthM());
        assertEquals(SolutionStatus.BELOW_PROFILE_MAX_DEPTH.name(), beyond.status());
        assertNull(beyond.depthM());
        assertTrue(beyond.layerIds().containsAll(List.of("P2-L1", "P2-L2", "P2-L3")));
    }

    @Test
    void replayAfterClearReloadsFixedFixturesAndProducesSameDecision() {
        DecisionDtos.DecisionResponse first = decisionService.execute(
                new DecisionDtos.DecisionRequest("P1", "V0", 60.0));
        repository.deleteAllData();
        assertEquals(0L, repository.count("sound_speed_profile"));

        fixtureService.reimportFixtures();
        DecisionDtos.DecisionResponse second = decisionService.execute(
                new DecisionDtos.DecisionRequest("P1", "V0", 60.0));

        assertEquals(2L, repository.count("sound_speed_profile"));
        assertEquals(25L, repository.count("beam_sample"));
        assertEquals(first.run().validPoints(), second.run().validPoints());
        assertEquals(first.results().size(), second.results().size());
        assertNotNull(bySample(second, "P1-B-B04"));
    }

    private DecisionDtos.ResultRecord bySample(DecisionDtos.DecisionResponse response, String sampleId) {
        return response.results().stream()
                .filter(result -> result.beamSampleId().equals(sampleId))
                .findFirst()
                .orElseThrow();
    }
}

