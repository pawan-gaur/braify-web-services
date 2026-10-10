package com.braify.feature.bulkemail.service;

import com.braify.feature.bulkemail.model.BulkEmailDetailRow;
import com.braify.feature.bulkemail.model.BulkEmailRecipient;
import com.braify.feature.bulkemail.repository.BulkEmailDetailRowRepository;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Data access for campaign recipients and Sheet-2 detail rows: chunked inserts, keyset paging
 * for the processor, targeted per-recipient status updates, and the filtered/paged reads behind
 * the Recipients tab. Nothing here ever loads a whole campaign into memory.
 */
@Component
@RequiredArgsConstructor
public class BulkEmailRecipientStore {

    /** Documents per insert round-trip — big enough to be fast, small enough to stay light. */
    static final int INSERT_CHUNK = 1000;
    /** Page size when streaming a whole campaign (segment resend, detail-row copy). */
    static final int STREAM_PAGE  = 500;

    private final MongoTemplate                 mongo;
    private final BulkEmailDetailRowRepository  detailRepo;

    /** Recipients-tab filters. */
    public enum Filter { ALL, SENT, FAILED, PENDING, OPENED, CLICKED, UNOPENED, UNSUBSCRIBED }

    // ── Writes ──────────────────────────────────────────────────────────────────

    public void insertRecipients(List<BulkEmailRecipient> recipients) {
        for (int i = 0; i < recipients.size(); i += INSERT_CHUNK) {
            mongo.insert(recipients.subList(i, Math.min(i + INSERT_CHUNK, recipients.size())),
                    BulkEmailRecipient.class);
        }
    }

    /** Stores a campaign's Sheet-2 rows, keyed for indexed matching. Returns the number stored. */
    public int insertDetailRows(String jobId, String idColumn, List<Map<String, String>> rows) {
        if (rows == null || rows.isEmpty()) return 0;
        List<BulkEmailDetailRow> chunk = new ArrayList<>(Math.min(INSERT_CHUNK, rows.size()));
        for (int seq = 0; seq < rows.size(); seq++) {
            Map<String, String> data = rows.get(seq);
            chunk.add(BulkEmailDetailRow.builder()
                    .jobId(jobId)
                    .seq(seq)
                    .matchKey(BulkEmailDetailRow.matchKeyOf(
                            idColumn == null || data == null ? "" : data.getOrDefault(idColumn, "")))
                    .data(data)
                    .build());
            if (chunk.size() == INSERT_CHUNK) {
                mongo.insert(chunk, BulkEmailDetailRow.class);
                chunk = new ArrayList<>(INSERT_CHUNK);
            }
        }
        if (!chunk.isEmpty()) mongo.insert(chunk, BulkEmailDetailRow.class);
        return rows.size();
    }

    /** Copies one job's Sheet-2 rows to another job (follow-up campaigns). Returns rows copied. */
    public int copyDetailRows(String fromJobId, String toJobId) {
        int copied = 0, lastSeq = -1;
        while (true) {
            Query q = Query.query(Criteria.where("jobId").is(fromJobId).and("seq").gt(lastSeq))
                    .with(Sort.by("seq")).limit(STREAM_PAGE);
            List<BulkEmailDetailRow> page = mongo.find(q, BulkEmailDetailRow.class);
            if (page.isEmpty()) return copied;
            List<BulkEmailDetailRow> copies = new ArrayList<>(page.size());
            for (BulkEmailDetailRow r : page) {
                copies.add(BulkEmailDetailRow.builder()
                        .jobId(toJobId).seq(r.getSeq()).matchKey(r.getMatchKey()).data(r.getData()).build());
                lastSeq = r.getSeq();
            }
            mongo.insert(copies, BulkEmailDetailRow.class);
            copied += copies.size();
        }
    }

    public void markSent(String recipientId, String messageId) {
        mongo.updateFirst(byId(recipientId), new Update()
                        .set("status",    BulkEmailRecipient.Status.SENT)
                        .set("sentAt",    LocalDateTime.now())
                        .set("messageId", messageId)
                        .unset("error"),
                BulkEmailRecipient.class);
    }

    public void markFailed(String recipientId, String error) {
        mongo.updateFirst(byId(recipientId), new Update()
                        .set("status", BulkEmailRecipient.Status.FAILED)
                        .set("error",  error),
                BulkEmailRecipient.class);
    }

    /** Puts every FAILED recipient of a job back to PENDING. Returns how many were reset. */
    public long resetFailedToPending(String jobId) {
        return mongo.updateMulti(
                Query.query(Criteria.where("jobId").is(jobId).and("status").is(BulkEmailRecipient.Status.FAILED)),
                new Update()
                        .set("status", BulkEmailRecipient.Status.PENDING)
                        .unset("error").unset("messageId").unset("sentAt"),
                BulkEmailRecipient.class).getModifiedCount();
    }

    /** Marks every still-PENDING recipient of a job as FAILED with {@code error}. */
    public long failAllPending(String jobId, String error) {
        return mongo.updateMulti(
                Query.query(Criteria.where("jobId").is(jobId).and("status").is(BulkEmailRecipient.Status.PENDING)),
                new Update().set("status", BulkEmailRecipient.Status.FAILED).set("error", error),
                BulkEmailRecipient.class).getModifiedCount();
    }

    /** Removes everything stored for a job (used to roll back a half-created campaign). */
    public void deleteJobData(String jobId) {
        mongo.remove(Query.query(Criteria.where("jobId").is(jobId)), BulkEmailRecipient.class);
        mongo.remove(Query.query(Criteria.where("jobId").is(jobId)), BulkEmailDetailRow.class);
    }

