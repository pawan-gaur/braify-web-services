package com.braify.feature.user.controller;

import com.braify.feature.user.dto.UserRequest;
import com.braify.feature.user.dto.UserResponse;
import com.braify.feature.user.model.AppUser;
import com.braify.security.UserDetailsImpl;
import com.braify.feature.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    private AppUser currentUser(Authentication auth) {
        return ((UserDetailsImpl) auth.getPrincipal()).getAppUser();
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','ADMIN')")
    public List<UserResponse> getAll(Authentication auth) {
        log.debug("GET /api/users caller='{}'", currentUser(auth).getEmail());
        return userService.findAll(currentUser(auth));
    }

    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','ADMIN')")
    public List<UserResponse> search(@RequestParam(defaultValue = "") String q,
                                     @RequestParam(required = false) String orgId,
                                     Authentication auth) {
        log.debug("GET /api/users/search q='{}' orgId={} caller='{}'", q, orgId, currentUser(auth).getEmail());
        return userService.search(q, orgId, currentUser(auth));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','ADMIN')")
    public ResponseEntity<UserResponse> getById(@PathVariable String id, Authentication auth) {
        log.debug("GET /api/users/{} caller='{}'", id, currentUser(auth).getEmail());
        return ResponseEntity.ok(userService.findById(id, currentUser(auth)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','ADMIN')")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody UserRequest req, Authentication auth) {
        log.info("POST /api/users email='{}' by '{}'", req.getEmail(), currentUser(auth).getEmail());
        ResponseEntity<UserResponse> result = ResponseEntity.ok(userService.create(req, currentUser(auth)));
        log.info("User created: id='{}'", result.getBody() != null ? result.getBody().getId() : "unknown");
        return result;
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','ADMIN')")
    public ResponseEntity<UserResponse> update(@PathVariable String id,
                                               @Valid @RequestBody UserRequest req, Authentication auth) {
        log.info("PUT /api/users/{} by '{}'", id, currentUser(auth).getEmail());
        ResponseEntity<UserResponse> result = ResponseEntity.ok(userService.update(id, req, currentUser(auth)));
        log.info("User '{}' updated", id);
        return result;
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','ADMIN')")
    public ResponseEntity<Void> deactivate(@PathVariable String id, Authentication auth) {
        log.info("DELETE /api/users/{} by '{}'", id, currentUser(auth).getEmail());
        userService.deactivate(id, currentUser(auth));
        log.info("User '{}' deactivated", id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/enable")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','ADMIN')")
    public ResponseEntity<Void> enable(@PathVariable String id, Authentication auth) {
        log.info("PUT /api/users/{}/enable by '{}'", id, currentUser(auth).getEmail());
        userService.enable(id, currentUser(auth));
        log.info("User '{}' enabled", id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/disable")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','ADMIN')")
    public ResponseEntity<Void> disable(@PathVariable String id, Authentication auth) {
        log.info("PUT /api/users/{}/disable by '{}'", id, currentUser(auth).getEmail());
        userService.deactivate(id, currentUser(auth));
        log.info("User '{}' disabled", id);
        return ResponseEntity.noContent().build();
    }
}
