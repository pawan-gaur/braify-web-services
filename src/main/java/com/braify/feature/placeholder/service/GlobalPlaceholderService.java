package com.braify.feature.placeholder.service;

import com.braify.feature.placeholder.dto.GlobalPlaceholderRequest;
import com.braify.feature.placeholder.dto.GlobalPlaceholderResponse;
import com.braify.feature.placeholder.model.GlobalPlaceholder;
import com.braify.feature.placeholder.repository.GlobalPlaceholderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CRUD + resolution for org-level global placeholders.
 *
 * <p>{@link #mergeForOrg(String, Map)} is the integration point used by the
 * email and PDF render paths: it layers per-call values on top of the org's
 * configured globals so that a template's {@code {{token}}} resolves to the
 * global value unless the caller supplied an explicit non-blank override.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GlobalPlaceholderService {

    private final GlobalPlaceholderRepository repository;

    // ── CRUD ──────────────────────────────────────────────────────────────────

    public List<GlobalPlaceholderResponse> list(String orgId) {
        return repository.findByOrganizationIdOrderByKeyAsc(orgId)
                .stream().map(GlobalPlaceholderResponse::from).toList();
    }

    public GlobalPlaceholderResponse create(String orgId, GlobalPlaceholderRequest req) {
        String key = req.getKey().trim();
        if (repository.existsByOrganizationIdAndKey(orgId, key)) {
            throw new IllegalArgumentException("A placeholder with key '" + key + "' already exists");
        }
        GlobalPlaceholder p = GlobalPlaceholder.builder()
                .organizationId(orgId)
                .key(key)
                .value(req.getValue())
                .label(req.getLabel())
                .type(req.getType() != null ? req.getType() : GlobalPlaceholder.Type.TEXT)
                .build();
        try {
            p = repository.save(p);
        } catch (DuplicateKeyException e) {
            // Race with the unique index — surface a friendly message.
            throw new IllegalArgumentException("A placeholder with key '" + key + "' already exists");
        }
        log.info("Global placeholder created: key='{}' org='{}'", key, orgId);
        return GlobalPlaceholderResponse.from(p);
    }

    public GlobalPlaceholderResponse update(String id, String orgId, GlobalPlaceholderRequest req) {
        GlobalPlaceholder p = repository.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new IllegalArgumentException("Placeholder not found: " + id));

        String newKey = req.getKey().trim();
        // If the key changed, guard against colliding with a different existing placeholder.
        if (!newKey.equals(p.getKey()) && repository.existsByOrganizationIdAndKey(orgId, newKey)) {
            throw new IllegalArgumentException("A placeholder with key '" + newKey + "' already exists");
        }

        p.setKey(newKey);
        p.setValue(req.getValue());
        p.setLabel(req.getLabel());
        p.setType(req.getType() != null ? req.getType() : GlobalPlaceholder.Type.TEXT);

        try {
            p = repository.save(p);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("A placeholder with key '" + newKey + "' already exists");
        }
        log.info("Global placeholder updated: id='{}' key='{}' org='{}'", id, newKey, orgId);
        return GlobalPlaceholderResponse.from(p);
    }

    public void delete(String id, String orgId) {
        GlobalPlaceholder p = repository.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new IllegalArgumentException("Placeholder not found: " + id));
        repository.delete(p);
        log.info("Global placeholder deleted: id='{}' key='{}' org='{}'", id, p.getKey(), orgId);
    }

    // ── Resolution (used by render/send paths) ─────────────────────────────────

    /** Returns the org's global placeholders as a {@code key → value} map (empty when orgId is null). */
    public Map<String, Object> resolveForOrg(String orgId) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (orgId == null || orgId.isBlank()) return map;
        for (GlobalPlaceholder p : repository.findByOrganizationIdOrderByKeyAsc(orgId)) {
            map.put(p.getKey(), p.getValue() != null ? p.getValue() : "");
        }
        return map;
    }

    /**
     * Layers {@code overrides} on top of the org's globals. A global is only
     * overridden by a non-null, non-blank override value, so blank per-call
     * inputs never wipe out a configured global.
     */
    public Map<String, Object> mergeForOrg(String orgId, Map<String, Object> overrides) {
        Map<String, Object> merged = resolveForOrg(orgId);
        if (overrides != null) {
            overrides.forEach((k, v) -> {
                if (v != null && !v.toString().isBlank()) {
                    merged.put(k, v);
                }
            });
        }
        return merged;
    }
}
