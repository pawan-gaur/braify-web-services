package com.braify.feature.internaltemplate;

import com.braify.feature.email.model.EmailTemplate;
import com.braify.feature.email.repository.EmailTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Resolves INTERNAL system email templates by their stable {@link InternalTemplateCodes} code.
 *
 * <p>Senders look up the template here and pass its (tokenised) subject + HTML to the
 * {@code EmailDispatcher} along with a value map — the dispatcher performs the
 * {@code {{token}}} substitution. When no record is found (e.g. before the first seed
 * runs) callers fall back to their built-in HTML so transactional mail never breaks.
 */
@Service
@RequiredArgsConstructor
public class InternalEmailTemplateService {

    private final EmailTemplateRepository repository;

    /** Returns the INTERNAL template for the given code, if present and not deleted. */
    public Optional<EmailTemplate> find(String code) {
        return repository.findByCodeAndDeletedFalse(code);
    }
}
