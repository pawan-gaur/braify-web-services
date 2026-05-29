package com.braify.feature.bulkemail.dto;

import com.braify.feature.bulkemail.model.BulkEmailJob;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class BulkEmailJobRequest {

    private String label;                   // optional — auto-generated if blank

    @NotBlank
    private String emailTemplateId;

    @NotBlank
    private String emailColumn;             // which XLSX column holds the recipient email

    private String nameColumn;              // optional — recipient display name column

    private Map<String, String> columnMapping;  // emailTemplatePlaceholder → xlsxColumn

    @NotEmpty
    private List<Map<String, String>> rows; // parsed XLSX rows (max 500)

    private BulkEmailJob.AttachmentType attachmentType = BulkEmailJob.AttachmentType.NONE;

    /* ── UPLOAD ── */
    private String uploadedPdfBase64;       // data URL or raw base64
    private String uploadedPdfName;

    /* ── PDF_TEMPLATE ── */
    private String pdfTemplateId;
    private Map<String, String> pdfColumnMapping;  // pdfVar → xlsxColumn

    /* ── EXTERNAL_API ── */
    private String externalApiUrl;
    private String externalApiMethod;       // GET or POST
    private String externalApiHeaders;      // JSON string
    private String externalApiBody;         // POST body template

    /* ── EXCEL_SHEET ── */
    private List<Map<String, String>> detailSheetRows;    // all Sheet 2 rows from the workbook
    private List<String> detailSheetColumns;              // Sheet 2 column names (ordered)
    private String detailSheetIdColumn;                   // Sheet 2 column to match against
    private String mainSheetIdColumn;                     // Sheet 1 column that holds the matching key
    private String detailSheetFileName;                   // filename template e.g. "Statement_{{name}}.xlsx"
}
