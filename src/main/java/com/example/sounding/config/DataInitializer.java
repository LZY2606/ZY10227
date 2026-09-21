package com.example.sounding.config;

import com.example.sounding.service.FixtureService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.File;

@Component
public class DataInitializer implements ApplicationRunner {
    private final FixtureService fixtureService;

    public DataInitializer(FixtureService fixtureService) {
        this.fixtureService = fixtureService;
    }

    @Override
    public void run(ApplicationArguments args) {
        File dataDirectory = new File("data");
        if (!dataDirectory.exists()) {
            dataDirectory.mkdirs();
        }
        fixtureService.importFixturesIfEmpty();
    }
}

