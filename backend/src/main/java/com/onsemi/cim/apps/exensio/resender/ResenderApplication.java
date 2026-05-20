package com.onsemi.cim.apps.exensio.resender;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class ResenderApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResenderApplication.class, args);
    }
}
