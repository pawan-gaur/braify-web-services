package com.braify.feature.signup.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Self-serve sign-up. {@code /signup/start} validates everything and emails a code;
 * {@code /signup/complete} repeats the validation, checks {@code code} and creates the account.
 */
@Data
public class SignupRequest {
    @NotBlank @Email @Size(max = 254)
    private String email;

    @NotBlank @Size(max = 60)
    private String firstName;

    @Size(max = 60)
    private String lastName;

    @NotBlank @Size(min = 2, max = 80)
    private String organizationName;

    @NotBlank
    private String password;

    /** Emailed code — required by /signup/complete only. */
    private String code;

    /** Optional, e.g. "Chrome/Windows". */
    private String deviceInfo;
}
