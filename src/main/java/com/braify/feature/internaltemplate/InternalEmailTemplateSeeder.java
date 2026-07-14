package com.braify.feature.internaltemplate;

import com.braify.feature.email.model.EmailTemplate;
import com.braify.feature.email.repository.EmailTemplateRepository;
import com.braify.feature.pdf.model.Template;
import com.braify.shared.TemplateType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * On startup:
 * <ol>
 *   <li>Tags every pre-existing template record (email + PDF) that has no {@code type} as
 *       {@link TemplateType#EXTERNAL}, so the type-scoped queries pick them up.</li>
 *   <li>Idempotently seeds the platform's INTERNAL system email templates (invite, reset,
 *       onboarding, e-sign) from each {@link InternalTemplateProvider}, keyed by code.</li>
 * </ol>
 */
@Slf4j
@Component
@Order(20) // after DataSeeder
@RequiredArgsConstructor
public class InternalEmailTemplateSeeder implements CommandLineRunner {

    private final List<InternalTemplateProvider> providers;
    private final EmailTemplateRepository          emailTemplateRepository;
    private final MongoTemplate                    mongoTemplate;

    @Override
    public void run(String... args) {
        migrateExistingToExternal();
        seedInternalTemplates();
    }

    /** Backfill {@code type = EXTERNAL} on legacy records that predate the field. */
    private void migrateExistingToExternal() {
        Query missingType = new Query(Criteria.where("type").is(null));
        Update setExternal = new Update().set("type", TemplateType.EXTERNAL);

        long emails = mongoTemplate.updateMulti(missingType, setExternal, EmailTemplate.class).getModifiedCount();
        long pdfs   = mongoTemplate.updateMulti(missingType, setExternal, Template.class).getModifiedCount();

        if (emails > 0 || pdfs > 0) {
            log.info("[TemplateType] Tagged {} email + {} PDF legacy templates as EXTERNAL", emails, pdfs);
        }
    }

    /**
     * Upsert every INTERNAL template from code. These are code-owned system templates,
     * so an existing record is re-synced whenever its subject/html/name drifts from the
     * seed (design changes ship on the next restart). Only writes when something changed.
     */
    private void seedInternalTemplates() {
        int created = 0, updated = 0;
        for (InternalTemplateProvider provider : providers) {
            for (InternalTemplateSeed seed : provider.internalTemplateSeeds()) {
                EmailTemplate existing = emailTemplateRepository.findByCodeAndDeletedFalse(seed.code()).orElse(null);
                if (existing == null) {
                    EmailTemplate t = new EmailTemplate();
                    t.setName(seed.name());
                    t.setSubject(seed.subjectWithTokens());
                    t.setHtmlContent(seed.htmlWithTokens());
                    t.setPlaceholders(seed.placeholders());
                    t.setType(TemplateType.INTERNAL);
                    t.setCode(seed.code());
                    t.setOrganizationId(null); // platform-global
                    t.setDeleted(false);
                    emailTemplateRepository.save(t);
                    created++;
                    log.info("[InternalTemplate] Seeded '{}' (code={})", seed.name(), seed.code());
                } else {
                    boolean changed =
                            !java.util.Objects.equals(existing.getSubject(),     seed.subjectWithTokens()) ||
                            !java.util.Objects.equals(existing.getHtmlContent(), seed.htmlWithTokens())   ||
                            !java.util.Objects.equals(existing.getName(),        seed.name());
                    if (changed) {
                        existing.setName(seed.name());
                        existing.setSubject(seed.subjectWithTokens());
                        existing.setHtmlContent(seed.htmlWithTokens());
                        existing.setPlaceholders(seed.placeholders());
                        existing.setType(TemplateType.INTERNAL);
                        emailTemplateRepository.save(existing);
                        updated++;
                        log.info("[InternalTemplate] Re-synced '{}' (code={}) from code", seed.name(), seed.code());
                    }
                }
            }
        }
        if (created == 0 && updated == 0) {
            log.debug("[InternalTemplate] All INTERNAL email templates already up to date");
        }
    }
}
