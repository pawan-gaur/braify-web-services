package com.braify.feature.fileupload.service;

import com.braify.feature.fileupload.model.FileIdCounter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Generates human-readable, globally unique file IDs in the format:
 * <pre>
 *   F&lt;yyyyMMdd&gt;&lt;zero-padded-8-digit-sequence&gt;
 *   e.g. F2026052000000001
 * </pre>
 *
 * <p>The sequence resets each calendar day. The counter is stored in the
 * {@code file_id_counters} MongoDB collection and atomically incremented
 * with {@code $inc} to prevent duplicates under concurrent requests.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileIdGenerator {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final MongoTemplate mongoTemplate;

    /**
     * Atomically increments today's counter and returns the next file ID.
     *
     * @return unique file ID string, e.g. {@code F2026052000000001}
     */
    public String next() {
        String dateKey = LocalDate.now().format(DATE_FMT);

        Query  q = Query.query(Criteria.where("_id").is(dateKey));
        // $setOnInsert only fires on a new document (upsert insert path).
        // $inc is intentionally NOT combined with $setOnInsert on the same field —
        // MongoDB forbids two operators touching the same path in one command.
        // $inc on a missing field initialises it to 0 then adds 1, so no pre-seeding needed.
        Update u = new Update()
                .setOnInsert("_id", dateKey)
                .inc("seq", 1L);

        FileIdCounter counter = mongoTemplate.findAndModify(
                q, u,
                FindAndModifyOptions.options().upsert(true).returnNew(true),
                FileIdCounter.class);

        long seq = (counter != null) ? counter.getSeq() : 1L;
        return String.format("F%s%08d", dateKey, seq);
    }
}
