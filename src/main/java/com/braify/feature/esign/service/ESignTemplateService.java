package com.braify.feature.esign.service;

import com.braify.feature.esign.dto.CreateDocumentRequest;
import com.braify.feature.esign.dto.DocumentResponse;
import com.braify.feature.esign.dto.FieldPlacementRequest;
import com.braify.feature.esign.model.ESignDocument;
import com.braify.feature.esign.model.ESignSignatureField;
import com.braify.feature.esign.model.ESignTemplate;
import com.braify.feature.esign.repository.ESignDocumentRepository;
import com.braify.feature.esign.repository.ESignSignatureFieldRepository;
import com.braify.feature.esign.repository.ESignTemplateRepository;
import com.braify.feature.user.model.AppUser;
import com.braify.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Reusable e-sign templates: save a document's PDF + field layout once, then create new
 * documents from it by filling each signer role with a real person.
 *
 * <p>Using a template goes through {@link ESignDocumentService#createDocument} and
 * {@link ESignDocumentService#saveFields}, so storage checks, audit and the send flow are the
 * same as for a hand-built document. The result is a DRAFT the user reviews and sends.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ESignTemplateService {

    static final int MAX_TEMPLATES_PER_ORG = 200;
    private static final java.util.regex.Pattern EMAIL = java.util.regex.Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final int MAX_NAME = 120;

    private final ESignTemplateRepository       templateRepo;
    private final ESignDocumentRepository       docRepo;
    private final ESignSignatureFieldRepository fieldRepo;
    private final ESignDocumentService          documentService;
    private final ESignStorageService           esignStorage;

    public record SaveRequest(String name, String description, List<String> roles) {}
    public record UpdateRequest(String name, String description, String documentTitle,
                                List<String> roles, String signingMode) {}
    public record UseRequest(String title, List<CreateDocumentRequest.SignatoryRequest> signatories,
                             List<String> ccEmails, List<String> completionCcEmails) {}

    // ── Save from an existing document ──────────────────────────────────────

    public ESignTemplate saveFromDocument(String docId, SaveRequest req, UserDetailsImpl principal) {
        String orgId = requireOrg(principal);
        String name = cleanName(req.name());
        if (templateRepo.countByOrgId(orgId) >= MAX_TEMPLATES_PER_ORG) {
            throw new IllegalStateException("Template limit reached (" + MAX_TEMPLATES_PER_ORG + "). Delete unused templates first.");
        }

        // Access + PDF: same rule as the builder's source-pdf download (creator / participants only).
        byte[] pdf = documentService.getSourcePdfBytes(docId, principal);
        if (pdf == null || pdf.length == 0) throw new IllegalStateException("This document's PDF is not available.");
        ESignDocument doc = docRepo.findById(docId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + docId));
        if (!orgId.equals(doc.getOrgId())) throw new AccessDeniedException("Access denied to document: " + docId);

        List<ESignDocument.Signatory> signers = doc.getSignatories() == null ? List.of()
                : doc.getSignatories().stream().sorted(Comparator.comparingInt(ESignDocument.Signatory::getSigningOrder)).toList();
        int roleCount = Math.max(1, signers.size());
        Map<String, Integer> roleOf = new HashMap<>();
        for (int i = 0; i < signers.size(); i++) roleOf.put(signers.get(i).getId(), i);

        List<ESignTemplate.Field> fields = new ArrayList<>();
        for (ESignSignatureField f : fieldRepo.findByDocumentIdOrderByPageAscYAsc(docId)) {
            boolean creator = f.getFilledBy() == ESignSignatureField.FilledBy.CREATOR;
            fields.add(ESignTemplate.Field.builder()
                    .roleIndex(creator ? 0 : roleOf.getOrDefault(f.getSignatoryId(), 0))
                    .filledBy(creator ? "CREATOR" : "SIGNER")
                    .page(f.getPage()).x(f.getX()).y(f.getY()).width(f.getWidth()).height(f.getHeight())
                    .fieldType(f.getFieldType().name())
                    .label(f.getLabel())
                    .required(f.isRequired())
                    .fontSize(f.getFontSize())
                    // Never copy what a signer typed or drew — only the author's own pre-filled values.
                    .value(creator ? f.getValue() : null)
                    .signingMethod(creator && f.getSigningMethod() != null ? f.getSigningMethod().name() : null)
                    .build());
        }

        ESignTemplate tpl = templateRepo.save(ESignTemplate.builder()
                .orgId(orgId)
                .name(name)
                .description(trim(req.description(), 500))
                .documentTitle(doc.getTitle())
                .pdfHash(sha256Hex(pdf))
                .signingMode(doc.getSigningMode() != null ? doc.getSigningMode() : ESignDocument.SigningMode.PARALLEL)
                .roles(roleLabels(req.roles(), roleCount))
                .fields(fields)
                .emailTemplateId(doc.getEmailTemplateId())
                .completionCcEmails(doc.getCompletionCcEmails())
                .createdBy(principal.getId())
                .createdByEmail(principal.getUsername())
                .build());

        try {
            ESignStorageService.StoredPdf ref = esignStorage.uploadSourcePdf(orgId, "templates/" + tpl.getId(), pdf);
            tpl.setPdfBucket(ref.bucket());
            tpl.setPdfKey(ref.storageKey());
            tpl.setPdfProvider(ref.provider());
            return templateRepo.save(tpl);
        } catch (RuntimeException e) {
            templateRepo.deleteById(tpl.getId());   // no half-saved templates without a PDF
            throw e;
        }
    }

    // ── Read / update / delete ──────────────────────────────────────────────

    public List<ESignTemplate> list(UserDetailsImpl principal) {
        return templateRepo.findByOrgIdOrderByUpdatedAtDesc(requireOrg(principal));
    }

    public ESignTemplate get(String id, UserDetailsImpl principal) {
        return templateRepo.findByIdAndOrgId(id, requireOrg(principal))
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + id));
    }

    public byte[] pdf(String id, UserDetailsImpl principal) {
        ESignTemplate tpl = get(id, principal);
        return esignStorage.download(tpl.getOrgId(), tpl.getPdfBucket(), tpl.getPdfKey());
    }

    public ESignTemplate update(String id, UpdateRequest req, UserDetailsImpl principal) {
        ESignTemplate tpl = get(id, principal);
        assertCanManage(tpl, principal);
        if (req.name() != null)          tpl.setName(cleanName(req.name()));
        if (req.description() != null)   tpl.setDescription(trim(req.description(), 500));
        if (req.documentTitle() != null && !req.documentTitle().isBlank()) tpl.setDocumentTitle(trim(req.documentTitle(), 200));
        if (req.roles() != null)         tpl.setRoles(roleLabels(req.roles(), tpl.getRoles().size()));
        if (req.signingMode() != null) {
            try { tpl.setSigningMode(ESignDocument.SigningMode.valueOf(req.signingMode().trim().toUpperCase())); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("signingMode must be PARALLEL or SEQUENTIAL"); }
        }
        return templateRepo.save(tpl);
    }

    public void delete(String id, UserDetailsImpl principal) {
        ESignTemplate tpl = get(id, principal);
        assertCanManage(tpl, principal);
        templateRepo.delete(tpl);
        esignStorage.deleteQuietly(tpl.getOrgId(), tpl.getPdfBucket(), tpl.getPdfKey());
    }

    // ── Use ─────────────────────────────────────────────────────────────────

    /** Creates a DRAFT document from the template with each role filled by the given signatory. */
    public DocumentResponse use(String id, UseRequest req, UserDetailsImpl principal, String ip, String ua) {
        ESignTemplate tpl = get(id, principal);
        int roleCount = Math.max(1, tpl.getRoles().size());
        List<CreateDocumentRequest.SignatoryRequest> people = req.signatories() == null ? List.of() : req.signatories();
        if (people.size() != roleCount) {
            throw new IllegalArgumentException("This template needs " + roleCount + " signator" + (roleCount == 1 ? "y" : "ies")
                    + " (" + String.join(", ", tpl.getRoles()) + ").");
        }
        // Role order is the signing order — ignore any client-sent order. (This body skips @Valid.)
        for (int i = 0; i < people.size(); i++) {
            CreateDocumentRequest.SignatoryRequest p = people.get(i);
            if (p == null || p.getEmail() == null || !EMAIL.matcher(p.getEmail().trim()).matches()) {
                throw new IllegalArgumentException("Enter a valid email for " + tpl.getRoles().get(Math.min(i, tpl.getRoles().size() - 1)) + ".");
            }
            p.setSigningOrder(i + 1);
        }

        byte[] pdf = esignStorage.download(tpl.getOrgId(), tpl.getPdfBucket(), tpl.getPdfKey());

        CreateDocumentRequest create = new CreateDocumentRequest();
        create.setTitle(req.title() != null && !req.title().isBlank() ? trim(req.title(), 200) : tpl.getDocumentTitle());
        create.setSourceType("UPLOAD");
        create.setPdfBase64(Base64.getEncoder().encodeToString(pdf));
        create.setSignatories(people);
        create.setSigningMode(tpl.getSigningMode().name());
        create.setEmailTemplateId(tpl.getEmailTemplateId());
        create.setCcEmails(req.ccEmails());
        create.setCompletionCcEmails(req.completionCcEmails() != null ? req.completionCcEmails() : tpl.getCompletionCcEmails());
        DocumentResponse created = documentService.createDocument(create, principal, ip, ua);

        // createDocument keeps the signatories in role order (contiguous 1..n).
        List<String> signatoryIds = created.getSignatories().stream()
                .sorted(Comparator.comparingInt(DocumentResponse.SignatoryResponse::getSigningOrder))
                .map(DocumentResponse.SignatoryResponse::getId).toList();
        List<FieldPlacementRequest> placements = tpl.getFields().stream().map(f -> {
            FieldPlacementRequest p = new FieldPlacementRequest();
            boolean creator = "CREATOR".equals(f.getFilledBy());
            p.setPage(f.getPage()); p.setX(f.getX()); p.setY(f.getY());
            p.setWidth(f.getWidth()); p.setHeight(f.getHeight());
            p.setFieldType(f.getFieldType());
            p.setLabel(f.getLabel());
            p.setRequired(f.isRequired());
            p.setFontSize(f.getFontSize());
            p.setFilledBy(creator ? "CREATOR" : "SIGNER");
            if (creator) { p.setValue(f.getValue()); p.setSigningMethod(f.getSigningMethod()); }
            else p.setSignatoryId(signatoryIds.get(Math.min(Math.max(f.getRoleIndex(), 0), signatoryIds.size() - 1)));
            return p;
        }).toList();
        DocumentResponse result = placements.isEmpty() ? created
                : documentService.saveFields(created.getId(), placements, principal, ip, ua);

        tpl.setUseCount(tpl.getUseCount() + 1);
        tpl.setLastUsedAt(LocalDateTime.now());
        templateRepo.save(tpl);
        return result;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static String requireOrg(UserDetailsImpl principal) {
        String orgId = principal.getOrgId();
        if (orgId == null) throw new IllegalArgumentException("E-sign templates belong to an organisation.");
        return orgId;
    }

    /** Creator, or an Admin / Org Admin of the org, may edit or delete a shared template. */
    private static void assertCanManage(ESignTemplate tpl, UserDetailsImpl principal) {
        AppUser.Role role = principal.getAppUser().getRole();
        if (principal.getId().equals(tpl.getCreatedBy())
                || role == AppUser.Role.ADMIN || role == AppUser.Role.ORG_ADMIN || role == AppUser.Role.PLATFORM_ADMIN) return;
        throw new AccessDeniedException("Only the template's creator or an admin can change it.");
    }

    private static List<String> roleLabels(List<String> requested, int count) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String r = requested != null && i < requested.size() ? trim(requested.get(i), 60) : null;
            out.add(r == null || r.isBlank() ? (count == 1 ? "Signer" : "Signer " + (i + 1)) : r);
        }
        return out;
    }

    private static String cleanName(String name) {
        String n = trim(name, MAX_NAME);
        if (n == null || n.isBlank()) throw new IllegalArgumentException("Template name is required.");
        return n;
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String sha256Hex(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
