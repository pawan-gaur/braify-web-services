package com.braify.feature.storage.service;

import com.braify.config.EncryptionService;
import com.braify.feature.cloudconfig.model.OrgCloudConfig;
import com.braify.feature.fileupload.cloud.CloudDownloadRequest;
import com.braify.feature.fileupload.cloud.CloudUploadRequest;
import com.braify.feature.fileupload.cloud.CloudUploaderFactory;
import com.braify.feature.fileupload.cloud.StorageConfigResolver;
import com.braify.feature.organization.model.Organization;
import com.braify.feature.organization.repository.OrganizationRepository;
import com.braify.feature.storage.model.StoragePurpose;
import com.braify.feature.storage.model.StoredFile;
import com.braify.feature.storage.repository.StoredFileRepository;
import com.braify.feature.user.model.AppUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The one way to keep a file. Stores bytes in the org's bucket (or managed platform storage, see
 * {@link StorageConfigResolver}), records a {@link StoredFile}, and from then on everything is
 * addressed by the file id: {@link #read}, {@link #presignedUrl}, {@link #delete}.
 *
 * <p>Bucket names and object keys never leave this service, so buckets can stay private —
 * browsers only ever get short-lived pre-signed URLs.
 *
 * <p>Object key: {@code <path>/<orgId | "platform">/<purpose folder>/<fileId>/<filename>}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {

    private static final String PLATFORM_SEGMENT = "platform";

    private final StoredFileRepository   repository;
    private final MongoTemplate          mongo;
    private final OrganizationRepository orgRepository;
    private final StorageConfigResolver  storageResolver;
    private final CloudUploaderFactory   uploaderFactory;
    private final EncryptionService      encryptionService;

    /** A pre-signed GET URL and how long it stays valid. */
    public record PresignedUrl(String url, int expiresInSeconds) {}

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Validates a browser upload against the purpose's rules (client-uploadable, size, type),
     * then stores it. Callers still do their own feature checks (e.g. e-sign attachment types).
     */
    public StoredFile upload(String orgId, StoragePurpose purpose, MultipartFile file, String createdBy) {
        if (!purpose.clientUpload())
            throw new IllegalArgumentException("Files of type " + purpose + " can't be uploaded directly");
        if (file == null || file.isEmpty())
            throw new IllegalArgumentException("File must not be empty");
        if (purpose.maxMb() > 0 && file.getSize() > purpose.maxMb() * 1024L * 1024L)
            throw new IllegalArgumentException(String.format(
                    "File is %.1f MB; the maximum is %d MB", file.getSize() / 1024.0 / 1024.0, purpose.maxMb()));
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read the uploaded file: " + e.getMessage());
        }
        String type = file.getContentType() != null ? file.getContentType().toLowerCase() : "";
        // PDFs are recognised by content: browsers and remote servers often send them as
        // application/octet-stream or with no type at all.
        if (purpose.contentTypes().equals(PDF_ONLY)) {
            if (!isPdf(bytes)) throw new IllegalArgumentException("The file is not a PDF");
            type = "application/pdf";
        } else if (!purpose.contentTypes().isEmpty() && !purpose.contentTypes().contains(type)) {
            throw new IllegalArgumentException("File type '" + type + "' is not allowed here");
        }
        return store(orgId, purpose, bytes, file.getOriginalFilename(), type, createdBy);
    }

    /** Stores server-side bytes (generated PDFs, decoded uploads) and returns the new record. */
    public StoredFile store(String orgId, StoragePurpose purpose, byte[] bytes,
                            String filename, String contentType, String createdBy) {
        return store(orgId, purpose, bytes, filename, contentType, createdBy, null);
    }

    /** {@link #store} for a file that already belongs to something ({@code ownerRef}, e.g. {@code esign-doc:<id>}). */
    public StoredFile store(String orgId, StoragePurpose purpose, byte[] bytes,
                            String filename, String contentType, String createdBy, String ownerRef) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("File must not be empty");
        OrgCloudConfig cfg = writeConfig(orgId);
        String id  = UUID.randomUUID().toString();
        String name = filename != null && !filename.isBlank() ? filename : "file";
        String key = keyBase(cfg, orgId) + "/" + purpose.folder() + "/" + id + "/" + sanitize(name);
        String type = contentType != null && !contentType.isBlank() ? contentType : "application/octet-stream";

        String accessKey = encryptionService.decryptSafe(cfg.getAccessKey());
        String secretKey = encryptionService.decryptSafe(cfg.getSecretKey());
        uploaderFactory.get(cfg.getCloud()).upload(CloudUploadRequest.builder()
                .bucket(cfg.getBucket())
                .storageKey(key)
                .data(bytes)
                .contentType(type)
                .originalFilename(name)
                .awsRegion(cfg.getAwsRegion())
                .awsAccessKeyId(accessKey)
                .awsSecretAccessKey(secretKey)
                .azureConnectionString(accessKey)   // Azure: accessKey = connection string
                .gcpServiceAccountJson(secretKey)   // GCP:   secretKey = service-account JSON
                .useDefaultCredentials(cfg.isPlatformManaged())
                .build());

        StoredFile saved = repository.save(StoredFile.builder()
                .id(id)
                .organizationId(orgId)
                .purpose(purpose)
                .bucket(cfg.getBucket())
                .storageKey(key)
                .provider(cfg.getCloud().name())
                .originalFilename(name)
                .contentType(type)
                .sizeBytes(bytes.length)
                .ownerRef(ownerRef)
                .createdBy(createdBy)
                .createdAt(LocalDateTime.now())
                .build());
        log.info("Stored file: id={} org={} purpose={} size={}B cloud={}",
                id, orgId, purpose, bytes.length, cfg.getCloud());
        return saved;
    }

    /**
     * Hands an upload to its owner. The first claim adopts the file as-is (atomically, so two
     * owners can never both adopt it); a file that already has an owner (e.g. one PDF reused for
     * every row of a bulk send) is copied, so each owner can later delete its own file without
     * touching anyone else's.
     */
    public StoredFile claim(StoredFile f, String ownerRef) {
        return claim(f, ownerRef, null);
    }

    /** {@link #claim} with the file's bytes when the caller already read them (saves a download if a copy is needed). */
    public StoredFile claim(StoredFile f, String ownerRef, byte[] bytes) {
        if (ownerRef.equals(f.getOwnerRef())) return f;
        StoredFile adopted = mongo.findAndModify(
                new Query(Criteria.where("_id").is(f.getId()).and("ownerRef").is(null)),
                new Update().set("ownerRef", ownerRef),
                FindAndModifyOptions.options().returnNew(true), StoredFile.class);
        if (adopted != null) return adopted;
        return store(f.getOrganizationId(), f.getPurpose(), bytes != null ? bytes : read(f),
                f.getOriginalFilename(), f.getContentType(), f.getCreatedBy(), ownerRef);
    }

    /**
     * Records an object that is already in a bucket (written before this registry existed) so it
     * can be addressed by id. Used by the legacy-data migration; nothing is uploaded.
     */
    public StoredFile registerExisting(String orgId, StoragePurpose purpose, String bucket, String storageKey,
                                       String provider, String filename, String contentType, long sizeBytes,
                                       String ownerRef) {
        return repository.save(StoredFile.builder()
                .id(UUID.randomUUID().toString())
                .organizationId(orgId)
                .purpose(purpose)
                .bucket(bucket)
                .storageKey(storageKey)
                .provider(provider)
                .originalFilename(filename)
                .contentType(contentType)
                .sizeBytes(sizeBytes)
                .ownerRef(ownerRef)
                .createdBy("migration")
                .createdAt(LocalDateTime.now())
                .build());
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /** The live record, or a 404 ("not found"). */
    public StoredFile get(String fileId) {
        if (fileId == null || fileId.isBlank()) throw new IllegalArgumentException("fileId is required");
        return repository.findByIdAndDeletedAtIsNull(fileId)
                .orElseThrow(() -> new RuntimeException("File not found: " + fileId));
    }

    /**
     * The live record, after checking {@code caller} may use it: Platform Admins any file, everyone
     * else files of their own organisation (or, for no-org files, files they created).
     */
    public StoredFile getForUser(String fileId, AppUser caller) {
        StoredFile f = get(fileId);
        assertAccess(f, caller);
        return f;
    }

    /** Same as {@link #getForUser} but also requires the given purpose — e.g. a create call claiming an upload. */
    public StoredFile getForUser(String fileId, StoragePurpose purpose, AppUser caller) {
        StoredFile f = getForUser(fileId, caller);
        if (f.getPurpose() != purpose)
            throw new IllegalArgumentException("File " + fileId + " is not a " + purpose + " upload");
        return f;
    }

    public void assertAccess(StoredFile f, AppUser caller) {
        if (caller == null) throw new AccessDeniedException("Access denied");
        if (caller.getRole() == AppUser.Role.PLATFORM_ADMIN) return;
        boolean ok = f.getOrganizationId() != null
                ? f.getOrganizationId().equals(caller.getOrganizationId())
                : caller.getId() != null && caller.getId().equals(f.getCreatedBy());
        if (!ok) throw new AccessDeniedException("Access denied");
    }

    /** The object's bytes, for server-side use (stamping PDFs, email attachments, rendering). */
    public byte[] read(String fileId) {
        return read(get(fileId));
    }

    public byte[] read(StoredFile f) {
        OrgCloudConfig cfg = readConfig(f);
        return uploaderFactory.get(cfg.getCloud()).download(downloadRequest(cfg, f, 0));
    }

    /** A short-lived pre-signed GET URL — the only way a browser reaches an object. */
    public PresignedUrl presignedUrl(String fileId) {
        return presignedUrl(get(fileId));
    }

    public PresignedUrl presignedUrl(StoredFile f) {
        OrgCloudConfig cfg = readConfig(f);
        int ttl = cfg.getPresignedUrlExpiration() != null
                ? cfg.getPresignedUrlExpiration() * 60   // stored in minutes
                : 3600;
        String url = uploaderFactory.get(cfg.getCloud()).generatePresignedUrl(downloadRequest(cfg, f, ttl));
        return new PresignedUrl(url, ttl);
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    /** Removes the object and soft-deletes the record. Never throws; null / unknown ids are ignored. */
    public void deleteQuietly(String fileId) {
        if (fileId == null || fileId.isBlank()) return;
        repository.findByIdAndDeletedAtIsNull(fileId).ifPresent(f -> {
            deleteObjectQuietly(f);
            f.setDeletedAt(LocalDateTime.now());
            repository.save(f);
        });
    }

    /** Removes just the bucket object (also works for an unsaved, pre-registry reference). Never throws. */
    public void deleteObjectQuietly(StoredFile f) {
        try {
            OrgCloudConfig cfg = readConfig(f);
            uploaderFactory.get(cfg.getCloud()).delete(downloadRequest(cfg, f, 0));
        } catch (Exception e) {
            log.warn("Stored object delete failed (id={} key={}): {}", f.getId(), f.getStorageKey(), e.getMessage());
        }
    }

    /** True when {@code orgId} (null = platform) has somewhere to store files. */
    public boolean isAvailable(String orgId) {
        if (orgId == null) return storageResolver.platformEnabled();
        return orgRepository.findById(orgId).map(storageResolver::isAvailable).orElse(false);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private OrgCloudConfig writeConfig(String orgId) {
        return orgId == null ? storageResolver.forPlatformWrite() : storageResolver.forWrite(requireOrg(orgId));
    }

    private OrgCloudConfig readConfig(StoredFile f) {
        return f.getOrganizationId() == null
                ? storageResolver.forPlatformRead(f.getBucket())
                : storageResolver.forRead(requireOrg(f.getOrganizationId()), f.getBucket());
    }

    private Organization requireOrg(String orgId) {
        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new RuntimeException("Organisation not found: " + orgId));
        if (!storageResolver.isAvailable(org)) throw new IllegalStateException(StorageConfigResolver.NOT_CONFIGURED);
        return org;
    }

    private CloudDownloadRequest downloadRequest(OrgCloudConfig cfg, StoredFile f, int ttlSeconds) {
        String accessKey = encryptionService.decryptSafe(cfg.getAccessKey());
        String secretKey = encryptionService.decryptSafe(cfg.getSecretKey());
        return CloudDownloadRequest.builder()
                .bucket(f.getBucket() != null ? f.getBucket() : cfg.getBucket())
                .storageKey(f.getStorageKey())
                .expirationSeconds(ttlSeconds)
                .awsRegion(cfg.getAwsRegion())
                .awsAccessKeyId(accessKey)
                .awsSecretAccessKey(secretKey)
                .azureConnectionString(accessKey)
                .gcpServiceAccountJson(secretKey)
                .useDefaultCredentials(cfg.isPlatformManaged())
                .build();
    }

    private static String keyBase(OrgCloudConfig cfg, String orgId) {
        String owner = orgId != null ? orgId : PLATFORM_SEGMENT;
        return cfg.getPath() != null && !cfg.getPath().isBlank()
                ? cfg.getPath().replaceAll("/$", "") + "/" + owner
                : owner;
    }

    private static final java.util.Set<String> PDF_ONLY = java.util.Set.of("application/pdf");

    /** PDF header ("%PDF-") within the first 1024 bytes — what PDF readers accept. */
    private static boolean isPdf(byte[] b) {
        if (b == null) return false;
        for (int i = 0, end = Math.min(b.length - 5, 1024); i <= end; i++)
            if (b[i] == '%' && b[i + 1] == 'P' && b[i + 2] == 'D' && b[i + 3] == 'F' && b[i + 4] == '-') return true;
        return false;
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }
}
