package com.braify.feature.esign.controller;

import com.braify.feature.esign.dto.ContactResponse;
import com.braify.feature.esign.service.OrgContactService;
import com.braify.feature.user.model.AppUser;
import com.braify.security.UserDetailsImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Recipient suggestions for the e-sign send flow. Returns the caller's organisation's
 * address book (recipients previously sent to), ranked by usage, so the frontend can
 * autocomplete emails locally without a per-keystroke round trip.
 */
@Slf4j
@Tag(name = "E-Sign — Contacts", description = "Recipient autocomplete suggestions for the send flow.")
@RestController
@RequestMapping("/api/esign/contacts")
@RequiredArgsConstructor
public class ESignContactController {

    private final OrgContactService contactService;

    private AppUser currentUser(Authentication auth) {
        return ((UserDetailsImpl) auth.getPrincipal()).getAppUser();
    }

    @Operation(summary = "List recipient suggestions for the caller's organisation",
               description = "Returns up to 200 previously-used recipients (name + email) ranked by usage. " +
                             "The client caches this once and filters it locally as the user types.")
    @GetMapping
    public List<ContactResponse> list(Authentication auth) {
        return contactService.suggest(currentUser(auth).getOrganizationId());
    }
}
