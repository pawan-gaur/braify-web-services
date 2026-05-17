package com.braify.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UsageResponse {

    private String organizationId;
    private int    year;
    private int    month;
    private String monthLabel;   // e.g. "Jan '26"

    private long docsGenerated;
    private long esignSent;
    private long storageMb;
    private long apiCalls;
}
