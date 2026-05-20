package com.onsemi.cim.apps.exensio.exensioreload;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class ExensioreloadApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExensioreloadApplication.class, args);
    }
}
