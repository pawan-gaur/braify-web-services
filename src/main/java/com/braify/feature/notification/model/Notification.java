package com.braify.feature.notification.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/** In-app notification for one user (the bell in the top bar). Purged after 90 days. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "notifications")
@CompoundIndex(name = "idx_user_read_created", def = "{'userId': 1, 'read': 1, 'createdAt': -1}")
public class Notification {
    @Id
    private String id;
    private String orgId;
    private String userId;
    private String type;
    private String title;
    private String link;
    @Builder.Default
    private boolean read = false;
    private LocalDateTime createdAt;
    @Indexed(expireAfterSeconds = 0)
    private LocalDateTime expiresAt;
}
