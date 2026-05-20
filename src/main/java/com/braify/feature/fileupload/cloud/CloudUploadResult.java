package com.braify.feature.fileupload.cloud;

import lombok.Builder;
import lombok.Data;

/**
 * Result returned by a {@link CloudUploader} after a successful upload.
 */
@Data
@Builder
public class CloudUploadResult {

    /** The bucket / container the file was stored in. */
    private String bucket;

    /** The final storage key (path) of the uploaded object. */
    private String storageKey;

    /** Public or storage URL (if available / applicable). */
    private String publicUrl;
}
