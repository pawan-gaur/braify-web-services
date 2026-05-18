package com.braify.feature.esign.model;

import lombok.*;
import org.springframework.data.annotation.*;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Document(collection = "esign_signing_tokens")
public class ESignSigningToken {

    @Id private String id;

    @Indexed(unique = true)
    private String jti;              // JWT ID — one token per send

    @Indexed private String documentId;
    private String clientEmail;

    private LocalDateTime issuedAt;
    private LocalDateTime expiresAt;

    @Builder.Default private boolean used      = false;
    private LocalDateTime usedAt;

    private LocalDateTime revokedAt;           // set on cancel / resend

    @CreatedDate  private LocalDateTime createdAt;
}
