package com.braify.repository;

import com.braify.model.OnboardingRequest;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OnboardingRequestRepository extends MongoRepository<OnboardingRequest, String> {

    List<OnboardingRequest> findAllByOrderBySubmittedAtDesc();

    List<OnboardingRequest> findByStatusOrderBySubmittedAtDesc(OnboardingRequest.Status status);

    Optional<OnboardingRequest> findByApplicantEmail(String email);

    boolean existsByApplicantEmail(String email);

    long countByStatus(OnboardingRequest.Status status);
}
