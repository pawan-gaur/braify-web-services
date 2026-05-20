package com.braify.feature.fileupload.dto;

import com.braify.feature.fileupload.model.OrgFile;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * API response returned after a successful file upload or when fetching file metadata.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileUploadResponse {

    private String fileId;
    private String organizationId;
    private String uploadedBy;
    private String originalFilename;
    private String storageKey;
    private String bucket;
    private String cloudProvider;
    private String contentType;
    private long   fileSizeBytes;
    private double fileSizeMb;
    private String folder;
    private String documentType;
    private LocalDate documentExpiryDate;
    private String description;
    private List<String> tags;
    private String status;
    private long   downloadCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static FileUploadResponse from(OrgFile f) {
        return FileUploadResponse.builder()
                .fileId(f.getFileId())
                .organizationId(f.getOrganizationId())
                .uploadedBy(f.getUploadedBy())
                .originalFilename(f.getOriginalFilename())
                .storageKey(f.getStorageKey())
                .bucket(f.getBucket())
                .cloudProvider(f.getCloudProvider() != null ? f.getCloudProvider().name() : null)
                .contentType(f.getContentType())
                .fileSizeBytes(f.getFileSizeBytes())
                .fileSizeMb(f.getFileSizeMb())
                .folder(f.getFolder())
                .documentType(f.getDocumentType() != null ? f.getDocumentType().name() : null)
                .documentExpiryDate(f.getDocumentExpiryDate())
                .description(f.getDescription())
                .tags(f.getTags())
                .status(f.getStatus() != null ? f.getStatus().name() : null)
                .downloadCount(f.getDownloadCount())
                .createdAt(f.getCreatedAt())
                .updatedAt(f.getUpdatedAt())
                .build();
    }
}
