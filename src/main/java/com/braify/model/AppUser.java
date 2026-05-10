package com.braify.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "users")
public class AppUser {

    @Id
    private String id;

    @Indexed(unique = true)
    private String email;

    /** BCrypt hashed password */
    private String password;

    private String firstName;
    private String lastName;

    private Role role;

    /** null for PLATFORM_ADMIN */
    private String organizationId;

    private boolean active = true;

    /** Set to true until the user completes the invite / password-reset flow */
    private boolean mustChangePassword = false;

    /** Base64 data-URL of the user's profile picture (may be null) */
    private String profilePicture;

    /** Optional short bio / display note */
    private String bio;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    public enum Role {
        PLATFORM_ADMIN, ORG_ADMIN, ADMIN, USER
    }

    public String getFullName() {
        return firstName + " " + lastName;
    }
}
