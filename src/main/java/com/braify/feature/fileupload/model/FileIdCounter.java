package com.braify.feature.fileupload.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Atomic sequence counter used to generate unique {@code fileId} values
 * in the format {@code F<yyyyMMdd><zero-padded-10-digit-sequence>}.
 *
 * <p>The document is upserted with {@code $inc} by {@link com.braify.feature.fileupload.service.FileIdGenerator}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "file_id_counters")
public class FileIdCounter {

    /** Fixed document key — one counter document per calendar date. */
    @Id
    private String date;   // "yyyyMMdd"

    /** Monotonically increasing sequence value (starts at 0, incremented before use). */
    @Builder.Default
    private long seq = 0;
}
