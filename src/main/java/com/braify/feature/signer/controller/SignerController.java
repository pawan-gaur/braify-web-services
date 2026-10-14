package com.braify.feature.signer.controller;

import com.braify.config.RequestLoggingFilter;
import com.braify.feature.auth.dto.LoginResponse;
import com.braify.feature.auth.service.AuthService;
import com.braify.feature.auth.service.PasswordPolicyService;
import com.braify.feature.user.model.AppUser;
import com.braify.feature.user.repository.AppUserRepository;
import com.braify.feature.session.model.UserSession;
import com.braify.feature.session.repository.UserSessionRepository;
import com.braify.feature.signer.EmailVerificationRequiredException;
import com.braify.feature.signer.dto.SignerInboxResponse;
import com.braify.feature.signer.service.EmailOtpService;
import com.braify.feature.signer.service.SignerAuthService;
import com.braify.feature.signer.service.SignerEmails;
import com.braify.feature.signer.service.SignerInboxService;
import com.braify.security.SignerPrincipal;
import com.braify.security.UserDetailsImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Signer inbox — "documents I need to sign / have signed", across every organization.
 *
 * <p>Callable with a signer-portal session (external recipient, {@code ROLE_SIGNER}) or a
 * normal Braify user session; either way the caller only ever sees documents addressed
 * to their own email. Signer sessions are rejected everywhere outside {@code /api/signer/**}
 * by {@link com.braify.security.JwtAuthFilter}.
 *
 * <p>A Braify user session must also have proven the mailbox ({@link UserSession#isEmailVerified()}):
 * admins can create users with any email and set their passwords, so a password session alone
 * must never unlock documents another organization sent to that address.
 */
@Slf4j
@Tag(name = "Signer Inbox", description = "Documents awaiting / signed by the current user's email, across organizations.")
@RestController
@RequestMapping("/api/signer")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SIGNER','PLATFORM_ADMIN','ORG_ADMIN','ADMIN','USER')")
public class SignerController {

    private final SignerInboxService inboxService;
    private final SignerAuthService  signerAuthService;
    private final EmailOtpService    emailOtpService;
    private final UserSessionRepository sessionRepository;
    private final AppUserRepository     userRepository;
    private final PasswordEncoder       passwordEncoder;
    private final PasswordPolicyService passwordPolicyService;

    @Operation(summary = "Current signer profile (signer-portal sessions)")
    @GetMapping("/me")
    public ResponseEntity<LoginResponse> me(Authentication auth, HttpServletRequest http) {
        boolean sessionVerified = currentSession(http).isEmailVerified();
        if (auth.getPrincipal() instanceof SignerPrincipal sp) {
            return ResponseEntity.ok(signerAuthService.me(sp.accountId(), sessionVerified));
        }
        UserDetailsImpl ud = (UserDetailsImpl) auth.getPrincipal();
        boolean hasPassword = AuthService.hasUsablePassword(ud.getAppUser());
        return ResponseEntity.ok(LoginResponse.builder()
                .userId(ud.getId())
                .email(ud.getAppUser().getEmail())
                .firstName(ud.getAppUser().getFirstName())
                .lastName(ud.getAppUser().getLastName())
                .role(ud.getAppUser().getRole().name())
                .accountType("USER")
                .hasPassword(hasPassword)
                .passwordNeedsCurrent(hasPassword && !sessionVerified)
                .build());
    }

    @Operation(summary = "Email a code to confirm the mailbox (Braify user sessions)",
               description = "Needed once per session before a Braify user can open the signer inbox, unless they signed in with an email code.")
    @PostMapping("/verify-email/request")
    public ResponseEntity<Map<String, Object>> requestEmailVerification(Authentication auth, HttpServletRequest http) {
        emailOtpService.issue(email(auth), clientIp(http));
        return ResponseEntity.ok(Map.of(
                "resendAfterSeconds", emailOtpService.getResendSeconds(),
                "ttlMinutes", emailOtpService.getTtlMinutes()));
    }

    @Operation(summary = "Confirm the mailbox with the emailed code", description = "Body: `{ code }`.")
    @PostMapping("/verify-email/confirm")
    public ResponseEntity<Map<String, Boolean>> confirmEmailVerification(@RequestBody Map<String, String> body,
                                                                          Authentication auth, HttpServletRequest http) {
        UserSession session = currentSession(http);
        emailOtpService.verify(email(auth), body.get("code"));
        session.setEmailVerified(true);
        sessionRepository.save(session);
        log.info("Signer inbox: mailbox confirmed for '{}' (session {})", email(auth), session.getId());
        return ResponseEntity.ok(Map.of("emailVerified", true));
    }

    @Operation(summary = "Set or change the signer-portal password",
               description = "Body: `{ currentPassword?, newPassword }`. `currentPassword` is required only when a password exists and this session did not sign in with an email code. Signer-portal accounts and SIGNER-role users only; other Braify users use their profile.")
    @PutMapping("/me/password")
    public ResponseEntity<Map<String, String>> setPassword(@RequestBody Map<String, String> body, Authentication auth,
                                                           HttpServletRequest http) {
        boolean sessionVerified = currentSession(http).isEmailVerified();
        if (auth.getPrincipal() instanceof UserDetailsImpl ud) {
            // Only SIGNER-role users manage their password here — they cannot reach /api/profile.
            if (ud.getAppUser().getRole() != AppUser.Role.SIGNER) {
                throw new AccessDeniedException("Change your password from your profile.");
            }
            AppUser user = ud.getAppUser();
            String current = body.get("currentPassword");
            // Current password required unless there is none yet, or this session proved the mailbox.
            if (AuthService.hasUsablePassword(user) && !sessionVerified
                    && (current == null || !passwordEncoder.matches(current, user.getPassword()))) {
                throw new IllegalArgumentException("Current password is incorrect");
            }
            passwordPolicyService.applyNewPassword(user, body.get("newPassword"));
            user.setMustChangePassword(false);
            // Chosen inside a session that proved the mailbox → later password sign-ins count as proof.
            if (sessionVerified) user.setPasswordMailboxVerified(true);
            userRepository.save(user);
            return ResponseEntity.ok(Map.of("message", "Password saved. You can now sign in with your email and password."));
        }
        SignerPrincipal sp = (SignerPrincipal) auth.getPrincipal();
        signerAuthService.setPassword(sp.accountId(), body.get("currentPassword"), body.get("newPassword"), sessionVerified);
        return ResponseEntity.ok(Map.of("message", "Password saved. You can now sign in with your email and password."));
    }

    @Operation(summary = "List inbox documents",
               description = "`tab` = ACTION (needs your signature now), UPCOMING (waiting on an earlier signer), SIGNED (you signed), OTHER (expired, cancelled or CC'd).")
    @GetMapping("/documents")
    public ResponseEntity<SignerInboxResponse> list(@RequestParam(defaultValue = "ACTION") String tab,
                                                    @RequestParam(required = false) String orgId,
                                                    @RequestParam(required = false) String search,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size,
                                                    Authentication auth, HttpServletRequest http) {
        return ResponseEntity.ok(inboxService.list(verifiedEmail(auth, http), tab, orgId, search, page, size));
    }

    @Operation(summary = "Per-tab document counts")
    @GetMapping("/documents/counts")
    public ResponseEntity<Map<String, Long>> counts(Authentication auth, HttpServletRequest http) {
        return ResponseEntity.ok(inboxService.counts(verifiedEmail(auth, http)));
    }

    @Operation(summary = "Start signing a document",
               description = "Returns `{ token }` — the caller's signing token for this document, to open `/sign/{token}`. Only allowed when it is the caller's turn to sign.")
    @PostMapping("/documents/{id}/sign-session")
    public ResponseEntity<Map<String, String>> signSession(@PathVariable String id, Authentication auth,
                                                           HttpServletRequest http) {
        String token = inboxService.openForSigning(verifiedEmail(auth, http), id, clientIp(http), http.getHeader("User-Agent"));
        return ResponseEntity.ok(Map.of("token", token));
    }

    @Operation(summary = "View a document read-only",
               description = "Returns `{ token }` — a short-lived view token for `/esign/view/{token}`.")
    @PostMapping("/documents/{id}/view-session")
    public ResponseEntity<Map<String, String>> viewSession(@PathVariable String id, Authentication auth,
                                                           HttpServletRequest http) {
        return ResponseEntity.ok(Map.of("token", inboxService.openForView(verifiedEmail(auth, http), id)));
    }

    @Operation(summary = "Download the final signed PDF (completed documents only)")
    @GetMapping("/documents/{id}/signed-pdf")
    public ResponseEntity<byte[]> signedPdf(@PathVariable String id, Authentication auth, HttpServletRequest http) {
        SignerInboxService.SignedPdf pdf =
                inboxService.signedPdf(verifiedEmail(auth, http), id, clientIp(http), http.getHeader("User-Agent"));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + pdf.fileName() + "\"")
                .body(pdf.bytes());
    }

    /**
     * The caller's email, for inbox reads/actions. Signer sessions are verified by construction;
     * a Braify user session must have proven the mailbox in this session.
     */
    private String verifiedEmail(Authentication auth, HttpServletRequest http) {
        if (!(auth.getPrincipal() instanceof SignerPrincipal) && !currentSession(http).isEmailVerified()) {
            throw new EmailVerificationRequiredException();
        }
        return email(auth);
    }

    private UserSession currentSession(HttpServletRequest http) {
        Object jti = http.getAttribute(RequestLoggingFilter.SESSION_ID_ATTR);
        return (jti instanceof String j ? sessionRepository.findByJtiAndActiveTrue(j) : java.util.Optional.<UserSession>empty())
                .orElseThrow(() -> new AccessDeniedException("Session not found"));
    }

    /** The caller's normalized email — the only key used to scope inbox access. */
    private static String email(Authentication auth) {
        Object p = auth.getPrincipal();
        String raw = p instanceof SignerPrincipal sp ? sp.email()
                : ((UserDetailsImpl) p).getAppUser().getEmail();
        return SignerEmails.normalize(raw);
    }

    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) return xRealIp.trim();
        return request.getRemoteAddr();
    }
}
