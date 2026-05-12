package com.braify.dto.esign;

import lombok.Data;

@Data
public class SignFieldRequest {
    private String signingMethod;   // DRAW | TYPE | UPLOAD
    private String value;           // base64 PNG data-URL or plain text
}
