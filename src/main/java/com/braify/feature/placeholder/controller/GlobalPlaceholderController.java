package com.braify.feature.placeholder.controller;

import com.braify.feature.placeholder.dto.GlobalPlaceholderRequest;
import com.braify.feature.placeholder.dto.GlobalPlaceholderResponse;
import com.braify.feature.placeholder.service.GlobalPlaceholderService;
import com.braify.feature.user.model.AppUser;
import com.braify.security.UserDetailsImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@Tag(name = "Global Placeholders",
     description = "Org-level reusable placeholders (logo, organization_name, address, …) auto-injected into email and PDF templates that reference {{key}}.")
@RestController
@RequestMapping("/api/global-placeholders")
@RequiredArgsConstructor
public class GlobalPlaceholderController {

    private final GlobalPlaceholderService service;

    private AppUser currentUser(Authentication auth) {
        return ((UserDetailsImpl) auth.getPrincipal()).getAppUser();
    }

    @Operation(summary = "List global placeholders for the caller's organisation")
    @GetMapping
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','ADMIN')")
    public List<GlobalPlaceholderResponse> list(Authentication auth) {
        return service.list(currentUser(auth).getOrganizationId());
    }

    @Operation(summary = "Create a global placeholder")
    @PostMapping
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','ADMIN')")
    public ResponseEntity<GlobalPlaceholderResponse> create(@Valid @RequestBody GlobalPlaceholderRequest req,
                                                            Authentication auth) {
        AppUser caller = currentUser(auth);
        log.info("POST /api/global-placeholders key='{}' by '{}'", req.getKey(), caller.getEmail());
        return ResponseEntity.ok(service.create(caller.getOrganizationId(), req));
    }

    @Operation(summary = "Update a global placeholder")
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','ADMIN')")
    public ResponseEntity<GlobalPlaceholderResponse> update(@PathVariable String id,
                                                            @Valid @RequestBody GlobalPlaceholderRequest req,
                                                            Authentication auth) {
        AppUser caller = currentUser(auth);
        log.info("PUT /api/global-placeholders/{} by '{}'", id, caller.getEmail());
        return ResponseEntity.ok(service.update(id, caller.getOrganizationId(), req));
    }

    @Operation(summary = "Delete a global placeholder")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable String id, Authentication auth) {
        AppUser caller = currentUser(auth);
        log.info("DELETE /api/global-placeholders/{} by '{}'", id, caller.getEmail());
        service.delete(id, caller.getOrganizationId());
        return ResponseEntity.noContent().build();
    }
}
