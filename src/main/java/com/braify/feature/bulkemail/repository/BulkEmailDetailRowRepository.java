package com.braify.feature.bulkemail.repository;

import com.braify.feature.bulkemail.model.BulkEmailDetailRow;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BulkEmailDetailRowRepository extends MongoRepository<BulkEmailDetailRow, String> {

    /** Sheet-2 rows matching one recipient's id value, in original sheet order (indexed lookup). */
    List<BulkEmailDetailRow> findByJobIdAndMatchKeyOrderBySeqAsc(String jobId, String matchKey);

    long countByJobId(String jobId);

    long deleteByJobId(String jobId);
}
