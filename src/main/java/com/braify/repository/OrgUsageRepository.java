package com.braify.repository;

import com.braify.model.OrgUsage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrgUsageRepository extends MongoRepository<OrgUsage, String> {

    Optional<OrgUsage> findByOrganizationIdAndYearAndMonth(String organizationId, int year, int month);

    List<OrgUsage> findByOrganizationIdOrderByYearDescMonthDesc(String organizationId, Pageable pageable);
}
