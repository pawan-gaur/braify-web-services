package com.braify.feature.esign.service;

import com.braify.feature.esign.model.ESignDocument;
import com.braify.feature.esign.model.ESignSignatureField;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

@Slf4j
@Service
public class ESignPdfService {

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Stamps all signed field values onto the source PDF.
     * Returns the stamped PDF bytes.
     */
    public byte[] stampSignatures(ESignDocument doc,
                                  byte[] sourcePdf,
                                  List<ESignSignatureField> fields,
                                  String creatorName,
                                  String creatorEmail) throws IOException {
        try (PDDocument pdf = PDDocument.load(new ByteArrayInputStream(sourcePdf))) {
            int totalPages = pdf.getNumberOfPages();

            // Resolve each field's signer name from the document's signatories (fallback: client name).
            java.util.Map<String, String> signatoryNames = new java.util.HashMap<>();
            if (doc.getSignatories() != null)
                doc.getSignatories().forEach(s -> signatoryNames.put(s.getId(), s.getName()));

            for (ESignSignatureField field : fields) {
                if (field.getValue() == null || field.getValue().isBlank()) continue;

                String signerName = field.getSignatoryId() != null && signatoryNames.containsKey(field.getSignatoryId())
                        ? signatoryNames.get(field.getSignatoryId())
                        : doc.getClientName();

                // page 0 → stamp every page; otherwise stamp 1-based page index
                int startPage = field.getPage() == 0 ? 0 : field.getPage() - 1;
                int endPage   = field.getPage() == 0 ? totalPages - 1 : field.getPage() - 1;

                for (int p = startPage; p <= endPage; p++) {
                    PDPage page = pdf.getPage(p);
                    stampField(pdf, page, field, signerName);
                }
            }

            // Append the Final Audit Report page(s)
            appendAuditReport(pdf, doc, fields, creatorName, creatorEmail);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.save(out);
            return out.toByteArray();
        }
    }

