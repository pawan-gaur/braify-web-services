package com.braify;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class PdfGeneratorApplication {
    public static void main(String[] args) {
        // Force the JVM to UTC so that LocalDateTime.now() always stores UTC,
        // regardless of the host machine's OS timezone. Without this, a server
        // running in UTC+5:30 stores IST time in LocalDateTime fields, and the
        // frontend's UTC-aware parsing (parseUtc / 'Z' suffix) then double-shifts
        // the time by +5:30.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(PdfGeneratorApplication.class, args);
    }
}
