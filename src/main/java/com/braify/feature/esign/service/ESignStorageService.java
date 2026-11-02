package com.braify.feature.esign.service;

import com.braify.feature.esign.model.ESignDocument;
import com.braify.feature.esign.model.ESignTemplate;
import com.braify.feature.storage.model.StoragePurpose;
import com.braify.feature.storage.model.StoredFile;
import com.braify.feature.storage.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * E-sign view of {@link StorageService}: stores source / signed / template PDFs as stored files
 * and resolves a document's PDFs by file id. Documents written before the stored-file registry
 * (raw {@code pdfBucket}/{@code *PdfKey}, or embedded bytes) are still readable until
 * {@code LegacyFileMigration} has moved them.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ESignStorageService {

    private final StorageService storage;

    /** True when the org can store e-sign PDFs (own bucket or managed storage). */
    public boolean isCloudConfigured(String orgId) {
        return orgId != null && storage.isAvailable(orgId);   // e-sign documents always belong to an org
    }

    // ── Write ──────────────────────────────────────────────────────────────────

    /** Stores a source PDF built server-side (decoded upload, rendered template) for a document. */
    public String storeSourcePdf(String orgId, String docId, byte[] bytes, String createdBy) {
        return storage.store(orgId, StoragePurpose.ESIGN_SOURCE, bytes, "source.pdf",
                "application/pdf", createdBy, owner(docId)).getId();
    }

    /** Makes an uploaded file ({@code POST /api/storage/upload}) a document's source PDF. */
    public String adoptSourcePdf(StoredFile upload, byte[] bytes, String docId) {
        return storage.claim(upload, owner(docId), bytes).getId();
    }

    public String storeSignedPdf(ESignDocument doc, byte[] bytes) {
        return storage.store(doc.getOrgId(), StoragePurpose.ESIGN_SIGNED, bytes, "signed.pdf",
                "application/pdf", "system", owner(doc.getId())).getId();
    }

    public String storeTemplatePdf(String orgId, String templateId, byte[] bytes, String createdBy) {
        return storage.store(orgId, StoragePurpose.ESIGN_TEMPLATE, bytes, "template.pdf",
                "application/pdf", createdBy, "esign-template:" + templateId).getId();
    }

    /** Stores a signer's post-signing attachment for a document; returns the file id. */
    public String storeAttachment(ESignDocument doc, byte[] bytes, String fileName, String contentType, String uploadedBy) {
        return storage.store(doc.getOrgId(), StoragePurpose.ESIGN_ATTACHMENT, bytes, fileName,
                contentType, uploadedBy, owner(doc.getId())).getId();
    }

    // ── Read ───────────────────────────────────────────────────────────────────

    /** Bytes of a client attachment (stored file, or LEGACY embedded bytes). */
    public byte[] attachmentBytes(ESignDocument.ClientAttachment att) {
        return att.getFileId() != null ? storage.read(att.getFileId()) : att.getData();
    }

    /** Source PDF bytes (server-side: stamping, attaching to emails). */
    public byte[] resolveSourceBytes(ESignDocument doc) {
        if (doc.getSourceFileId() != null) return storage.read(doc.getSourceFileId());
        if (doc.getSourcePdfKey() != null) return storage.read(legacy(doc.getOrgId(), doc.getPdfBucket(), doc.getSourcePdfKey()));
        return doc.getSourcePdfData();
    }

    /** Signed PDF bytes, or null while the document isn't completed. */
    public byte[] resolveSignedBytes(ESignDocument doc) {
        if (doc.getSignedFileId() != null) return storage.read(doc.getSignedFileId());
        if (doc.getSignedPdfKey() != null) return storage.read(legacy(doc.getOrgId(), doc.getPdfBucket(), doc.getSignedPdfKey()));
        return doc.getSignedPdfData();
    }

    /** Pre-signed URL for the source PDF; null for embedded-bytes legacy documents. */
    public String sourcePresignedUrl(ESignDocument doc) {
        if (doc.getSourceFileId() != null) return storage.presignedUrl(doc.getSourceFileId()).url();
        if (doc.getSourcePdfKey() != null) return storage.presignedUrl(legacy(doc.getOrgId(), doc.getPdfBucket(), doc.getSourcePdfKey())).url();
        return null;
    }

    /** Pre-signed URL for the signed PDF; null when there is none (or it's embedded legacy bytes). */
    public String signedPresignedUrl(ESignDocument doc) {
        if (doc.getSignedFileId() != null) return storage.presignedUrl(doc.getSignedFileId()).url();
        if (doc.getSignedPdfKey() != null) return storage.presignedUrl(legacy(doc.getOrgId(), doc.getPdfBucket(), doc.getSignedPdfKey())).url();
        return null;
    }

    public byte[] templatePdf(ESignTemplate tpl) {
        if (tpl.getPdfFileId() != null) return storage.read(tpl.getPdfFileId());
        return storage.read(legacy(tpl.getOrgId(), tpl.getPdfBucket(), tpl.getPdfKey()));
    }

    /** Best-effort delete of a template's PDF. Never throws. */
    public void deleteTemplatePdf(ESignTemplate tpl) {
        if (tpl.getPdfFileId() != null) { storage.deleteQuietly(tpl.getPdfFileId()); return; }
        if (tpl.getPdfKey() == null) return;
        try {
            storage.deleteObjectQuietly(legacy(tpl.getOrgId(), tpl.getPdfBucket(), tpl.getPdfKey()));
        } catch (Exception e) {
            log.warn("E-sign template PDF delete failed (key={}): {}", tpl.getPdfKey(), e.getMessage());
        }
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private static String owner(String docId) {
        return "esign-doc:" + docId;
    }

    /** An unsaved StoredFile describing a pre-registry object, so StorageService can route it by bucket. */
    private static StoredFile legacy(String orgId, String bucket, String key) {
        return StoredFile.builder().organizationId(orgId).bucket(bucket).storageKey(key).build();
    }
}