    /** SHA-256 hex digest of the supplied bytes. */
    public String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private void stampField(PDDocument pdf, PDPage page, ESignSignatureField field, String signerName) throws IOException {
        PDRectangle mediaBox = page.getMediaBox();
        float pageW = mediaBox.getWidth();
        float pageH = mediaBox.getHeight();

        // Convert % → points (PDFBox: origin at bottom-left)
        float x      = (float) (field.getX()      / 100.0 * pageW);
        float width  = (float) (field.getWidth()   / 100.0 * pageW);
        float height = (float) (field.getHeight()  / 100.0 * pageH);
        // top-left % → PDFBox bottom-left
        float y = pageH - (float) (field.getY() / 100.0 * pageH) - height;

        ESignSignatureField.SigningMethod method = field.getSigningMethod();

        try (PDPageContentStream cs = new PDPageContentStream(
                pdf, page, PDPageContentStream.AppendMode.APPEND, true, true)) {

            if (method == ESignSignatureField.SigningMethod.TYPE) {
                // Typed text signature
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA_OBLIQUE, 12);
                cs.newLineAtOffset(x + 2, y + 4);
                cs.showText(field.getValue());
                cs.endText();

            } else {
                // DRAW or UPLOAD — value is a base64 data-URL or pure base64 PNG
                byte[] imgBytes = decodeBase64Image(field.getValue());
                PDImageXObject img = PDImageXObject.createFromByteArray(pdf, imgBytes, "sig");
                cs.drawImage(img, x, y, width, height);
            }

            // Adobe-style caption under signature/initials: an underline + "Name (timestamp)".
            ESignSignatureField.FieldType type = field.getFieldType();
            if (type == ESignSignatureField.FieldType.SIGNATURE
                    || type == ESignSignatureField.FieldType.INITIALS) {
                // Render the timestamp in the SIGNER's timezone (with GMT offset), not the server's.
                String ts = ESignTimeFormat.caption(field.getSignedAt(), field.getSignedTimeZone());
                drawSignatureCaption(cs, x, y, width, signerName, ts);
            }
        }
    }

    /**
     * Draws the blue underline + caption below a stamped signature — the signer's name on one line
     * and the full timestamp on the next, each auto-sized down to fit the field width so the
     * timestamp is never cut off.
     */
    private void drawSignatureCaption(PDPageContentStream cs, float x, float y, float width,
                                      String signerName, String ts) throws IOException {
        String name = safe(signerName == null ? "" : signerName);
        String time = safe(ts == null ? "" : ts);
        if (name.isEmpty() && time.isEmpty()) return;

        // Underline just below the signature box
        cs.setStrokingColor(0.16f, 0.38f, 0.90f);
        cs.setLineWidth(0.6f);
        cs.moveTo(x, y - 1);
        cs.lineTo(x + width, y - 1);
        cs.stroke();

        cs.setNonStrokingColor(0.16f, 0.38f, 0.90f);
        float lineY = y - 8;
        if (!name.isEmpty()) lineY = drawCaptionLine(cs, x, lineY, width, name);
        if (!time.isEmpty()) drawCaptionLine(cs, x, lineY, width, time);
        cs.setNonStrokingColor(0f, 0f, 0f);   // reset for any subsequent drawing
    }

    /** Draws one caption line, shrinking the font (7→5pt) to fit the width; returns the next y. */
    private float drawCaptionLine(PDPageContentStream cs, float x, float y, float width, String text) throws IOException {
        float size = 7f;
        while (size > 5f && textWidth(PDType1Font.HELVETICA, size, text) > width) size -= 0.5f;
        String line = ellipsize(PDType1Font.HELVETICA, size, text, width);   // last-resort trim at min size
        cs.beginText();
        cs.setFont(PDType1Font.HELVETICA, size);
        cs.newLineAtOffset(x, y);
        cs.showText(line);
        cs.endText();
        return y - (size + 1.5f);
    }

    // ── Final Audit Report ───────────────────────────────────────────────────

    /** One line of the audit history (with its colour marker + optional sub-line). */
    private record AuditEvent(LocalDateTime ts, float r, float g, float b, String primary, String sub) {}

    /**
     * Appends an Adobe-style "Final Audit Report": a header, a summary box
     * (Created / By / Status / Transaction ID) and a chronological history of the signing events.
     * Spills onto additional pages when the history is long.
     */
    private void appendAuditReport(PDDocument pdf, ESignDocument doc, List<ESignSignatureField> fields,
                                   String creatorName, String creatorEmail) throws IOException {
        final float pageW = PDRectangle.A4.getWidth();
        final float pageH = PDRectangle.A4.getHeight();
        final float margin = 45;
        final float contentW = pageW - 2 * margin;
        final float bottom = 55;

        final PDType1Font bold = PDType1Font.HELVETICA_BOLD;
        final PDType1Font reg  = PDType1Font.HELVETICA;

        PDPage page = new PDPage(PDRectangle.A4);
        pdf.addPage(page);
        PDPageContentStream cs = new PDPageContentStream(pdf, page);
        float y = pageH - margin;

        // Title
        cs.setNonStrokingColor(0.10f, 0.36f, 0.80f);
        y = drawWrapped(cs, bold, 18, margin, y, contentW, nz(doc.getTitle(), "Document"), 22);
        cs.setNonStrokingColor(0f, 0f, 0f);
        y -= 4;

        // "Final Audit Report" + report date
        drawText(cs, reg, 10, margin, y, "Final Audit Report");
        String reportDate = (doc.getCompletedAt() != null ? doc.getCompletedAt() : LocalDateTime.now())
                .toLocalDate().toString();
        drawRightText(cs, reg, 10, pageW - margin, y, reportDate);
        y -= 22;

        // Summary box
        float rowH = 18;
        float boxH = rowH * 4 + 10;
        cs.setStrokingColor(0.80f, 0.80f, 0.80f);
        cs.addRect(margin, y - boxH, contentW, boxH);
        cs.stroke();
        float ry = y - 14;
        float labelX = margin + 12, valX = margin + 120;
        String by = creatorName != null
                ? creatorName + (creatorEmail != null ? " (" + creatorEmail + ")" : "")
                : (creatorEmail != null ? creatorEmail : "-");
        drawKV(cs, reg, bold, labelX, valX, pageW - margin, ry, "Created:", ESignTimeFormat.audit(doc.getCreatedAt())); ry -= rowH;
        drawKV(cs, reg, bold, labelX, valX, pageW - margin, ry, "By:", by); ry -= rowH;
        drawKV(cs, reg, bold, labelX, valX, pageW - margin, ry, "Status:", statusLabel(doc.getStatus())); ry -= rowH;
        drawKV(cs, reg, bold, labelX, valX, pageW - margin, ry, "Transaction ID:", nz(doc.getId(), "-"));
        y -= boxH + 22;

        // History heading
        y = drawWrapped(cs, bold, 14, margin, y, contentW, "\"" + nz(doc.getTitle(), "Document") + "\" History", 18);
        y -= 8;

        // Chronological events
        for (AuditEvent ev : buildAuditEvents(doc, fields, creatorName, creatorEmail)) {
            if (y - 30 < bottom) {                 // new page when out of room
                cs.close();
                page = new PDPage(PDRectangle.A4);
                pdf.addPage(page);
                cs = new PDPageContentStream(pdf, page);
                y = pageH - margin;
            }
            cs.setNonStrokingColor(ev.r(), ev.g(), ev.b());
            cs.addRect(margin, y - 7, 6, 6);
            cs.fill();
            cs.setNonStrokingColor(0f, 0f, 0f);
            y = drawWrapped(cs, reg, 10, margin + 14, y, contentW - 14, ev.primary(), 13);
            cs.setNonStrokingColor(0.45f, 0.45f, 0.45f);
            String subLine = ev.sub() != null ? ev.sub() : ESignTimeFormat.audit(ev.ts());
            y = drawWrapped(cs, reg, 8, margin + 14, y - 1, contentW - 14, subLine, 11);
            cs.setNonStrokingColor(0f, 0f, 0f);
            y -= 9;
        }

        // Footer
        cs.setNonStrokingColor(0.45f, 0.45f, 0.45f);
        drawText(cs, reg, 8, margin, 34, "Powered by Braify e-Sign");
        cs.setNonStrokingColor(0f, 0f, 0f);

        cs.close();
    }

    private List<AuditEvent> buildAuditEvents(ESignDocument doc, List<ESignSignatureField> fields,
                                              String creatorName, String creatorEmail) {
        List<AuditEvent> events = new java.util.ArrayList<>();

        String creator = creatorName != null
                ? creatorName + (creatorEmail != null ? " (" + creatorEmail + ")" : "")
                : (creatorEmail != null ? creatorEmail : "the sender");
        if (doc.getCreatedAt() != null)
            events.add(new AuditEvent(doc.getCreatedAt(), 0.10f, 0.36f, 0.80f,
                    "Document created by " + creator, null));

        for (ESignDocument.Signatory s : effectiveSignatoriesForReport(doc)) {
            String who = (s.getName() != null ? s.getName() : "")
                    + (s.getEmail() != null ? " (" + s.getEmail() + ")" : "");
            LocalDateTime invited = s.getInvitedAt() != null ? s.getInvitedAt() : doc.getSentAt();
            if (invited != null)
                events.add(new AuditEvent(invited, 0.13f, 0.50f, 0.60f,
                        "Document emailed to " + who + " for signature", null));
            if (s.getViewedAt() != null)
                events.add(new AuditEvent(s.getViewedAt(), 0.90f, 0.45f, 0.10f,
                        "Email viewed by " + who, null));
            if (s.getSignedAt() != null) {
                String sub = "Signature Date: " + ESignTimeFormat.audit(s.getSignedAt())
                        + " - Time Source: server - Signature Appearance: " + methodForSignatory(doc, fields, s);
                events.add(new AuditEvent(s.getSignedAt(), 0.13f, 0.60f, 0.30f,
                        "Document e-signed by " + who, sub));
            }
        }

        if (doc.getCompletedAt() != null)
            events.add(new AuditEvent(doc.getCompletedAt(), 0.10f, 0.55f, 0.25f,
                    "Agreement completed.", null));

        events.sort(java.util.Comparator.comparing(AuditEvent::ts,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        return events;
    }

    /** Document signatories ordered; a synthetic single signatory for legacy docs. */
    private List<ESignDocument.Signatory> effectiveSignatoriesForReport(ESignDocument doc) {
        if (doc.getSignatories() != null && !doc.getSignatories().isEmpty())
            return doc.getSignatories().stream()
                    .sorted(java.util.Comparator.comparingInt(ESignDocument.Signatory::getSigningOrder))
                    .toList();
        return List.of(ESignDocument.Signatory.builder()
                .name(doc.getClientName()).email(doc.getClientEmail())
                .invitedAt(doc.getSentAt()).viewedAt(doc.getViewedAt()).signedAt(doc.getSubmittedAt())
                .build());
    }

    /** The signing method used by a signatory (DRAW/TYPE/UPLOAD) — from their first signed field. */
    private String methodForSignatory(ESignDocument doc, List<ESignSignatureField> fields, ESignDocument.Signatory s) {
        String firstId = (doc.getSignatories() != null && !doc.getSignatories().isEmpty())
                ? doc.getSignatories().get(0).getId() : null;
        for (ESignSignatureField f : fields) {
            if (f.getSigningMethod() == null) continue;
            if (s.getId() == null) return f.getSigningMethod().name();   // legacy: any signed field
            String owner = f.getSignatoryId() != null ? f.getSignatoryId() : firstId;
            if (s.getId().equals(owner)) return f.getSigningMethod().name();
        }
        return "N/A";
    }

    // ── PDF text helpers ──────────────────────────────────────────────────────

    private void drawText(PDPageContentStream cs, PDType1Font font, float size, float x, float y, String text) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(safe(text));
        cs.endText();
    }

    private void drawRightText(PDPageContentStream cs, PDType1Font font, float size, float rightX, float y, String text) throws IOException {
        String t = safe(text);
        float w = textWidth(font, size, t);
        drawText(cs, font, size, rightX - w, y, t);
    }

    private void drawKV(PDPageContentStream cs, PDType1Font reg, PDType1Font bold,
                        float labelX, float valX, float rightX, float y, String k, String v) throws IOException {
        drawText(cs, bold, 9, labelX, y, k);
        drawText(cs, reg, 9, valX, y, ellipsize(reg, 9, safe(v), rightX - valX));
    }

    /** Word-wraps text to maxW, draws each line, and returns the y below the last line. */
    private float drawWrapped(PDPageContentStream cs, PDType1Font font, float size,
                              float x, float y, float maxW, String text, float leading) throws IOException {
        for (String line : wrap(font, size, safe(text), maxW)) {
            cs.beginText();
            cs.setFont(font, size);
            cs.newLineAtOffset(x, y);
            cs.showText(line);
            cs.endText();
            y -= leading;
        }
        return y;
    }

    private List<String> wrap(PDType1Font font, float size, String text, float maxW) throws IOException {
        List<String> lines = new java.util.ArrayList<>();
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) { lines.add(""); return lines; }
        StringBuilder cur = new StringBuilder();
        for (String w : t.split("\\s+")) {
            String cand = cur.length() == 0 ? w : cur + " " + w;
            if (textWidth(font, size, cand) <= maxW) {
                cur.setLength(0); cur.append(cand);
            } else {
                if (cur.length() > 0) { lines.add(cur.toString()); cur.setLength(0); }
                if (textWidth(font, size, w) > maxW) lines.add(ellipsize(font, size, w, maxW));
                else cur.append(w);
            }
        }
        if (cur.length() > 0) lines.add(cur.toString());
        if (lines.isEmpty()) lines.add("");
        return lines;
    }

    private String ellipsize(PDType1Font font, float size, String text, float maxW) throws IOException {
        String t = safe(text);
        if (textWidth(font, size, t) <= maxW) return t;
        while (t.length() > 1 && textWidth(font, size, t + "...") > maxW) t = t.substring(0, t.length() - 1);
        return t + "...";
    }

    private float textWidth(PDType1Font font, float size, String s) throws IOException {
        return font.getStringWidth(s) / 1000 * size;
    }

    /** Keeps only WinAnsi-safe printable ASCII so PDFBox's standard font never fails on a glyph. */
    private String safe(String s) {
        return s == null ? "" : s.replaceAll("[^\\x20-\\x7E]", "?");
    }

    private String nz(String s, String def) {
        return (s == null || s.isBlank()) ? def : s;
    }

    private String statusLabel(ESignDocument.Status st) {
        if (st == null) return "-";
        return switch (st) {
            case COMPLETED, SIGNED -> "Signed";
            case PARTIALLY_SIGNED  -> "Partially signed";
            default -> st.name().charAt(0) + st.name().substring(1).toLowerCase().replace('_', ' ');
        };
    }

    private byte[] decodeBase64Image(String value) {
        // Strip data-URL prefix if present: "data:image/png;base64,<data>"
        if (value.contains(",")) {
            value = value.substring(value.indexOf(',') + 1);
        }
        return Base64.getDecoder().decode(value.trim());
    }
}
