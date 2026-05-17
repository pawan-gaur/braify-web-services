package com.braify.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures springdoc-openapi:
 * <ul>
 *   <li>Global API metadata (title, version, contact)</li>
 *   <li>JWT Bearer security scheme applied globally</li>
 *   <li>"organizations" group  →  /v3/api-docs/organizations  (used by the frontend doc page)</li>
 *   <li>"all" group            →  /v3/api-docs/all            (full spec)</li>
 * </ul>
 *
 * Dynamic behaviour:  springdoc reflects the live running controllers on every startup.
 * Adding a new @Operation method to any controller in the matched path pattern
 * automatically appears in the spec — no manual update needed.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    // ── Global API info + security scheme ────────────────────────────────────

    @Bean
    public OpenAPI braifyOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Braify API")
                        .description(
                                "REST API for the Braify PDF & Email Template platform. " +
                                "All endpoints (except /api/auth/**, /api/esign/sign/**, " +
                                "/api/esign/verify/**, and POST /api/onboarding) require a " +
                                "**JWT Bearer token** obtained from POST /api/auth/login.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Braify Platform")
                                .email("support@braify.io"))
                        .license(new License().name("Proprietary")))
                // Declare the Bearer scheme once; @SecurityRequirement on individual
                // operations (or the global addSecurityItem below) wires it in.
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Paste the JWT token returned by POST /api/auth/login")))
                // Apply Bearer auth globally to every operation
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }

    // ── Organization API group ────────────────────────────────────────────────
    //
    // Exposes:  GET /v3/api-docs/organizations
    // Covers: OrganizationController, OrgBrandingController, QuotaController,
    //         TemplateSharingController

    @Bean
    public GroupedOpenApi organizationsGroup() {
        return GroupedOpenApi.builder()
                .group("organizations")
                .displayName("Organization Management")
                .pathsToMatch(
                        "/api/organizations/**",
                        "/api/sharing/**"
                )
                .build();
    }

    // ── PDF Templates group ───────────────────────────────────────────────────
    //
    // Exposes:  GET /v3/api-docs/pdf-templates
    // Covers: TemplateController + PdfController

    @Bean
    public GroupedOpenApi pdfTemplatesGroup() {
        return GroupedOpenApi.builder()
                .group("pdf-templates")
                .displayName("PDF Templates")
                .pathsToMatch("/api/templates/**", "/api/generate-pdf", "/api/preview-pdf")
                .build();
    }

    // ── Email Templates group ─────────────────────────────────────────────────
    //
    // Exposes:  GET /v3/api-docs/email-templates
    // Covers: EmailTemplateController

    @Bean
    public GroupedOpenApi emailTemplatesGroup() {
        return GroupedOpenApi.builder()
                .group("email-templates")
                .displayName("Email Templates")
                .pathsToMatch("/api/email-templates/**")
                .build();
    }

    // ── E-Sign group ──────────────────────────────────────────────────────────
    //
    // Exposes:  GET /v3/api-docs/esign
    // Covers: ESignCreatorController, ESignClientController, ESignVerifyController

    @Bean
    public GroupedOpenApi esignGroup() {
        return GroupedOpenApi.builder()
                .group("esign")
                .displayName("E-Sign")
                .pathsToMatch("/api/esign/**")
                .build();
    }

    // ── All-endpoints group ───────────────────────────────────────────────────
    //
    // Exposes:  GET /v3/api-docs/all
    // Useful for importing into Postman / Insomnia.

    @Bean
    public GroupedOpenApi allGroup() {
        return GroupedOpenApi.builder()
                .group("all")
                .displayName("All Endpoints")
                .pathsToMatch("/api/**")
                .build();
    }
}
