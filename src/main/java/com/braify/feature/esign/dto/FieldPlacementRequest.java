package com.braify.feature.esign.dto;

import lombok.Data;

@Data
public class FieldPlacementRequest {
    private int    page;
    private double x;
    private double y;
    private double width;
    private double height;
    private String fieldType;   // SIGNATURE | INITIALS | DATE | TEXT | STAMP
    private String label;
    private boolean required = true;
    /** Which signatory fills this field (ESignDocument.Signatory.id). Null → first/only signatory. */
    private String signatoryId;
    /** SIGNER (default) or CREATOR. CREATOR fields are pre-filled by the author at authoring time. */
    private String filledBy;
    /** Creator-supplied value for CREATOR fields (text for TEXT/DATE, "true"/"false" for CHECKBOX,
     *  base64 image or typed name for SIGNATURE/INITIALS). Ignored for SIGNER fields. */
    private String value;
    /** How a CREATOR value was produced: DRAW | TYPE | UPLOAD. Defaults to TYPE. */
    private String signingMethod;
    /** Font size (points) for TEXT/DATE values. Null = default (12pt). */
    private Integer fontSize;
}
