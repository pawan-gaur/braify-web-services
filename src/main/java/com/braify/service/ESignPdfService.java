package com.braify.service;

import com.braify.model.ESignDocument;
import com.braify.model.ESignSignatureField;
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
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

@Slf4j
@Service
public class ESignPdfService {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'");

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Stamps all signed field values onto the source PDF.
     * Returns the stamped PDF bytes.
     */
    public byte[] stampSignatures(ESignDocument doc,
                                  List<ESignSignatureField> fields) throws IOException {
        try (PDDocument pdf = PDDocument.load(new ByteArrayInputStream(doc.getSourcePdfData()))) {
            int totalPages = pdf.getNumberOfPages();

            for (ESignSignatureField field : fields) {
                if (field.getValue() == null || field.getValue().isBlank()) continue;

                // page 0 → stamp every page; otherwise stamp 1-based page index
                int startPage = field.getPage() == 0 ? 0 : field.getPage() - 1;
                int endPage   = field.getPage() == 0 ? totalPages - 1 : field.getPage() - 1;

                for (int p = startPage; p <= endPage; p++) {
                    PDPage page = pdf.getPage(p);
                    stampField(pdf, page, field);
                }
            }

            // Append audit trail page
            appendAuditPage(pdf, doc, fields);

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

    private void stampField(PDDocument pdf, PDPage page, ESignSignatureField field) throws IOException {
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
        }
    }

    /** Appends a final audit-trail page listing signing metadata. */
    private void appendAuditPage(PDDocument pdf,
                                 ESignDocument doc,
                                 List<ESignSignatureField> fields) throws IOException {
        PDPage auditPage = new PDPage(PDRectangle.A4);
        pdf.addPage(auditPage);

        try (PDPageContentStream cs = new PDPageContentStream(pdf, auditPage)) {
            PDType1Font titleFont = PDType1Font.HELVETICA_BOLD;
            PDType1Font bodyFont  = PDType1Font.HELVETICA;

            float margin = 50;
            float yPos   = PDRectangle.A4.getHeight() - margin;
            float leading = 16;

            // Title
            cs.beginText();
            cs.setFont(titleFont, 14);
            cs.newLineAtOffset(margin, yPos);
            cs.showText("E-Signature Audit Trail");
            cs.endText();
            yPos -= leading * 2;

            // Document details
            yPos = writeAuditLine(cs, bodyFont, margin, yPos, leading,
                    "Document ID: " + doc.getId());
            yPos = writeAuditLine(cs, bodyFont, margin, yPos, leading,
                    "Title: " + doc.getTitle());
            yPos = writeAuditLine(cs, bodyFont, margin, yPos, leading,
                    "Client: " + doc.getClientName() + " <" + doc.getClientEmail() + ">");
            if (doc.getSentAt() != null)
                yPos = writeAuditLine(cs, bodyFont, margin, yPos, leading,
                        "Sent at: " + doc.getSentAt().format(DATE_FMT));
            if (doc.getViewedAt() != null)
                yPos = writeAuditLine(cs, bodyFont, margin, yPos, leading,
                        "Viewed at: " + doc.getViewedAt().format(DATE_FMT));
            if (doc.getSubmittedAt() != null)
                yPos = writeAuditLine(cs, bodyFont, margin, yPos, leading,
                        "Submitted at: " + doc.getSubmittedAt().format(DATE_FMT));

            yPos -= leading;
            yPos = writeAuditLine(cs, titleFont, margin, yPos, leading, "Signed Fields:");
            for (ESignSignatureField f : fields) {
                if (f.getValue() == null) continue;
                String line = String.format("  [%s] %s — method: %s — signed: %s",
                        f.getFieldType().name(),
                        f.getLabel() != null ? f.getLabel() : "unlabelled",
                        f.getSigningMethod() != null ? f.getSigningMethod().name() : "N/A",
                        f.getSignedAt() != null ? f.getSignedAt().format(DATE_FMT) : "N/A");
                yPos = writeAuditLine(cs, bodyFont, margin, yPos, leading, line);
            }

            yPos -= leading * 2;
            yPos = writeAuditLine(cs, bodyFont, margin, yPos, leading,
                    "Generated: " + LocalDateTime.now().format(DATE_FMT));
            writeAuditLine(cs, bodyFont, margin, yPos, leading,
                    "This document was electronically signed via Braify e-Sign.");
        }
    }

    private float writeAuditLine(PDPageContentStream cs,
                                  PDType1Font font,
                                  float x, float y, float leading,
                                  String text) throws IOException {
        cs.beginText();
        cs.setFont(font, 9);
        cs.newLineAtOffset(x, y);
        // Truncate long lines to avoid PDFBox overflow
        String safeText = text.length() > 110 ? text.substring(0, 110) + "…" : text;
        cs.showText(safeText);
        cs.endText();
        return y - leading;
    }

    private byte[] decodeBase64Image(String value) {
        // Strip data-URL prefix if present: "data:image/png;base64,<data>"
        if (value.contains(",")) {
            value = value.substring(value.indexOf(',') + 1);
        }
        return Base64.getDecoder().decode(value.trim());
    }
}
