package com.braify.feature.health.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public liveness probe. Returns 200 as long as the application is running and able
 * to serve HTTP — the frontend polls this to distinguish "backend down" (no response
 * or a gateway 5xx) from "no internet". Intentionally dependency-free (no DB call) so
 * it stays fast and never fails for reasons unrelated to reachability.
 */
@Tag(name = "Health", description = "Public liveness probe for connectivity checks.")
@RestController
@RequestMapping("/api/health")
public class HealthController {

    @Operation(summary = "Liveness probe — 200 if the backend is up")
    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "braify-web-services");
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(body);
    }
}
