package com.braify.controller;

import com.braify.dto.OrganizationRequest;
import com.braify.model.Organization;
import com.braify.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/organizations")
@RequiredArgsConstructor
public class OrganizationController {

    private final OrganizationService orgService;

    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public List<Organization> getAll() {
        return orgService.findAll();
    }

    @GetMapping("/search")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public List<Organization> search(@RequestParam(defaultValue = "") String q) {
        return orgService.search(q);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Organization getById(@PathVariable String id) {
        return orgService.findById(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Organization> create(@RequestBody OrganizationRequest req) {
        return ResponseEntity.ok(orgService.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Organization> update(@PathVariable String id,
                                               @RequestBody OrganizationRequest req) {
        return ResponseEntity.ok(orgService.update(id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        orgService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
