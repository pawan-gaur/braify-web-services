package com.braify.feature.fileupload.repository;

import com.braify.feature.fileupload.model.OrgFile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OrgFileRepository extends MongoRepository<OrgFile, String> {

    Optional<OrgFile> findByFileIdAndOrganizationId(String fileId, String organizationId);

    Optional<OrgFile> findByFileId(String fileId);

    /** All non-deleted files for an org, newest first. */
    Page<OrgFile> findByOrganizationIdAndStatusNot(
            String organizationId,
            OrgFile.FileStatus status,
            Pageable pageable);

    /** Non-deleted files for an org filtered by document type. */
    Page<OrgFile> findByOrganizationIdAndDocumentTypeAndStatusNot(
            String organizationId,
            OrgFile.DocumentType documentType,
            OrgFile.FileStatus status,
            Pageable pageable);

    /** Non-deleted files for an org filtered by status. */
    Page<OrgFile> findByOrganizationIdAndStatus(
            String organizationId,
            OrgFile.FileStatus status,
            Pageable pageable);

    /**
     * Full-text style search on original filename and description
     * for non-deleted files.
     */
    @Query("{ 'organizationId': ?0, 'status': { $ne: 'DELETED' }, " +
           "$or: [ { 'originalFilename': { $regex: ?1, $options: 'i' } }, " +
           "       { 'description':      { $regex: ?1, $options: 'i' } }, " +
           "       { 'tags':             { $regex: ?1, $options: 'i' } } ] }")
    Page<OrgFile> searchByOrganizationId(String organizationId, String keyword, Pageable pageable);

    /** Count active files for an org (used in dashboard summaries). */
    long countByOrganizationIdAndStatus(String organizationId, OrgFile.FileStatus status);

    /** Total storage consumed by active files for an org (summed in application layer). */
    @Query(value = "{ 'organizationId': ?0, 'status': 'ACTIVE' }",
           fields = "{ 'fileSizeMb': 1 }")
    java.util.List<OrgFile> findActiveFileSizes(String organizationId);

    // ── Platform Admin — cross-org queries ───────────────────────────────────

    /** All non-deleted files across every org. */
    Page<OrgFile> findByStatusNot(OrgFile.FileStatus status, Pageable pageable);

    /** All non-deleted files across every org filtered by document type. */
    Page<OrgFile> findByDocumentTypeAndStatusNot(
            OrgFile.DocumentType documentType,
            OrgFile.FileStatus status,
            Pageable pageable);

    /** All files across every org filtered by exact status. */
    Page<OrgFile> findByStatus(OrgFile.FileStatus status, Pageable pageable);

    /** Full-text search across ALL orgs on filename, description and tags. */
    @Query("{ 'status': { $ne: 'DELETED' }, " +
           "$or: [ { 'originalFilename': { $regex: ?0, $options: 'i' } }, " +
           "       { 'description':      { $regex: ?0, $options: 'i' } }, " +
           "       { 'tags':             { $regex: ?0, $options: 'i' } } ] }")
    Page<OrgFile> searchAllOrganizations(String keyword, Pageable pageable);

    /** Total active file count across every org. */
    long countByStatus(OrgFile.FileStatus status);

    /**
     * Active file sizes across every org — lightweight projection
     * (only organizationId + fileSizeMb) used for per-org storage stats.
     */
    @Query(value = "{ 'status': 'ACTIVE' }",
           fields = "{ 'organizationId': 1, 'fileSizeMb': 1 }")
    java.util.List<OrgFile> findAllActiveFileSizes();
}
