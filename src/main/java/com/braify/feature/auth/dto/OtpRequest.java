package com.braify.feature.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Body of POST /api/auth/otp/request. */
@Data
public class OtpRequest {
    @NotBlank @Email
    private String email;
}
