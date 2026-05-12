package com.braify.service;

import com.braify.dto.OrganizationRequest;
import com.braify.model.Organization;
import com.braify.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizationService {

    private final OrganizationRepository orgRepository;

    public List<Organization> findAll() {
        log.info("Finding all organizations");
        return orgRepository.findByDeletedFalseOrderByNameAsc();
    }

    public List<Organization> search(String q) {
        if (q == null || q.isBlank()) return findAll();
        return orgRepository.searchByQuery(q.trim());
    }

    public Organization findById(String id) {
        return orgRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Organization not found: " + id));
    }

    public Organization create(OrganizationRequest req) {
        if (orgRepository.existsByCode(req.getCode())) {
            throw new RuntimeException("Code already taken: " + req.getCode());
        }
        Organization org = Organization.builder()
                .name(req.getName())
                .code(req.getCode())
                .description(req.getDescription())
                .active(true)
                .deleted(false)
                .build();
        return orgRepository.save(org);
    }

    public Organization update(String id, OrganizationRequest req) {
        Organization org = findById(id);
        org.setName(req.getName());
        org.setDescription(req.getDescription());
        return orgRepository.save(org);
    }

    public void delete(String id) {
        Organization org = findById(id);
        org.setDeleted(true);
        org.setDeletedAt(LocalDateTime.now());
        orgRepository.save(org);
    }
}
