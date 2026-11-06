package com.braify.feature.search;

import com.braify.feature.esign.model.ESignDocument;
import com.braify.feature.email.model.EmailTemplate;
import com.braify.feature.fileupload.model.OrgFile;
import com.braify.feature.organization.model.Organization;
import com.braify.feature.organization.repository.OrganizationRepository;
import com.braify.feature.pdf.model.Template;
import com.braify.feature.user.model.AppUser;
import com.braify.security.UserDetailsImpl;
import com.braify.shared.Feature;
import com.braify.shared.TemplateType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Global search for the top bar. Every group applies the same visibility rules as its own list
 * page (org scope, role scope for e-sign, Admin+ for users) and the org's feature access, so
 * search never reveals anything the caller couldn't already open.
 */
@Tag(name = "Search")
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','ADMIN','USER')")
public class SearchController {

    private static final int PER_GROUP = 5;

    private final MongoTemplate          mongoTemplate;
    private final OrganizationRepository orgRepository;

    @Operation(summary = "Search templates, email templates, e-sign documents, files and users")
    @GetMapping
    public Map<String, Object> search(@RequestParam String q, Authentication auth) {
        AppUser u = ((UserDetailsImpl) auth.getPrincipal()).getAppUser();
        Map<String, Object> out = new LinkedHashMap<>();
        String term = q == null ? "" : q.trim();
        if (term.length() < 2) return out;
        if (term.length() > 100) term = term.substring(0, 100);
        Pattern rx = Pattern.compile(Pattern.quote(term), Pattern.CASE_INSENSITIVE);

        boolean pa = u.getRole() == AppUser.Role.PLATFORM_ADMIN;
        Organization org = pa || u.getOrganizationId() == null ? null : orgRepository.findById(u.getOrganizationId()).orElse(null);
        if (!pa && org == null) return out;

        if (can(u, org, Feature.PDF_TEMPLATES)) {
            Criteria c = Criteria.where("deleted").is(false).and("type").ne(TemplateType.INTERNAL).and("name").regex(rx);
            if (!pa) c = c.and("organizationId").is(u.getOrganizationId());
            out.put("templates", mongoTemplate.find(query(c, "updatedAt", "name"), Template.class).stream()
                    .map(t -> item(t.getId(), t.getName(), "PDF template", "/builder/" + t.getId())).toList());
        }
        if (can(u, org, Feature.EMAIL_TEMPLATES)) {
            Criteria c = Criteria.where("deleted").is(false).and("type").is(TemplateType.EXTERNAL).and("name").regex(rx);
            if (!pa) c = c.and("organizationId").is(u.getOrganizationId());
            out.put("emailTemplates", mongoTemplate.find(query(c, "updatedAt", "name"), EmailTemplate.class).stream()
                    .map(t -> item(t.getId(), t.getName(), "Email template", "/email-builder/" + t.getId())).toList());
        }
        if (can(u, org, Feature.E_SIGN)) {
            Criteria c = new Criteria().orOperator(
                    Criteria.where("title").regex(rx),
                    Criteria.where("signatories.email").regex(rx),
                    Criteria.where("signatories.name").regex(rx),
                    Criteria.where("clientEmail").regex(rx));
            // Same scope as the e-sign list: PA all · ORG_ADMIN org · others their own documents.
            if (u.getRole() == AppUser.Role.ORG_ADMIN) c = new Criteria().andOperator(c, Criteria.where("orgId").is(u.getOrganizationId()));
            else if (!pa) c = new Criteria().andOperator(c, Criteria.where("createdBy").is(u.getId()));
            Query query = query(c, "createdAt", "title", "status");
            out.put("documents", mongoTemplate.find(query, ESignDocument.class).stream()
                    .map(d -> item(d.getId(), d.getTitle(), "E-sign · " + (d.getStatus() != null ? d.getStatus().name().toLowerCase().replace('_', ' ') : ""),
                            "/esign/" + d.getId())).toList());
        }
        if (can(u, org, Feature.FILE_STORAGE)) {
            Criteria c = Criteria.where("status").ne(OrgFile.FileStatus.DELETED).and("originalFilename").regex(rx);
            if (!pa) c = c.and("organizationId").is(u.getOrganizationId());
            out.put("files", mongoTemplate.find(query(c, "createdAt", "originalFilename", "fileId"), OrgFile.class).stream()
                    .map(f -> item(f.getFileId(), f.getOriginalFilename(), "File", "/files")).toList());
        }
        if (pa || u.getRole() == AppUser.Role.ORG_ADMIN || u.getRole() == AppUser.Role.ADMIN) {
            Criteria c = new Criteria().orOperator(
                    Criteria.where("email").regex(rx), Criteria.where("firstName").regex(rx), Criteria.where("lastName").regex(rx));
            if (!pa) c = new Criteria().andOperator(c, Criteria.where("organizationId").is(u.getOrganizationId()));
            out.put("users", mongoTemplate.find(query(c, "createdAt", "firstName", "lastName", "email"), AppUser.class).stream()
                    .map(x -> item(x.getId(), (Objects.toString(x.getFirstName(), "") + " " + Objects.toString(x.getLastName(), "")).trim()
                            + " · " + x.getEmail(), "User", "/users")).toList());
        }
        return out;
    }

    /** Same rule as the UI's hasFeature: org has the feature, and the role is allowed (if restricted). */
    private static boolean can(AppUser u, Organization org, Feature f) {
        if (u.getRole() == AppUser.Role.PLATFORM_ADMIN) return true;
        if (org == null || org.getFeatures() == null || !org.getFeatures().contains(f.name())) return false;
        var access = org.getBranding() != null ? org.getBranding().getFeatureRoleAccess() : null;
        List<String> roles = access != null ? access.get(f.name()) : null;
        return roles == null || roles.isEmpty() || roles.contains(u.getRole().name());
    }

    private static Query query(Criteria c, String sortField, String... fields) {
        Query q = new Query(c).with(Sort.by(Sort.Direction.DESC, sortField)).limit(PER_GROUP);
        q.fields().include("_id");
        for (String f : fields) q.fields().include(f);
        return q;
    }

    private static Map<String, String> item(String id, String title, String kind, String link) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title != null ? title : "Untitled");
        m.put("kind", kind);
        m.put("link", link);
        return m;
    }
}
