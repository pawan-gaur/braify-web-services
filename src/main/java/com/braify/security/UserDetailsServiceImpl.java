package com.braify.security;

import com.braify.feature.user.model.AppUser;
import com.braify.feature.user.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final AppUserRepository userRepository;
    private final AuthCache authCache;

    /** Uncached — used by Spring Security's password authentication. */
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmail(email)
                .map(UserDetailsImpl::new)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
    }

    /**
     * Per-request JWT path: same result as {@link #loadUserByUsername} but backed by a short-TTL
     * cache (evicted on every AppUser save/delete). Each call gets its own copy of the user so a
     * request that mutates its principal's AppUser can never leak into other requests.
     */
    public UserDetails loadUserCached(String email) throws UsernameNotFoundException {
        AppUser cached = authCache.users.get(email, e -> userRepository.findByEmail(e).orElse(null));
        if (cached == null) throw new UsernameNotFoundException("User not found: " + email);
        return new UserDetailsImpl(copyOf(cached));
    }

    private static AppUser copyOf(AppUser src) {
        AppUser copy = new AppUser();
        BeanUtils.copyProperties(src, copy);
        copy.setPreviousPasswords(src.getPreviousPasswords() != null ? new ArrayList<>(src.getPreviousPasswords()) : null);
        copy.setMfaRecoveryCodes(src.getMfaRecoveryCodes() != null ? new ArrayList<>(src.getMfaRecoveryCodes()) : null);
        return copy;
    }
}
