package com.braify.feature.auth.dto;

import lombok.Data;

/** Body for enable / disable / regenerate — a single TOTP or recovery code. */
@Data
public class MfaCodeRequest {
    private String code;
}
