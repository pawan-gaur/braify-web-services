package com.braify.feature.esign.dto;

import lombok.Data;

@Data
public class FieldPlacementRequest {
    private int    page;
    private double x;
    private double y;
    private double width;
    private double height;
    private String fieldType;   // SIGNATURE | INITIALS | DATE | TEXT
    private String label;
    private boolean required = true;
    /** Which signatory fills this field (ESignDocument.Signatory.id). Null → first/only signatory. */
    private String signatoryId;
}
