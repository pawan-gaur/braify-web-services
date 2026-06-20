package com.braify.feature.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/** Returned ONCE on enable / regenerate — plaintext recovery codes (stored hashed). */
@Data
@AllArgsConstructor
public class MfaRecoveryCodesResponse {
    private List<String> recoveryCodes;
}
