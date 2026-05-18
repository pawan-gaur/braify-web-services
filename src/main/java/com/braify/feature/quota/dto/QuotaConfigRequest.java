package com.braify.feature.quota.dto;

import lombok.Data;

@Data
public class QuotaConfigRequest {

    /** Max active users (-1 = unlimited). */
    private int  maxUsers;

    /** Max documents per month (-1 = unlimited). */
    private int  maxDocsPerMonth;

    /** Max storage in MB (-1 = unlimited). */
    private long maxStorageMb;

    /** Max API calls per month (-1 = unlimited). */
    private int  maxApiCallsPerMonth;
}
