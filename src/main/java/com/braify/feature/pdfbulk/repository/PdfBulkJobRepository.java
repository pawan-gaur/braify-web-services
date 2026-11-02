package com.braify.feature.pdfbulk.repository;

import com.braify.feature.pdfbulk.model.PdfBulkJob;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PdfBulkJobRepository extends MongoRepository<PdfBulkJob, String> {

    Page<PdfBulkJob> findByOrgIdOrderByCreatedAtDesc(String orgId, Pageable pageable);

    Page<PdfBulkJob> findByOrgIdAndCreatedByOrderByCreatedAtDesc(String orgId, String createdBy, Pageable pageable);

    Optional<PdfBulkJob> findByIdAndOrgId(String id, String orgId);

    List<PdfBulkJob> findByStatusInAndCreatedAtBefore(Collection<PdfBulkJob.Status> statuses, LocalDateTime before);

    List<PdfBulkJob> findByTempZipPathNotNullAndFinishedAtBefore(LocalDateTime before);
}
