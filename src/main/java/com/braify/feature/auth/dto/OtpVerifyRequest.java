package com.braify.feature.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Body of POST /api/auth/otp/verify. */
@Data
public class OtpVerifyRequest {
    @NotBlank @Email
    private String email;
    @NotBlank
    private String code;
    /** Optional, e.g. "Chrome/Windows" */
    private String deviceInfo;
}
