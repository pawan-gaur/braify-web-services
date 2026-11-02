package com.braify.feature.notification.controller;

import com.braify.feature.notification.model.Notification;
import com.braify.feature.notification.service.NotificationService;
import com.braify.security.UserDetailsImpl;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** The signed-in user's own notifications. */
@Tag(name = "Notifications")
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','ADMIN','USER')")
public class NotificationController {

    private final NotificationService service;

    @GetMapping
    public List<Notification> list(@RequestParam(defaultValue = "false") boolean unreadOnly,
                                   @RequestParam(defaultValue = "20") int limit, Authentication auth) {
        return service.list(userId(auth), unreadOnly, limit);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(Authentication auth) {
        return Map.of("count", service.unreadCount(userId(auth)));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> read(@PathVariable String id, Authentication auth) {
        service.markRead(userId(auth), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public ResponseEntity<Void> readAll(Authentication auth) {
        service.markAllRead(userId(auth));
        return ResponseEntity.noContent().build();
    }

    private static String userId(Authentication auth) {
        return ((UserDetailsImpl) auth.getPrincipal()).getId();
    }
}
