package com.braify.feature.fileupload.service;

import com.braify.config.EncryptionService;
import com.braify.feature.audit.model.AuditLog;
import com.braify.feature.audit.service.AuditLogService;
import com.braify.feature.cloudconfig.model.OrgCloudConfig;
import com.braify.feature.fileupload.cloud.CloudDownloadRequest;
import com.braify.feature.fileupload.cloud.CloudUploadRequest;
import com.braify.feature.fileupload.cloud.CloudUploadResult;
import com.braify.feature.fileupload.cloud.CloudUploader;
import com.braify.feature.fileupload.cloud.CloudUploaderFactory;
import com.braify.feature.fileupload.dto.FileDownloadResponse;
import com.braify.feature.fileupload.dto.FileListResponse;
import com.braify.feature.fileupload.dto.FileMetadataRequest;
import com.braify.feature.fileupload.dto.FileUploadResponse;
import com.braify.feature.fileupload.dto.OrgStorageStat;
import com.braify.feature.fileupload.model.OrgFile;
import com.braify.feature.fileupload.repository.OrgFileRepository;
import com.braify.feature.organization.model.Organization;
import com.braify.feature.organization.repository.OrganizationRepository;
import com.braify.feature.quota.exception.QuotaExceededException;
import com.braify.feature.quota.model.OrgQuotaConfig;
import com.braify.feature.quota.service.QuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Core service for file upload / download / management.
 *
 * <h3>Flow — Upload</h3>
 * <ol>
 *   <li>Verify the org has cloud config (throw {@code "Cloud setup not exists"} otherwise).</li>
 *   <li>Validate file type against {@code allowedFileTypes} and size against {@code maxUploadSizeMb}.</li>
 *   <li>Check monthly document quota and storage quota.</li>
 *   <li>Decrypt org credentials, build cloud-specific upload request.</li>
 *   <li>Upload bytes to cloud via {@link CloudUploaderFactory}.</li>
 *   <li>Persist metadata in {@link OrgFileRepository}.</li>
 *   <li>Increment quota counters (docs + storage).</li>
 *   <li>Write audit log.</li>
 * </ol>
 *
 * <h3>Flow — Download</h3>
 * <ol>
 *   <li>Verify org ownership of the file (org isolation).</li>
 *   <li>Verify cloud config exists.</li>
 *   <li>Generate pre-signed URL via the appropriate cloud provider.</li>
 *   <li>Increment download counter.</li>
 *   <li>Write audit log.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    private static final String CLOUD_NOT_CONFIGURED = "Cloud setup not exists";

    private final OrganizationRepository orgRepository;
    private final OrgFileRepository      fileRepository;
    private final CloudUploaderFactory   uploaderFactory;
    private final EncryptionService      encryptionService;
    private final QuotaService           quotaService;
    private final AuditLogService        auditLogService;
    private final FileIdGenerator        fileIdGenerator;

    // ── Upload ────────────────────────────────────────────────────────────────

    /**
     * Uploads a file to the org's configured cloud storage.
     *
     * @param orgId       target organisation ID
     * @param file        multipart file from the request
     * @param meta        optional metadata (folder, type, description, tags)
     * @param uploader    identity string of the caller, e.g. email or "api-key:AKIAXXXX"
     * @param createdById userId of the AppUser performing the upload; null for API-key uploads
     * @return persisted file metadata
     */
    public FileUploadResponse upload(String orgId,
                                     MultipartFile file,
                                     FileMetadataRequest meta,
                                     String uploader,
                                     String createdById) {
        Organization org = requireOrg(orgId);
        OrgCloudConfig cfg = requireCloudConfig(org);

        // ── Validate file ─────────────────────────────────────────────────────
        validateFile(file, cfg);

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read file bytes: " + e.getMessage(), e);
        }

        long   fileSizeBytes = bytes.length;
        double fileSizeMb    = Math.round((fileSizeBytes / (1024.0 * 1024.0)) * 1000.0) / 1000.0;

        // ── Quota checks ──────────────────────────────────────────────────────
        quotaService.checkAndIncrementDocs(orgId);
        checkStorageQuota(orgId, fileSizeMb);

        // ── Build storage key ─────────────────────────────────────────────────
        String fileId     = fileIdGenerator.next();
        String basePath   = buildBasePath(cfg, orgId, fileId, file.getOriginalFilename());

        // ── Decrypt credentials ───────────────────────────────────────────────
        String plainAccessKey = encryptionService.decryptSafe(cfg.getAccessKey());
        String plainSecretKey = encryptionService.decryptSafe(cfg.getSecretKey());

        // ── Upload to cloud ───────────────────────────────────────────────────
        CloudUploader uploader2 = uploaderFactory.get(cfg.getCloud());
        CloudUploadRequest uploadReq = buildUploadRequest(cfg, basePath, bytes,
                file.getContentType(), file.getOriginalFilename(),
                plainAccessKey, plainSecretKey);
        CloudUploadResult result = uploader2.upload(uploadReq);

        // ── Persist metadata ──────────────────────────────────────────────────
        OrgFile orgFile = OrgFile.builder()
                .fileId(fileId)
                .organizationId(orgId)
                .uploadedBy(uploader)
                .createdBy(createdById)
                .originalFilename(file.getOriginalFilename())
                .storageKey(result.getStorageKey())
                .bucket(result.getBucket())
                .cloudProvider(toModelProvider(cfg.getCloud()))
                .contentType(file.getContentType())
                .fileSizeBytes(fileSizeBytes)
                .fileSizeMb(fileSizeMb)
                .folder(meta != null ? meta.getFolder() : null)
                .documentType(parseDocumentType(meta != null ? meta.getDocumentType() : null))
                .documentExpiryDate(meta != null ? meta.getDocumentExpiryDate() : null)
                .description(meta != null ? meta.getDescription() : null)
                .tags(meta != null ? meta.getTags() : null)
                .status(OrgFile.FileStatus.ACTIVE)
                .build();

        OrgFile saved = fileRepository.save(orgFile);

        // ── Increment storage quota ───────────────────────────────────────────
        quotaService.incrementStorage(orgId, fileSizeMb);

        // ── Audit log ─────────────────────────────────────────────────────────
        auditLogService.log(
                orgId, org.getName(),
                AuditLog.Action.CREATED, AuditLog.ResourceType.DOCUMENT,
                1,
                Map.of("action",   "FILE_UPLOADED",
                       "fileId",   fileId,
                       "filename", file.getOriginalFilename() != null ? file.getOriginalFilename() : "",
                       "sizeMb",   String.valueOf(fileSizeMb),
                       "cloud",    cfg.getCloud().name()),
                uploader,
                orgId
        );

        log.info("File uploaded: org={} fileId={} filename={} size={}MB cloud={}",
                orgId, fileId, file.getOriginalFilename(), fileSizeMb, cfg.getCloud());

        return FileUploadResponse.from(saved);
    }

    /**
     * Backward-compatible overload — createdById defaults to null.
     * Used by API-key callers where no AppUser identity is available.
     */
    public FileUploadResponse upload(String orgId,
                                     MultipartFile file,
                                     FileMetadataRequest meta,
                                     String uploader) {
        return upload(orgId, file, meta, uploader, null);
    }

    // ── Download ──────────────────────────────────────────────────────────────

    /**
     * Generates a pre-signed download URL for a file owned by {@code orgId}.
     *
     * @param orgId    the requesting organisation (enforces org isolation)
     * @param fileId   human-readable file ID (e.g. {@code F2026052000000001})
     * @param requester identity string of the caller
     * @return pre-signed URL and expiry metadata
     */
    public FileDownloadResponse download(String orgId, String fileId, String requester) {
        OrgFile file = requireFile(orgId, fileId);

        Organization org = requireOrg(orgId);
        OrgCloudConfig cfg = requireCloudConfig(org);

        int ttlSeconds = cfg.getPresignedUrlExpiration() != null
                ? cfg.getPresignedUrlExpiration() * 60   // stored as minutes
                : 3600;                                   // default 1 hour

        String plainAccessKey = encryptionService.decryptSafe(cfg.getAccessKey());
        String plainSecretKey = encryptionService.decryptSafe(cfg.getSecretKey());

        CloudUploader cloudUploader = uploaderFactory.get(cfg.getCloud());
        CloudDownloadRequest dlReq = buildDownloadRequest(cfg, file.getStorageKey(),
                ttlSeconds, plainAccessKey, plainSecretKey);

        String presignedUrl = cloudUploader.generatePresignedUrl(dlReq);

        // Increment download counter (non-atomic, best-effort)
        file.setDownloadCount(file.getDownloadCount() + 1);
        fileRepository.save(file);

        // Audit log
        auditLogService.log(
                orgId, org.getName(),
                AuditLog.Action.READ, AuditLog.ResourceType.DOCUMENT,
                0,
                Map.of("action", "FILE_DOWNLOADED",
                       "fileId", fileId,
                       "filename", file.getOriginalFilename() != null ? file.getOriginalFilename() : ""),
                requester,
                orgId
        );

        log.info("Download URL generated: org={} fileId={} ttl={}s", orgId, fileId, ttlSeconds);

        return FileDownloadResponse.builder()
                .fileId(fileId)
                .originalFilename(file.getOriginalFilename())
                .downloadUrl(presignedUrl)
                .expiresInSeconds(ttlSeconds)
                .expiresAt(LocalDateTime.now().plusSeconds(ttlSeconds))
                .build();
    }

    // ── List / Dashboard ──────────────────────────────────────────────────────

    /**
     * Returns a paginated list of files for the org dashboard.
     *
     * @param orgId        the org to list files for
     * @param page         0-based page number
     * @param size         page size (capped at 100)
     * @param documentType optional filter by document type
     * @param status       optional filter by file status ("ACTIVE", "ARCHIVED", "DELETED")
     * @param keyword      optional full-text search against name, description, and tags
     * @param sortBy       field to sort by (default: {@code createdAt})
     * @param sortDir      sort direction ("ASC" or "DESC", default: "DESC")
     */
    public FileListResponse listFiles(String orgId,
                                      int page,
                                      int size,
                                      String documentType,
                                      String status,
                                      String keyword,
                                      String sortBy,
                                      String sortDir) {
        int   cappedSize  = Math.min(size, 100);
        Sort  sort        = Sort.by("DESC".equalsIgnoreCase(sortDir) ? Sort.Direction.DESC : Sort.Direction.ASC,
                                    sortBy != null ? sortBy : "createdAt");
        Pageable pageable = PageRequest.of(page, cappedSize, sort);

        Page<OrgFile> resultPage;

        if (keyword != null && !keyword.isBlank()) {
            resultPage = fileRepository.searchByOrganizationId(orgId, keyword.trim(), pageable);
        } else if (documentType != null && !documentType.isBlank()) {
            OrgFile.DocumentType dt = parseDocumentType(documentType);
            resultPage = (dt != null)
                    ? fileRepository.findByOrganizationIdAndDocumentTypeAndStatusNot(
                            orgId, dt, OrgFile.FileStatus.DELETED, pageable)
                    : fileRepository.findByOrganizationIdAndStatusNot(
                            orgId, OrgFile.FileStatus.DELETED, pageable);
        } else if (status != null && !status.isBlank()) {
            OrgFile.FileStatus fs = parseFileStatus(status);
            resultPage = (fs != null)
                    ? fileRepository.findByOrganizationIdAndStatus(orgId, fs, pageable)
                    : fileRepository.findByOrganizationIdAndStatusNot(
                            orgId, OrgFile.FileStatus.DELETED, pageable);
        } else {
            resultPage = fileRepository.findByOrganizationIdAndStatusNot(
                    orgId, OrgFile.FileStatus.DELETED, pageable);
        }

        List<FileUploadResponse> items = resultPage.getContent()
                .stream()
                .map(FileUploadResponse::from)
                .toList();

        // Dashboard totals
        long   totalActive    = fileRepository.countByOrganizationIdAndStatus(orgId, OrgFile.FileStatus.ACTIVE);
        double totalStorageMb = fileRepository.findActiveFileSizes(orgId)
                .stream()
                .mapToDouble(OrgFile::getFileSizeMb)
                .sum();

        return FileListResponse.builder()
                .files(items)
                .totalElements(resultPage.getTotalElements())
                .totalPages(resultPage.getTotalPages())
                .currentPage(resultPage.getNumber())
                .pageSize(resultPage.getSize())
                .last(resultPage.isLast())
                .totalActiveFiles(totalActive)
                .totalStorageMb(Math.round(totalStorageMb * 1000.0) / 1000.0)
                .build();
    }

    // ── Platform Admin — cross-org list ──────────────────────────────────────

    /**
     * Lists files across ALL organisations for the Platform Admin dashboard.
     *
     * @param filterOrgId  optional — when set, restricts results to that single org
     * @param documentType optional document type filter
     * @param status       optional file status filter
     * @param keyword      optional keyword search (filename / description / tags)
     * @param sortBy       field to sort by
     * @param sortDir      "ASC" or "DESC"
     */
    public FileListResponse listAllFiles(int page,
                                         int size,
                                         String filterOrgId,
                                         String documentType,
                                         String status,
                                         String keyword,
                                         String sortBy,
                                         String sortDir) {
        int      cappedSize = Math.min(size, 100);
        Sort     sort       = Sort.by("DESC".equalsIgnoreCase(sortDir) ? Sort.Direction.DESC : Sort.Direction.ASC,
                                      sortBy != null ? sortBy : "createdAt");
        Pageable pageable   = PageRequest.of(page, cappedSize, sort);

        Page<OrgFile> resultPage;

        // If a specific org is selected, delegate to the existing org-scoped queries
        if (filterOrgId != null && !filterOrgId.isBlank()) {
            return listFiles(filterOrgId, page, size, documentType, status, keyword, sortBy, sortDir);
        }

        // Cross-org queries
        if (keyword != null && !keyword.isBlank()) {
            resultPage = fileRepository.searchAllOrganizations(keyword.trim(), pageable);
        } else if (documentType != null && !documentType.isBlank()) {
            OrgFile.DocumentType dt = parseDocumentType(documentType);
            resultPage = (dt != null)
                    ? fileRepository.findByDocumentTypeAndStatusNot(dt, OrgFile.FileStatus.DELETED, pageable)
                    : fileRepository.findByStatusNot(OrgFile.FileStatus.DELETED, pageable);
        } else if (status != null && !status.isBlank()) {
            OrgFile.FileStatus fs = parseFileStatus(status);
            resultPage = (fs != null)
                    ? fileRepository.findByStatus(fs, pageable)
                    : fileRepository.findByStatusNot(OrgFile.FileStatus.DELETED, pageable);
        } else {
            resultPage = fileRepository.findByStatusNot(OrgFile.FileStatus.DELETED, pageable);
        }

        List<FileUploadResponse> items = resultPage.getContent().stream()
                .map(FileUploadResponse::from)
                .toList();

        // ── Cross-org totals ──────────────────────────────────────────────────
        long totalActive = fileRepository.countByStatus(OrgFile.FileStatus.ACTIVE);

        // ── Per-org storage stats ─────────────────────────────────────────────
        List<OrgFile> allActiveSizes = fileRepository.findAllActiveFileSizes();

        // Group by orgId: { orgId -> {count, storageMb} }
        Map<String, long[]> orgAgg = new HashMap<>(); // [0]=count, [1]=bits of double storageMb
        for (OrgFile f : allActiveSizes) {
            orgAgg.computeIfAbsent(f.getOrganizationId(), k -> new long[]{0, 0});
            long[] v = orgAgg.get(f.getOrganizationId());
            v[0]++;
            v[1] = Double.doubleToLongBits(Double.longBitsToDouble(v[1]) + f.getFileSizeMb());
        }

        // Resolve org names from repository
        Map<String, String> orgNames = new HashMap<>();
        orgRepository.findAllById(orgAgg.keySet())
                .forEach(o -> orgNames.put(o.getId(), o.getName()));

        List<OrgStorageStat> orgStats = orgAgg.entrySet().stream()
                .map(e -> OrgStorageStat.builder()
                        .orgId(e.getKey())
                        .orgName(orgNames.getOrDefault(e.getKey(), e.getKey()))
                        .fileCount(e.getValue()[0])
                        .storageMb(Math.round(Double.longBitsToDouble(e.getValue()[1]) * 1000.0) / 1000.0)
                        .build())
                .sorted(Comparator.comparingDouble(OrgStorageStat::getStorageMb).reversed())
                .collect(Collectors.toList());

        double totalStorageMb = orgStats.stream().mapToDouble(OrgStorageStat::getStorageMb).sum();

        return FileListResponse.builder()
                .files(items)
                .totalElements(resultPage.getTotalElements())
                .totalPages(resultPage.getTotalPages())
                .currentPage(resultPage.getNumber())
                .pageSize(resultPage.getSize())
                .last(resultPage.isLast())
                .totalActiveFiles(totalActive)
                .totalStorageMb(Math.round(totalStorageMb * 1000.0) / 1000.0)
                .orgStats(orgStats)
                .build();
    }

    // ── Get single file metadata ──────────────────────────────────────────────

    public FileUploadResponse getFile(String orgId, String fileId) {
        return FileUploadResponse.from(requireFile(orgId, fileId));
    }

    // ── Soft delete ───────────────────────────────────────────────────────────

    /**
     * Soft-deletes a file (marks it {@code DELETED}).
     * The object is NOT removed from cloud storage — use a cloud lifecycle policy
     * or a scheduled cleanup job for that.
     */
    public void deleteFile(String orgId, String fileId, String deletedBy) {
        OrgFile file = requireFile(orgId, fileId);
        Organization org = requireOrg(orgId);

        file.setStatus(OrgFile.FileStatus.DELETED);
        file.setDeletedAt(LocalDateTime.now());
        fileRepository.save(file);

        auditLogService.log(
                orgId, org.getName(),
                AuditLog.Action.DELETED, AuditLog.ResourceType.DOCUMENT,
                0,
                Map.of("action",   "FILE_DELETED",
                       "fileId",   fileId,
                       "filename", file.getOriginalFilename() != null ? file.getOriginalFilename() : ""),
                deletedBy,
                orgId
        );

        log.info("File soft-deleted: org={} fileId={} by={}", orgId, fileId, deletedBy);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Organization requireOrg(String orgId) {
        return orgRepository.findById(orgId)
                .orElseThrow(() -> new RuntimeException("Organization not found: " + orgId));
    }

    private OrgCloudConfig requireCloudConfig(Organization org) {
        OrgCloudConfig cfg = org.getCloudConfig();
        if (cfg == null || cfg.getCloud() == null) {
            throw new RuntimeException(CLOUD_NOT_CONFIGURED);
        }
        return cfg;
    }

    private OrgFile requireFile(String orgId, String fileId) {
        return fileRepository.findByFileIdAndOrganizationId(fileId, orgId)
                .orElseThrow(() -> new RuntimeException(
                        "File not found: " + fileId + " (org: " + orgId + ")"));
    }

    private void validateFile(MultipartFile file, OrgCloudConfig cfg) {
        if (file == null || file.isEmpty()) {
            throw new RuntimeException("File must not be empty");
        }

        // Check allowed file types
        List<String> allowed = cfg.getAllowedFileTypes();
        if (allowed != null && !allowed.isEmpty()) {
            String originalFilename = file.getOriginalFilename();
            String extension = originalFilename != null && originalFilename.contains(".")
                    ? originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase()
                    : "";
            if (!allowed.contains(extension)) {
                throw new RuntimeException(
                        "File type '" + extension + "' is not allowed. " +
                        "Allowed types: " + String.join(", ", allowed));
            }
        }

        // Check file size
        if (cfg.getMaxUploadSizeMb() != null && cfg.getMaxUploadSizeMb() > 0) {
            double fileMb = file.getSize() / (1024.0 * 1024.0);
            if (fileMb > cfg.getMaxUploadSizeMb()) {
                throw new RuntimeException(
                        String.format("File size %.2f MB exceeds the maximum allowed size of %d MB",
                                fileMb, cfg.getMaxUploadSizeMb()));
            }
        }
    }

    private void checkStorageQuota(String orgId, double additionalMb) {
        OrgQuotaConfig quotaCfg = quotaService.getConfig(orgId);
        if (quotaCfg.getMaxStorageMb() == -1) return; // unlimited

        double usedMb = fileRepository.findActiveFileSizes(orgId)
                .stream().mapToDouble(OrgFile::getFileSizeMb).sum();

        if (usedMb + additionalMb > quotaCfg.getMaxStorageMb()) {
            throw new QuotaExceededException(
                    "Storage", (long) quotaCfg.getMaxStorageMb(), (long) (usedMb + additionalMb));
        }
    }

    /**
     * Builds the object storage key.
     * Pattern: {@code <cfg.path>/<orgId>/<fileId>/<sanitisedFilename>}
     */
    private String buildBasePath(OrgCloudConfig cfg, String orgId, String fileId, String filename) {
        String base = (cfg.getPath() != null && !cfg.getPath().isBlank())
                ? cfg.getPath().replaceAll("/$", "") + "/" + orgId + "/" + fileId
                : orgId + "/" + fileId;
        String safeFilename = sanitize(filename != null ? filename : "file");
        return base + "/" + safeFilename;
    }

    private CloudUploadRequest buildUploadRequest(OrgCloudConfig cfg,
                                                   String storageKey,
                                                   byte[] data,
                                                   String contentType,
                                                   String originalFilename,
                                                   String plainAccessKey,
                                                   String plainSecretKey) {
        return CloudUploadRequest.builder()
                .bucket(cfg.getBucket())
                .storageKey(storageKey)
                .data(data)
                .contentType(contentType)
                .originalFilename(originalFilename)
                .awsRegion(cfg.getAwsRegion())
                .awsAccessKeyId(plainAccessKey)
                .awsSecretAccessKey(plainSecretKey)
                .azureConnectionString(plainAccessKey)    // Azure: accessKey = connection string
                .gcpServiceAccountJson(plainSecretKey)    // GCP:   secretKey = SA JSON
                .build();
    }

    private CloudDownloadRequest buildDownloadRequest(OrgCloudConfig cfg,
                                                       String storageKey,
                                                       int ttlSeconds,
                                                       String plainAccessKey,
                                                       String plainSecretKey) {
        return CloudDownloadRequest.builder()
                .bucket(cfg.getBucket())
                .storageKey(storageKey)
                .expirationSeconds(ttlSeconds)
                .awsRegion(cfg.getAwsRegion())
                .awsAccessKeyId(plainAccessKey)
                .awsSecretAccessKey(plainSecretKey)
                .azureConnectionString(plainAccessKey)
                .gcpServiceAccountJson(plainSecretKey)
                .build();
    }

    private OrgFile.CloudProvider toModelProvider(OrgCloudConfig.CloudProvider p) {
        return OrgFile.CloudProvider.valueOf(p.name());
    }

    private OrgFile.DocumentType parseDocumentType(String value) {
        if (value == null || value.isBlank()) return OrgFile.DocumentType.OTHER;
        try {
            return OrgFile.DocumentType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return OrgFile.DocumentType.OTHER;
        }
    }

    private OrgFile.FileStatus parseFileStatus(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return OrgFile.FileStatus.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }
}
