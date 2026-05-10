package com.braify.dto;

import lombok.Data;

@Data
public class LoginRequest {
    private String email;
    private String password;
    /** Optional, e.g. "Chrome/Windows" */
    private String deviceInfo;
}
