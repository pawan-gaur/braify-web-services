package com.braify.feature.quota.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Tracks monthly resource consumption for a single organisation.
 * One document per (orgId, year, month) triple — upserted atomically with $inc.
 * Resets naturally each month because a new document is created for the new period.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "org_usage")
@CompoundIndexes({
    @CompoundIndex(name = "org_year_month_unique", def = "{'organizationId':1,'year':1,'month':1}", unique = true)
})
public class OrgUsage {

    @Id
    private String id;

    @Indexed
    private String organizationId;

    /** Calendar year, e.g. 2026 */
    private int year;

    /** Calendar month (1–12) */
    private int month;

    /** Number of PDF generations this month. */
    @Builder.Default
    private long docsGenerated = 0;

    /** Number of e-sign sends this month. */
    @Builder.Default
    private long esignSent = 0;

    /** Approximate storage consumed in MB (updated on template save). */
    @Builder.Default
    private long storageMb = 0;

    /** Number of public API calls this month. */
    @Builder.Default
    private long apiCalls = 0;
}
