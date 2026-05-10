package com.braify.model;

import lombok.Data;

import java.util.Map;

@Data
public class PdfRequest {
    private String templateId;

    /** Runtime data to inject into placeholders */
    private Map<String, Object> data;

    /** Optional: override filename in Content-Disposition */
    private String filename;
}