    // ── Reads ───────────────────────────────────────────────────────────────────

    /** Keyset page of PENDING recipients after {@code afterSeq}, in send order. */
    public List<BulkEmailRecipient> nextPending(String jobId, int afterSeq, int limit) {
        Query q = Query.query(Criteria.where("jobId").is(jobId)
                        .and("status").is(BulkEmailRecipient.Status.PENDING)
                        .and("seq").gt(afterSeq))
                .with(Sort.by("seq")).limit(limit);
        return mongo.find(q, BulkEmailRecipient.class);
    }

    /** Sheet-2 rows matching one recipient's id value — a single indexed lookup. */
    public List<BulkEmailDetailRow> detailRowsFor(String jobId, String idValue) {
        return detailRepo.findByJobIdAndMatchKeyOrderBySeqAsc(jobId, BulkEmailDetailRow.matchKeyOf(idValue));
    }

    /** Streams a job's recipients matching {@code extra} in seq order, one page at a time. */
    public void forEach(String jobId, Criteria extra, Consumer<BulkEmailRecipient> action) {
        int lastSeq = -1;
        while (true) {
            Criteria c = Criteria.where("jobId").is(jobId).and("seq").gt(lastSeq);
            Query q = (extra == null ? Query.query(c) : Query.query(new Criteria().andOperator(c, extra)))
                    .with(Sort.by("seq")).limit(STREAM_PAGE);
            List<BulkEmailRecipient> page = mongo.find(q, BulkEmailRecipient.class);
            if (page.isEmpty()) return;
            for (BulkEmailRecipient r : page) {
                action.accept(r);
                lastSeq = r.getSeq();
            }
        }
    }

    /** One page of a job's recipients for the Recipients tab, in seq order. */
    public List<BulkEmailRecipient> page(String jobId, Filter filter, String search, int page, int size) {
        Query q = Query.query(criteria(jobId, filter, search))
                .with(Sort.by("seq")).skip((long) page * size).limit(size);
        q.fields().exclude("data");   // the raw spreadsheet row is never shown in the table
        return mongo.find(q, BulkEmailRecipient.class);
    }

    public long count(String jobId, Filter filter, String search) {
        return mongo.count(Query.query(criteria(jobId, filter, search)), BulkEmailRecipient.class);
    }

    /** Counts for every Recipients-tab filter chip, in one pass over the job's recipients. */
    public Map<String, Long> filterCounts(String jobId) {
        Document opened = new Document("$gt", List.of("$openCount", 0));
        Document group = new Document("_id", null)
                .append("all",          new Document("$sum", 1))
                .append("sent",         new Document("$sum", cond(new Document("$eq", List.of("$status", "SENT")))))
                .append("failed",       new Document("$sum", cond(new Document("$eq", List.of("$status", "FAILED")))))
                .append("pending",      new Document("$sum", cond(new Document("$eq", List.of("$status", "PENDING")))))
                .append("opened",       new Document("$sum", cond(opened)))
                .append("clicked",      new Document("$sum", cond(new Document("$gt", List.of("$clickCount", 0)))))
                .append("unopened",     new Document("$sum", cond(new Document("$and", List.of(
                        new Document("$eq", List.of("$status", "SENT")),
                        new Document("$not", List.of(opened)))))))
                .append("unsubscribed", new Document("$sum", cond(new Document("$eq", List.of("$unsubscribed", true)))));
        Document out = mongo.getCollection(BulkEmailJobStore.RECIPIENTS).aggregate(List.of(
                new Document("$match", new Document("jobId", jobId)),
                new Document("$group", group))).first();

        Map<String, Long> counts = new LinkedHashMap<>();
        for (String k : List.of("all", "sent", "failed", "pending", "opened", "clicked", "unopened", "unsubscribed")) {
            Object v = out == null ? null : out.get(k);
            counts.put(k, v instanceof Number n ? n.longValue() : 0L);
        }
        return counts;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static Criteria criteria(String jobId, Filter filter, String search) {
        List<Criteria> and = new ArrayList<>();
        and.add(Criteria.where("jobId").is(jobId));
        switch (filter == null ? Filter.ALL : filter) {
            case SENT         -> and.add(Criteria.where("status").is(BulkEmailRecipient.Status.SENT));
            case FAILED       -> and.add(Criteria.where("status").is(BulkEmailRecipient.Status.FAILED));
            case PENDING      -> and.add(Criteria.where("status").is(BulkEmailRecipient.Status.PENDING));
            case OPENED       -> and.add(Criteria.where("openCount").gt(0));
            case CLICKED      -> and.add(Criteria.where("clickCount").gt(0));
            case UNOPENED     -> and.add(new Criteria().andOperator(
                                        Criteria.where("status").is(BulkEmailRecipient.Status.SENT),
                                        Criteria.where("openCount").not().gt(0)));
            case UNSUBSCRIBED -> and.add(Criteria.where("unsubscribed").is(true));
            default           -> { /* ALL */ }
        }
        if (search != null && !search.isBlank()) {
            String rx = Pattern.quote(search.trim());
            and.add(new Criteria().orOperator(
                    Criteria.where("recipientEmail").regex(rx, "i"),
                    Criteria.where("recipientName").regex(rx, "i")));
        }
        return new Criteria().andOperator(and.toArray(new Criteria[0]));
    }

    private static Document cond(Document predicate) {
        return new Document("$cond", List.of(predicate, 1, 0));
    }

    private static Query byId(String id) {
        return Query.query(Criteria.where("_id").is(id));
    }
}
