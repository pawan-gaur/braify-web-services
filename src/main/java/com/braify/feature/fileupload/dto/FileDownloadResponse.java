package com.braify.feature.fileupload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Returned when a caller requests a download URL for a stored file.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileDownloadResponse {

    /** The human-readable file ID. */
    private String fileId;

    /** Original filename for the Content-Disposition header hint. */
    private String originalFilename;

    /**
     * Pre-signed URL from the cloud provider.
     * Valid for {@link #expiresInSeconds} seconds.
     */
    private String downloadUrl;

    /** How many seconds until the URL expires. */
    private int expiresInSeconds;

    /** UTC timestamp when the URL expires. */
    private LocalDateTime expiresAt;
}
