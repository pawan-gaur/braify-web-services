package com.braify.feature.signer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * Per-email rate-limit state for sign-in codes. Updated only with atomic Mongo operations, so
 * parallel requests can't slip past the resend gap or the wrong-guess limit.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "email_otp_throttles")
public class OtpThrottle {

    /** Normalized email. */
    @Id
    private String email;

    /** When the last code was sent — a new one is allowed only after the resend gap. */
    private LocalDateTime lastSentAt;

    /** Wrong guesses since {@link #failWindowStart} (across all codes for this email). */
    private int failedGuesses;
    private LocalDateTime failWindowStart;

    /** Code sign-in blocked until this instant after too many wrong guesses. */
    private LocalDateTime lockedUntil;
}
