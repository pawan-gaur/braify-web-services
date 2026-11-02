package com.braify.feature.shortlink.controller;

import com.braify.feature.shortlink.service.ShortLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Public: resolves a short code (from an SMS) to the in-app path it stands for. */
@Tag(name = "Short links")
@RestController
@RequestMapping("/api/s")
@RequiredArgsConstructor
public class ShortLinkController {

    private final ShortLinkService service;

    @Operation(summary = "Resolve a short link", description = "Returns `{ path }` (an app-relative path), or 404 when unknown / expired.")
    @GetMapping("/{code}")
    public Map<String, String> resolve(@PathVariable String code) {
        return Map.of("path", service.resolve(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "This link has expired or doesn’t exist.")));
    }
}
