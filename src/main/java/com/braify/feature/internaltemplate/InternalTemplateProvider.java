package com.braify.feature.internaltemplate;

import java.util.List;

/**
 * Implemented by feature services that own system (INTERNAL) email templates.
 * The seeder collects every provider's seeds on startup and upserts the ones
 * that don't yet exist (idempotent, keyed by code).
 */
public interface InternalTemplateProvider {

    List<InternalTemplateSeed> internalTemplateSeeds();
}
