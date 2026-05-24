package com.braify.feature.esign.dto;

import com.braify.feature.esign.model.ESignBulkBatch;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Summary DTO returned for each bulk batch in the list and detail views.
 */
@Data @Builder
public class BulkBatchResponse {

    private String        id;
    private String        label;
    private int           totalRequested;
    private int           totalCreated;
    private int           totalSent;
    private int           totalFailed;
    private String        status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static BulkBatchResponse from(ESignBulkBatch batch) {
        return BulkBatchResponse.builder()
                .id(batch.getId())
                .label(batch.getLabel())
                .totalRequested(batch.getTotalRequested())
                .totalCreated(batch.getTotalCreated())
                .totalSent(batch.getTotalSent())
                .totalFailed(batch.getTotalFailed())
                .status(batch.getStatus().name())
                .createdAt(batch.getCreatedAt())
                .updatedAt(batch.getUpdatedAt())
                .build();
    }
}
