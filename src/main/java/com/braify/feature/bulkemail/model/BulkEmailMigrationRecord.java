package com.braify.feature.bulkemail.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * Audit record of moving one legacy job's embedded {@code rows}/{@code detailSheetRows} into the
 * {@code bulk_email_recipients}/{@code bulk_email_detail_rows} collections. Keyed by job id, so
 * re-runs overwrite rather than duplicate. Lets an operator prove every job migrated cleanly.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Document(collection = "bulk_email_migration_log")
public class BulkEmailMigrationRecord {

    public enum Status { MIGRATED, FAILED }

    @Id private String jobId;
    private Status status;

    private int legacyRows;           // rows embedded in the job before migration
    private int migratedRows;         // recipient documents present after migration
    private int legacyDetailRows;
    private int migratedDetailRows;

    private String error;             // why verification failed (FAILED only)
    private int attempts;
    private LocalDateTime migratedAt; // set when status == MIGRATED
    private LocalDateTime updatedAt;
}
