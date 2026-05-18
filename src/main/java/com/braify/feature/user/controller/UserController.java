package com.braify.feature.user.controller;

import com.braify.feature.user.dto.UserRequest;
import com.braify.feature.user.dto.UserResponse;
import com.braify.feature.user.model.AppUser;
import com.braify.security.UserDetailsImpl;
import com.braify.feature.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
        return userService.findAll(currentUser(auth));
    }

    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','ADMIN')")
    public List<UserResponse> search(@RequestParam(defaultValue = "") String q,
                                     @RequestParam(required = false) String orgId,
                                     Authentication auth) {
        return userService.search(q, orgId, currentUser(auth));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','ADMIN')")
    public ResponseEntity<UserResponse> getById(@PathVariable String id, Authentication auth) {
        return ResponseEntity.ok(userService.findById(id, currentUser(auth)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','ADMIN')")
    public ResponseEntity<UserResponse> create(@RequestBody UserRequest req, Authentication auth) {
        return ResponseEntity.ok(userService.create(req, currentUser(auth)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','ADMIN')")
    public ResponseEntity<UserResponse> update(@PathVariable String id,
                                               @RequestBody UserRequest req, Authentication auth) {
        return ResponseEntity.ok(userService.update(id, req, currentUser(auth)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','ADMIN')")
    public ResponseEntity<Void> deactivate(@PathVariable String id, Authentication auth) {
        userService.deactivate(id, currentUser(auth));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/enable")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','ADMIN')")
    public ResponseEntity<Void> enable(@PathVariable String id, Authentication auth) {
        userService.enable(id, currentUser(auth));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/disable")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','ADMIN')")
    public ResponseEntity<Void> disable(@PathVariable String id, Authentication auth) {
        userService.deactivate(id, currentUser(auth));
        return ResponseEntity.noContent().build();
    }
}
