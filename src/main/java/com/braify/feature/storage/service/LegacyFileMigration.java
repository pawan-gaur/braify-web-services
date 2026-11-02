package com.braify.feature.storage.service;

import com.braify.feature.bulkemail.model.BulkEmailJob;
import com.braify.feature.esign.model.ESignDocument;
import com.braify.feature.esign.model.ESignTemplate;
import com.braify.feature.fileupload.model.OrgFile;
import com.braify.feature.organization.model.Organization;
import com.braify.feature.pdf.model.Template;
import com.braify.feature.pdf.model.TemplateVersion;
import com.braify.feature.storage.model.StoragePurpose;
import com.braify.feature.user.model.AppUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * One-off move of every file still kept the old way into {@link StorageService}:
 * <ul>
 *   <li>bytes embedded in Mongo (e-sign PDFs and attachments, bulk-email PDFs, avatars, inline
 *       logos, base64 images inside PDF templates) are uploaded and replaced by a file id;</li>
 *   <li>objects already in a bucket but referenced by raw bucket/key (e-sign PDFs and templates,
 *       logos, Files library) are registered as stored files — nothing is re-uploaded.</li>
 * </ul>
 * Each record is updated with targeted {@code $set}/{@code $unset} so concurrent edits survive.
 * Re-running is safe: only records that still carry legacy fields are picked up. A record whose
 * org has no storage configured is counted as failed and left untouched.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LegacyFileMigration {

    private static final int MAX_ERRORS = 100;
    /** {@code data:image/<type>[;param=…];base64,<payload>} — the payload may be line-wrapped. */
    private static final Pattern DATA_IMAGE = Pattern.compile(
            "data:(image/[a-zA-Z0-9.+-]+)(?:;[a-zA-Z0-9-]+=[^;,\"']*)*;base64,([A-Za-z0-9+/=\\s]+)");

    private final MongoTemplate  mongo;
    private final StorageService storage;

    private final AtomicReference<Report> last = new AtomicReference<>();

    @org.springframework.beans.factory.annotation.Value("${app.base-url:http://localhost:5173}")
    private String publicBaseUrl;

    /** Progress / result of a run. Counts are per migrated record (not per file). */
    public static class Report {
        public final boolean dryRun;
        public final LocalDateTime startedAt = LocalDateTime.now();
        public volatile LocalDateTime finishedAt;
        public volatile boolean running = true;
        public final Map<String, int[]> sections = new LinkedHashMap<>();   // name → [migrated, failed]
        public final List<String> errors = new ArrayList<>();

        Report(boolean dryRun) { this.dryRun = dryRun; }

        synchronized void ok(String section)   { sections.computeIfAbsent(section, k -> new int[2])[0]++; }
        synchronized void fail(String section, String id, Exception e) {
            sections.computeIfAbsent(section, k -> new int[2])[1]++;
            if (errors.size() < MAX_ERRORS) errors.add(section + " " + id + ": " + e.getMessage());
        }

        public synchronized Map<String, Object> toMap() {
            Map<String, Object> s = new LinkedHashMap<>();
            sections.forEach((k, v) -> s.put(k, Map.of(dryRun ? "toMigrate" : "migrated", v[0], "failed", v[1])));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dryRun", dryRun);
            m.put("running", running);
            m.put("startedAt", startedAt.toString());
            m.put("finishedAt", finishedAt != null ? finishedAt.toString() : null);
            m.put("sections", s);
            m.put("errors", List.copyOf(errors));
            return m;
        }
    }

    public Report lastReport() { return last.get(); }

    /** Starts a run in the background. Returns false if one is already running. */
    public synchronized boolean start(boolean dryRun) {
        Report current = last.get();
        if (current != null && current.running) return false;
        Report report = new Report(dryRun);
        last.set(report);
        Thread t = new Thread(() -> run(report), "legacy-file-migration");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private void run(Report r) {
        log.info("Legacy file migration started (dryRun={})", r.dryRun);
        try {
            esignDocuments(r);
            esignTemplates(r);
            orgLogos(r);
            avatars(r);
            bulkEmailJobs(r);
            libraryFiles(r);
            pdfTemplateImages(r);
        } catch (Exception e) {
            log.error("Legacy file migration aborted: {}", e.getMessage(), e);
            r.fail("aborted", "-", e);
        } finally {
            r.running = false;
            r.finishedAt = LocalDateTime.now();
            log.info("Legacy file migration finished: {}", r.toMap());
        }
    }

    // ── E-sign documents: source / signed PDFs + client attachments ─────────────

    private void esignDocuments(Report r) {
        Query q = new Query(new Criteria().orOperator(
                where("sourcePdfKey").ne(null), where("sourcePdfData").ne(null),
                where("signedPdfKey").ne(null), where("signedPdfData").ne(null),
                where("clientAttachments").elemMatch(where("data").exists(true).ne(null))));
        try (Stream<ESignDocument> docs = mongo.stream(q, ESignDocument.class)) {
            docs.forEach(d -> {
                if (r.dryRun) { r.ok("esignDocuments"); return; }
                try {
                    String owner = "esign-doc:" + d.getId();
                    Update u = new Update();
                    String sourceId = d.getSourceFileId();
                    if (sourceId == null && d.getSourcePdfKey() != null)
                        sourceId = storage.registerExisting(d.getOrgId(), StoragePurpose.ESIGN_SOURCE, d.getPdfBucket(),
                                d.getSourcePdfKey(), d.getPdfCloudProvider(), "source.pdf", "application/pdf", 0, owner).getId();
                    else if (sourceId == null && d.getSourcePdfData() != null)
                        sourceId = storage.store(d.getOrgId(), StoragePurpose.ESIGN_SOURCE, d.getSourcePdfData(),
                                "source.pdf", "application/pdf", "migration", owner).getId();
                    if (sourceId != null) u.set("sourceFileId", sourceId).unset("sourcePdfKey").unset("sourcePdfData");

                    String signedId = d.getSignedFileId();
                    if (signedId == null && d.getSignedPdfKey() != null)
                        signedId = storage.registerExisting(d.getOrgId(), StoragePurpose.ESIGN_SIGNED, d.getPdfBucket(),
                                d.getSignedPdfKey(), d.getPdfCloudProvider(), "signed.pdf", "application/pdf", 0, owner).getId();
                    else if (signedId == null && d.getSignedPdfData() != null)
                        signedId = storage.store(d.getOrgId(), StoragePurpose.ESIGN_SIGNED, d.getSignedPdfData(),
                                "signed.pdf", "application/pdf", "migration", owner).getId();
                    if (signedId != null) u.set("signedFileId", signedId).unset("signedPdfKey").unset("signedPdfData");
                    if (sourceId != null) u.unset("pdfBucket").unset("pdfCloudProvider");

                    Query target = byId(d.getId());
                    List<ESignDocument.ClientAttachment> atts = d.getClientAttachments();
                    if (atts != null && atts.stream().anyMatch(a -> a.getData() != null)) {
                        // Only if no attachment was added meanwhile — else the next run picks it up.
                        target.addCriteria(where("clientAttachments").size(atts.size()));
                        for (ESignDocument.ClientAttachment a : atts) {
                            if (a.getData() == null) continue;
                            a.setFileId(storage.store(d.getOrgId(), StoragePurpose.ESIGN_ATTACHMENT, a.getData(),
                                    a.getFileName(), a.getContentType(), "migration", owner).getId());
                            a.setData(null);
                        }
                        u.set("clientAttachments", atts);
                    }
                    if (u.getUpdateObject().isEmpty()) return;
                    if (mongo.updateFirst(target, u, ESignDocument.class).getMatchedCount() == 0)
                        throw new IllegalStateException("changed during migration — re-run to pick it up");
                    r.ok("esignDocuments");
                } catch (Exception e) {
                    r.fail("esignDocuments", d.getId(), e);
                }
            });
        }
    }

    private void esignTemplates(Report r) {
        Query q = new Query(where("pdfKey").ne(null).and("pdfFileId").is(null));
        try (Stream<ESignTemplate> tpls = mongo.stream(q, ESignTemplate.class)) {
            tpls.forEach(t -> {
                if (r.dryRun) { r.ok("esignTemplates"); return; }
                try {
                    String id = storage.registerExisting(t.getOrgId(), StoragePurpose.ESIGN_TEMPLATE, t.getPdfBucket(),
                            t.getPdfKey(), t.getPdfProvider(), "template.pdf", "application/pdf", 0,
                            "esign-template:" + t.getId()).getId();
                    mongo.updateFirst(byId(t.getId()), new Update().set("pdfFileId", id)
                            .unset("pdfBucket").unset("pdfKey").unset("pdfProvider"), ESignTemplate.class);
                    r.ok("esignTemplates");
                } catch (Exception e) {
                    r.fail("esignTemplates", t.getId(), e);
                }
            });
        }
    }

    // ── Org logos ───────────────────────────────────────────────────────────────

    private void orgLogos(Report r) {
        Query q = new Query(new Criteria().andOperator(where("branding.logoFileId").is(null),
                new Criteria().orOperator(where("branding.logoKey").ne(null), where("branding.logoBase64").ne(null))));
        try (Stream<Organization> orgs = mongo.stream(q, Organization.class)) {
            orgs.forEach(o -> {
                if (r.dryRun) { r.ok("orgLogos"); return; }
                try {
                    var b = o.getBranding();
                    String owner = "branding-logo:" + o.getId();
                    String id;
                    if (b.getLogoKey() != null) {
                        id = storage.registerExisting(o.getId(), StoragePurpose.BRANDING_LOGO, b.getLogoBucket(), b.getLogoKey(),
                                b.getLogoProvider(), "logo", b.getLogoContentType() != null ? b.getLogoContentType() : "image/png",
                                0, owner).getId();
                    } else {
                        Decoded img = decode(b.getLogoBase64());
                        if (img == null) throw new IllegalStateException("logoBase64 is not a data URL");
                        id = storage.store(o.getId(), StoragePurpose.BRANDING_LOGO, img.bytes, "logo", img.type, "migration", owner).getId();
                    }
                    Update u = new Update().set("branding.logoFileId", id);
                    if (b.getLogoUrl() == null || b.getLogoUrl().isBlank())   // rows saved before logoUrl existed
                        u.set("branding.logoUrl", publicBaseUrl.replaceAll("/$", "") + "/api/public/branding/" + o.getId() + "/logo");
                    mongo.updateFirst(byId(o.getId()), u
                            .unset("branding.logoBase64").unset("branding.logoBucket").unset("branding.logoKey")
                            .unset("branding.logoProvider").unset("branding.logoContentType"), Organization.class);
                    r.ok("orgLogos");
                } catch (Exception e) {
                    r.fail("orgLogos", o.getId(), e);
                }
            });
        }
    }

    // ── Profile pictures ────────────────────────────────────────────────────────

    private void avatars(Report r) {
        Query q = new Query(where("profilePictureFileId").is(null).and("profilePicture").regex("^data:"));
        try (Stream<AppUser> users = mongo.stream(q, AppUser.class)) {
            users.forEach(u -> {
                if (r.dryRun) { r.ok("avatars"); return; }
                try {
                    Decoded img = decode(u.getProfilePicture());
                    if (img == null) throw new IllegalStateException("profilePicture is not a data URL");
                    String id = storage.store(u.getOrganizationId(), StoragePurpose.AVATAR, img.bytes, "avatar",
                            img.type, u.getId(), "avatar:" + u.getId()).getId();
                    mongo.updateFirst(byId(u.getId()),
                            new Update().set("profilePictureFileId", id).unset("profilePicture"), AppUser.class);
                    r.ok("avatars");
                } catch (Exception e) {
                    r.fail("avatars", u.getId(), e);
                }
            });
        }
    }

    // ── Bulk email uploaded PDF ─────────────────────────────────────────────────

    private void bulkEmailJobs(Report r) {
        Query q = new Query(where("uploadedPdfData").ne(null).and("uploadedPdfFileId").is(null));
        try (Stream<BulkEmailJob> jobs = mongo.stream(q, BulkEmailJob.class)) {
            jobs.forEach(j -> {
                if (r.dryRun) { r.ok("bulkEmailJobs"); return; }
                try {
                    String id = storage.store(j.getOrgId(), StoragePurpose.BULK_EMAIL_ATTACHMENT, j.getUploadedPdfData(),
                            j.getUploadedPdfName() != null ? j.getUploadedPdfName() : "attachment.pdf",
                            "application/pdf", "migration", "bulk-email-job:" + j.getId()).getId();
                    mongo.updateFirst(byId(j.getId()),
                            new Update().set("uploadedPdfFileId", id).unset("uploadedPdfData"), BulkEmailJob.class);
                    r.ok("bulkEmailJobs");
                } catch (Exception e) {
                    r.fail("bulkEmailJobs", j.getId(), e);
                }
            });
        }
    }

    // ── Files library ───────────────────────────────────────────────────────────

    private void libraryFiles(Report r) {
        Query q = new Query(where("storageKey").ne(null).and("storedFileId").is(null));
        try (Stream<OrgFile> files = mongo.stream(q, OrgFile.class)) {
            files.forEach(f -> {
                if (r.dryRun) { r.ok("libraryFiles"); return; }
                try {
                    String id = storage.registerExisting(f.getOrganizationId(), StoragePurpose.LIBRARY_FILE, f.getBucket(),
                            f.getStorageKey(), f.getCloudProvider() != null ? f.getCloudProvider().name() : null,
                            f.getOriginalFilename(), f.getContentType(), f.getFileSizeBytes(),
                            "org-file:" + f.getFileId()).getId();
                    mongo.updateFirst(byId(f.getId()),
                            new Update().set("storedFileId", id).unset("storageKey").unset("bucket"), OrgFile.class);
                    r.ok("libraryFiles");
                } catch (Exception e) {
                    r.fail("libraryFiles", f.getId(), e);
                }
            });
        }
    }

    // ── PDF templates: base64 images embedded in html / css / GrapesJS JSON ─────

    private void pdfTemplateImages(Report r) {
        Map<String, String> uploaded = new HashMap<>();   // orgId + sha256(dataUrl) → public URL (shared across versions)
        Map<String, String> orgOfTemplate = new HashMap<>();
        Criteria hasImage = new Criteria().orOperator(where("htmlContent").regex("data:image/"),
                where("cssContent").regex("data:image/"), where("gjsData").regex("data:image/"));

        try (Stream<Template> tpls = mongo.stream(new Query(hasImage), Template.class)) {
            tpls.forEach(t -> {
                orgOfTemplate.put(t.getId(), t.getOrganizationId());
                migrateTemplateImages(r, "pdfTemplates", t.getId(), t.getOrganizationId(), t.getId(),
                        t.getHtmlContent(), t.getCssContent(), t.getGjsData(), uploaded, Template.class);
            });
        }
        try (Stream<TemplateVersion> versions = mongo.stream(new Query(hasImage), TemplateVersion.class)) {
            versions.forEach(v -> {
                String orgId = orgOfTemplate.computeIfAbsent(v.getTemplateId(), id -> {
                    Template t = mongo.findById(id, Template.class);
                    return t != null ? t.getOrganizationId() : null;
                });
                migrateTemplateImages(r, "pdfTemplateVersions", v.getId(), orgId, v.getTemplateId(),
                        v.getHtmlContent(), v.getCssContent(), v.getGjsData(), uploaded, TemplateVersion.class);
            });
        }
    }

    private void migrateTemplateImages(Report r, String section, String id, String orgId, String templateId,
                                       String html, String css, String gjs, Map<String, String> uploaded, Class<?> type) {
        if (r.dryRun) { r.ok(section); return; }
        try {
            Update u = new Update();
            u.set("htmlContent", replaceImages(html, orgId, templateId, uploaded));
            u.set("cssContent",  replaceImages(css,  orgId, templateId, uploaded));
            u.set("gjsData",     replaceImages(gjs,  orgId, templateId, uploaded));
            // Only if nobody saved the template meanwhile — else the next run picks it up.
            Query unchanged = byId(id).addCriteria(where("htmlContent").is(html))
                    .addCriteria(where("cssContent").is(css)).addCriteria(where("gjsData").is(gjs));
            if (mongo.updateFirst(unchanged, u, type).getMatchedCount() == 0)
                throw new IllegalStateException("edited during migration — re-run to pick it up");
            r.ok(section);
        } catch (Exception e) {
            r.fail(section, id, e);
        }
    }

    /** Replaces each {@code data:image/…;base64,…} with a public stored-file URL, uploading each distinct image once per org. */
    private String replaceImages(String text, String orgId, String templateId, Map<String, String> uploaded) {
        if (text == null || !text.contains("data:image/")) return text;
        Matcher m = DATA_IMAGE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String dataUrl = m.group();
            String url = uploaded.computeIfAbsent(orgId + ":" + sha256(dataUrl), k -> {
                String type = m.group(1);
                String ext  = type.substring(type.indexOf('/') + 1).replace("svg+xml", "svg");
                String fileId = storage.store(orgId, StoragePurpose.TEMPLATE_IMAGE, Base64.getMimeDecoder().decode(m.group(2)),
                        "image." + ext, type, "migration", "pdf-template:" + templateId).getId();
                return "/api/public/files/" + fileId;
            });
            m.appendReplacement(out, Matcher.quoteReplacement(url));
        }
        m.appendTail(out);
        return out.toString();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private record Decoded(byte[] bytes, String type) {}

    private static Decoded decode(String dataUrl) {
        if (dataUrl == null || !dataUrl.startsWith("data:")) return null;
        int comma = dataUrl.indexOf(',');
        if (comma < 0) return null;
        String meta = dataUrl.substring(5, comma);
        String type = meta.contains(";") ? meta.substring(0, meta.indexOf(';')) : meta;
        return new Decoded(Base64.getDecoder().decode(dataUrl.substring(comma + 1).trim()),
                type.isBlank() ? "image/png" : type);
    }

    private static Query byId(String id) {
        return new Query(where("_id").is(id));
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
