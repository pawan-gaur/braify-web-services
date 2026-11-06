package com.braify.feature.fileupload.repository;

import com.braify.feature.fileupload.model.OrgFile;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;

/**
 * Storage-usage aggregations over {@code org_files}, computed in MongoDB with
 * {@code $group}/{@code $sum} so no file documents are loaded into the JVM.
 * "Usage" always means files with {@code status = ACTIVE} (same filter as the old
 * {@code findActiveFileSizes} / {@code findAllActiveFileSizes} projections).
 * A missing {@code fileSizeMb} contributes 0, as it did when summed in Java.
 */
@Repository
@RequiredArgsConstructor
public class OrgFileUsageRepository {

    private static final String COLLECTION = "org_files";

    private final MongoTemplate mongoTemplate;

    /** Per-org active file count and total size. */
    public record Usage(long count, double totalMb) {}

    /**
     * Total MB of ACTIVE files. {@code organizationId == null} sums across all orgs
     * (including files with no org).
     */
    public double sumActiveMb(String organizationId) {
        Criteria match = Criteria.where("status").is(OrgFile.FileStatus.ACTIVE.name());
        if (organizationId != null) match = match.and("organizationId").is(organizationId);
        Aggregation agg = Aggregation.newAggregation(
                Aggregation.match(match),
                Aggregation.group().sum("fileSizeMb").as("totalMb"));
        Document d = mongoTemplate.aggregate(agg, COLLECTION, Document.class).getUniqueMappedResult();
        return (d != null && d.get("totalMb") instanceof Number n) ? n.doubleValue() : 0.0;
    }

    /**
     * ACTIVE file count + total MB grouped by organizationId, in one aggregation.
     * Files without an org are grouped under the {@code null} key.
     */
    public Map<String, Usage> activeUsageByOrg() {
        Aggregation agg = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("status").is(OrgFile.FileStatus.ACTIVE.name())),
                Aggregation.group("organizationId").count().as("count").sum("fileSizeMb").as("totalMb"));
        Map<String, Usage> out = new HashMap<>();
        for (Document d : mongoTemplate.aggregate(agg, COLLECTION, Document.class).getMappedResults()) {
            Object id = d.get("_id");
            long count = (d.get("count") instanceof Number n) ? n.longValue() : 0L;
            double mb = (d.get("totalMb") instanceof Number n) ? n.doubleValue() : 0.0;
            out.put(id == null ? null : id.toString(), new Usage(count, mb));
        }
        return out;
    }
}
