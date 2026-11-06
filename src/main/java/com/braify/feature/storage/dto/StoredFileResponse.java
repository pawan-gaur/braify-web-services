package com.braify.feature.storage.dto;

import com.braify.feature.storage.model.StoragePurpose;
import com.braify.feature.storage.model.StoredFile;
import lombok.Builder;
import lombok.Data;

/** What clients see of a stored file: its id and metadata — never the bucket or key. */
@Data
@Builder
public class StoredFileResponse {

    private String         id;
    private StoragePurpose purpose;
    private String         filename;
    private String         contentType;
    private long           sizeBytes;
    /** Stable, login-free URL ({@code /api/public/files/{id}}) for public purposes; else null. */
    private String         publicUrl;

    public static StoredFileResponse from(StoredFile f) {
        return StoredFileResponse.builder()
                .id(f.getId())
                .purpose(f.getPurpose())
                .filename(f.getOriginalFilename())
                .contentType(f.getContentType())
                .sizeBytes(f.getSizeBytes())
                .publicUrl(f.getPurpose().publicRead() ? publicPath(f.getId()) : null)
                .build();
    }

    /** App-relative path of the public redirect endpoint for a public file. */
    public static String publicPath(String fileId) {
        return "/api/public/files/" + fileId;
    }
}
