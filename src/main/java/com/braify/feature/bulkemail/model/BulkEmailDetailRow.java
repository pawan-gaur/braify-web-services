package com.braify.feature.bulkemail.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Locale;
import java.util.Map;

/**
 * One row of a campaign's "Sheet 2" (the per-recipient detail sheet used for Excel attachments),
 * stored as its own document. {@code matchKey} is the lower-cased value of the job's
 * {@code detailSheetIdColumn}, indexed so each recipient's matching rows are a single indexed
 * lookup instead of a scan of the whole sheet.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Document(collection = "bulk_email_detail_rows")
@CompoundIndexes({
    @CompoundIndex(name = "uq_job_seq",        def = "{'jobId': 1, 'seq': 1}", unique = true),
    @CompoundIndex(name = "idx_job_match_seq", def = "{'jobId': 1, 'matchKey': 1, 'seq': 1}"),
})
public class BulkEmailDetailRow {

    @Id private String id;

    private String jobId;
    /** 0-based position within the sheet — preserves original row order; unique per job. */
    private int seq;
    /** Lower-cased value of the id column (see {@link #matchKeyOf}). */
    private String matchKey;
    private Map<String, String> data;

    /**
     * Normalises an id-column value for matching. Mirrors the original case-insensitive
     * comparison ({@code equalsIgnoreCase}) without trimming, so matching behaviour is unchanged.
     */
    public static String matchKeyOf(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
