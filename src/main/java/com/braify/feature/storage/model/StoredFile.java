package com.braify.feature.storage.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * Registry entry for one object in cloud storage. Every file Braify keeps (e-sign PDFs,
 * attachments, logos, avatars, template images, …) is stored through
 * {@link com.braify.feature.storage.service.StorageService} and referenced elsewhere only by
 * {@link #id}. Bucket and key never leave the backend — clients get short-lived pre-signed URLs.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "stored_files")
public class StoredFile {

    /** Random UUID — also the public handle, so it must not be guessable. */
    @Id
    private String id;

    /** Owning organisation; {@code null} for platform-level objects (e.g. a Platform Admin's avatar). */
    @Indexed
    private String organizationId;

    private StoragePurpose purpose;

    private String bucket;
    private String storageKey;
    private String provider;          // AWS | AZURE | GCP

    private String originalFilename;
    private String contentType;
    private long   sizeBytes;

    /**
     * What the file belongs to once a feature adopts it, e.g. {@code esign-doc:<id>}. Null for an
     * upload nobody has claimed yet. See {@link com.braify.feature.storage.service.StorageService#claim}.
     */
    @Indexed
    private String ownerRef;

    /** User id (or "api-key:…", "system", "signer:<email>") that created the file. */
    private String createdBy;
    private LocalDateTime createdAt;

    /** Soft delete — the object itself is removed from the bucket at the same time. */
    private LocalDateTime deletedAt;
}
