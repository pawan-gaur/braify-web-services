package com.braify.feature.fileupload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-organisation storage summary returned in the Platform Admin file list.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrgStorageStat {

    private String orgId;
    private String orgName;
    private long   fileCount;
    private double storageMb;
}
