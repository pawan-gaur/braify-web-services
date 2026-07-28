package com.braify.feature.bulkemail.service;

import com.braify.feature.bulkemail.model.BulkEmailJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Two responsibilities for bulk-email robustness:
 *
 * <ol>
 *   <li><b>Scheduled dispatch</b> — every minute, promotes {@code SCHEDULED} jobs whose
 *       {@code scheduledAt} has passed to {@code PENDING} and hands them to the processor.
 *       The status flip is a conditional {@code updateFirst} so two app instances can't
 *       both dispatch the same job.</li>
 *   <li><b>Crash recovery</b> — on startup, any job left in {@code PROCESSING} or
 *       {@code PENDING} (i.e. interrupted by a restart) is re-dispatched. The processor
 *       only sends rows still in {@code PENDING} status, so already-sent recipients are
 *       never emailed twice.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BulkEmailScheduler {

    private final MongoTemplate       mongoTemplate;
    private final BulkEmailProcessor  bulkEmailProcessor;

    @Scheduled(fixedDelay = 60_000)   // every minute
    public void dispatchDueScheduled() {
        Query due = Query.query(Criteria.where("status").is(BulkEmailJob.JobStatus.SCHEDULED)
                .and("scheduledAt").lte(LocalDateTime.now()));
        due.fields().include("_id");

        for (BulkEmailJob job : mongoTemplate.find(due, BulkEmailJob.class)) {
            String id = job.getId();
            // Claim the job: only one flip from SCHEDULED→PENDING succeeds.
            long claimed = mongoTemplate.updateFirst(
                    Query.query(Criteria.where("_id").is(id).and("status").is(BulkEmailJob.JobStatus.SCHEDULED)),
                    new Update().set("status", BulkEmailJob.JobStatus.PENDING),
                    BulkEmailJob.class).getModifiedCount();
            if (claimed > 0) {
                log.info("[BulkEmailScheduler] Dispatching scheduled campaign {}", id);
                bulkEmailProcessor.processJobAsync(id);
            }
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resumeInterrupted() {
        Query q = Query.query(Criteria.where("status")
                .in(BulkEmailJob.JobStatus.PROCESSING, BulkEmailJob.JobStatus.PENDING));
        q.fields().include("_id");

        List<BulkEmailJob> stuck = mongoTemplate.find(q, BulkEmailJob.class);
        if (stuck.isEmpty()) return;
        log.info("[BulkEmailScheduler] Resuming {} interrupted campaign(s) after restart", stuck.size());
        for (BulkEmailJob job : stuck) {
            bulkEmailProcessor.processJobAsync(job.getId());
        }
    }
}
