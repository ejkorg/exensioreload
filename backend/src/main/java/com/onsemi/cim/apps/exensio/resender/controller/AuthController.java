package com.onsemi.cim.apps.exensio.resender.controller;

import com.onsemi.cim.apps.exensio.resender.config.AppMailProperties;
import com.onsemi.cim.apps.exensio.resender.entity.RefreshToken;
import com.onsemi.cim.apps.exensio.resender.config.JwtUtil;
import com.onsemi.cim.apps.exensio.resender.service.RefreshTokenService;
import com.onsemi.cim.apps.exensio.resender.dto.AuthRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.CookieValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.onsemi.cim.apps.exensio.resender.service.AuthTokenService;
import com.onsemi.cim.apps.exensio.resender.repository.AppUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import com.onsemi.cim.apps.exensio.resender.controller.ProbeStrategy;
import com.onsemi.cim.apps.exensio.resender.controller.DefaultHttpProbeStrategy;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authManager;
    private final JwtUtil jwtUtil;
    private final RefreshTokenService refreshTokenService;
    private final AuthTokenService authTokenService;
    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final com.onsemi.cim.apps.exensio.resender.service.MailService mailService;
    private final boolean returnTokensInResponse;
    private final java.util.List<String> resetUrlBases;
    private final int resetUrlProbeFirst;
    private final int resetUrlProbeTimeoutMillis;
    private final ProbeStrategy probeStrategy;
    // cookie attributes for refresh token; configurable via application properties
    @org.springframework.beans.factory.annotation.Value("${reloader.refresh.cookie-secure:false}")
    private boolean refreshCookieSecure;

    @org.springframework.beans.factory.annotation.Value("${reloader.refresh.cookie-sameSite:None}")
    private String refreshCookieSameSite;

    /** Max-Age in seconds for the refresh_token cookie. 0 means session cookie (no Max-Age set). */
    @org.springframework.beans.factory.annotation.Value("${reloader.refresh.cookie-max-age:0}")
    private int refreshCookieMaxAge;

    @org.springframework.beans.factory.annotation.Autowired
    public AuthController(AuthenticationManager authManager, JwtUtil jwtUtil, RefreshTokenService refreshTokenService,
                          AuthTokenService authTokenService, AppUserRepository userRepository, PasswordEncoder passwordEncoder,
                          com.onsemi.cim.apps.exensio.resender.service.MailService mailService,
                          AppMailProperties appMailProperties,
                          @org.springframework.beans.factory.annotation.Autowired(required = false) ProbeStrategy probeStrategy,
                          @org.springframework.beans.factory.annotation.Value("${reloader.auth.return-tokens-in-response:true}") boolean returnTokensInResponse) {
        this.authManager = authManager;
        this.jwtUtil = jwtUtil;
        this.refreshTokenService = refreshTokenService;
        this.authTokenService = authTokenService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailService = mailService;

        // Read reset URL bases from strongly-typed AppMailProperties (supports YAML list, CSV via env, CLI overrides)
        java.util.List<String> bases = new java.util.ArrayList<>();
        if (appMailProperties != null && appMailProperties.getResetUrlBase() != null) {
            for (String u : appMailProperties.getResetUrlBase()) {
                if (u != null) {
                    String t = u.trim();
                    if (!t.isEmpty()) bases.add(t);
                }
            }
        }
        this.resetUrlBases = java.util.Collections.unmodifiableList(bases);

        // Probe settings from properties
        this.resetUrlProbeFirst = Math.max(1, appMailProperties != null ? appMailProperties.getResetUrlProbeFirst() : 1);
        // enforce a sensible lower bound for probe timeout (100ms)
        this.resetUrlProbeTimeoutMillis = Math.max(100, appMailProperties != null ? appMailProperties.getResetUrlProbeTimeoutMs() : 1000);

        this.probeStrategy = probeStrategy != null ? probeStrategy : new DefaultHttpProbeStrategy();
        this.returnTokensInResponse = returnTokensInResponse;

        // Helpful startup log: shows what bases the app will use when sending reset links
        logger.info("Configured password reset bases: {}", this.resetUrlBases);
    }

    /**
     * Backwards-compatible constructor used by some unit tests and simple code paths.
     * Signature preserved: (..., String resetUrlBaseRaw, boolean returnTokensInResponse)
     */
    public AuthController(AuthenticationManager authManager, JwtUtil jwtUtil, RefreshTokenService refreshTokenService,
                          AuthTokenService authTokenService, AppUserRepository userRepository, PasswordEncoder passwordEncoder,
                          com.onsemi.cim.apps.exensio.resender.service.MailService mailService,
                          String resetUrlBaseRaw,
                          boolean returnTokensInResponse) {
        this(authManager, jwtUtil, refreshTokenService, authTokenService, userRepository, passwordEncoder, mailService,
                // construct a minimal AppMailProperties instance for backwards-compatible tests
                createPropsFromCsv(resetUrlBaseRaw),
                null,
                returnTokensInResponse);
    }

    /**
     * Backwards-compatible constructor kept for some unit tests that provided probe settings inline.
     * Signature preserved: (..., String resetUrlBaseRaw, int resetUrlProbeFirst, int resetUrlProbeTimeoutMillis, boolean returnTokensInResponse)
     */
    public AuthController(AuthenticationManager authManager, JwtUtil jwtUtil, RefreshTokenService refreshTokenService,
                          AuthTokenService authTokenService, AppUserRepository userRepository, PasswordEncoder passwordEncoder,
                          com.onsemi.cim.apps.exensio.resender.service.MailService mailService,
                          String resetUrlBaseRaw,
                          int resetUrlProbeFirst,
                          int resetUrlProbeTimeoutMillis,
                          boolean returnTokensInResponse) {
        this(authManager, jwtUtil, refreshTokenService, authTokenService, userRepository, passwordEncoder, mailService,
                createPropsFromCsv(resetUrlBaseRaw, resetUrlProbeFirst, resetUrlTimeoutOrDefault(resetUrlProbeTimeoutMillis)), null, returnTokensInResponse);
    }

    /**
     * Added to preserve older test signatures that pass a ProbeStrategy instance.
     * Signature: (..., String resetUrlBaseRaw, ProbeStrategy probeStrategy, boolean returnTokensInResponse)
     */
    public AuthController(AuthenticationManager authManager, JwtUtil jwtUtil, RefreshTokenService refreshTokenService,
                          AuthTokenService authTokenService, AppUserRepository userRepository, PasswordEncoder passwordEncoder,
                          com.onsemi.cim.apps.exensio.resender.service.MailService mailService,
                          String resetUrlBaseRaw,
                          ProbeStrategy probeStrategy,
                          boolean returnTokensInResponse) {
        this(authManager, jwtUtil, refreshTokenService, authTokenService, userRepository, passwordEncoder, mailService,
                createPropsFromCsv(resetUrlBaseRaw), probeStrategy, returnTokensInResponse);
    }

    /**
     * Added to preserve older test signatures that pass probe settings and ProbeStrategy.
     * Signature: (..., String resetUrlBaseRaw, int resetUrlProbeFirst, int resetUrlProbeTimeoutMillis, ProbeStrategy probeStrategy, boolean returnTokensInResponse)
     */
    public AuthController(AuthenticationManager authManager, JwtUtil jwtUtil, RefreshTokenService refreshTokenService,
                          AuthTokenService authTokenService, AppUserRepository userRepository, PasswordEncoder passwordEncoder,
                          com.onsemi.cim.apps.exensio.resender.service.MailService mailService,
                          String resetUrlBaseRaw,
                          int resetUrlProbeFirst,
                          int resetUrlProbeTimeoutMillis,
                          ProbeStrategy probeStrategy,
                          boolean returnTokensInResponse) {
        this(authManager, jwtUtil, refreshTokenService, authTokenService, userRepository, passwordEncoder, mailService,
                createPropsFromCsv(resetUrlBaseRaw, resetUrlProbeFirst, resetUrlTimeoutOrDefault(resetUrlProbeTimeoutMillis)), probeStrategy, returnTokensInResponse);
    }

    // helper that ensures a sensible default for probe timeout if caller passes <= 0
    private static int resetUrlTimeoutOrDefault(int timeout) {
        return timeout <= 0 ? 1000 : timeout;
    }

    // small helper to create AppMailProperties for backwards compatible constructors
    private static AppMailProperties createPropsFromCsv(String raw) {
        return createPropsFromCsv(raw, 1, 1000);
    }

    private static AppMailProperties createPropsFromCsv(String raw, int probeFirst, int probeTimeout) {
        AppMailProperties p = new AppMailProperties();
        p.setResetUrlProbeFirst(probeFirst);
        p.setResetUrlProbeTimeoutMs(probeTimeout);
        java.util.List<String> bases = new java.util.ArrayList<>();
        if (raw != null && !raw.isBlank()) {
            String cleaned = raw.trim();
            if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
                cleaned = cleaned.substring(1, cleaned.length() - 1);
            }
            for (String part : cleaned.split("[,\\n\\r]+")) {
                String s = part.trim();
                if (!s.isEmpty()) bases.add(s);
            }
        }
        p.setResetUrlBase(bases);
        return p;
    }

    // --- verification / reset endpoints ---
    @PostMapping("/verify")
    public ResponseEntity<Map<String, String>> verify(@RequestBody Map<String, String> body) {
        String token = body.get("token");
        if (token == null || token.isBlank()) return ResponseEntity.badRequest().build();
        // lookup verification token
        var found = authTokenService.findVerificationToken(token);
        if (found.isEmpty()) return ResponseEntity.status(404).build();
        String username = found.get().getUsername();
        // enable the user
        enableUser(username);
        return ResponseEntity.ok(Map.of("message", "verified"));
    }

    @PostMapping("/request-reset")
    public ResponseEntity<Map<String, String>> requestReset(@RequestBody Map<String, String> body) {
        String input = body.get("username");
        if (input == null || input.isBlank()) return ResponseEntity.badRequest().build();

        // First try username lookup, then email lookup
        var userOpt = userRepository.findByUsername(input);
        if (userOpt.isEmpty()) {
            userOpt = userRepository.findByEmail(input);
            if (userOpt.isPresent()) {
                logger.debug("Password reset lookup: input '{}' matched user by email -> username={}", input, userOpt.get().getUsername());
            }
        } else {
            logger.debug("Password reset lookup: input '{}' matched user by username", input);
        }

        if (userOpt.isEmpty()) {
            logger.debug("Password reset requested for unknown user '{}' ; returning generic success", input);
            return ResponseEntity.ok(Map.of("message", "reset requested"));
        }

        String username = userOpt.get().getUsername();
        var token = authTokenService.createPasswordResetToken(username);

        // attempt to send email if user has an email address configured
        try {
            String to = userOpt.get().getEmail();
            logger.info("[AuthController.requestReset] user='{}' email='{}' willSendEmail={}", username, to, to != null && !to.isBlank());
            if (to == null || to.isBlank()) {
                logger.info("Password reset requested for user='{}' but no email is configured; skipping send", username);
            } else {
                logger.info("Password reset requested for user='{}' email='{}' - attempting to send reset email", username, to);
                String subject = "Password reset request";
                String bodyText;
                if (this.resetUrlBases != null && !this.resetUrlBases.isEmpty()) {
                    // Try to probe the first N configured bases and prefer the first reachable one.
                    String chosen = null;
                    int toProbe = Math.min(this.resetUrlProbeFirst, this.resetUrlBases.size());
                    for (int i = 0; i < toProbe; i++) {
                        String candidate = this.resetUrlBases.get(i);
                        if (this.probeStrategy.probe(candidate, this.resetUrlProbeTimeoutMillis)) {
                            chosen = candidate;
                            break;
                        }
                    }
                    if (chosen != null) {
                        String sep = chosen.contains("?") ? "&" : "?";
                        bodyText = "Reset your password using the following link:\n" + chosen + sep + "token=" + token.getToken();
                    } else if (this.resetUrlBases.size() == 1) {
                        // no reachable probe but single configured base - still include it
                        String only = this.resetUrlBases.get(0);
                        String sep = only.contains("?") ? "&" : "?";
                        bodyText = "Reset your password using the following link:\n" + only + sep + "token=" + token.getToken();
                    } else {
                        // multiple configured, none probed reachable - include multiple links so user has options
                        StringBuilder sb = new StringBuilder();
                        sb.append("Reset your password using one of the links below:\n");
                        for (String base : this.resetUrlBases) {
                            String sep = base.contains("?") ? "&" : "?";
                            sb.append(base).append(sep).append("token=").append(token.getToken()).append("\n");
                        }
                        bodyText = sb.toString();
                    }
                } else {
                    bodyText = "Use this token to reset your password: " + token.getToken();
                }
                mailService.send(to, subject, bodyText);
                logger.info("MailService.send invoked for user='{}' email='{}'", username, to);
            }
        } catch (Exception e) {
            logger.warn("Failed to send reset email for user={}", username, e);
        }
        // return token in response only when explicitly enabled (tests/dev). In prod this should be disabled.
        if (this.returnTokensInResponse) {
            return ResponseEntity.ok(Map.of("resetToken", token.getToken()));
        } else {
            return ResponseEntity.ok(Map.of("message", "reset requested"));
        }
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@RequestBody Map<String, String> body) {
        String token = body.get("token");
        String newPassword = body.get("password");
        if (token == null || token.isBlank() || newPassword == null || newPassword.length() < 8) return ResponseEntity.badRequest().build();
        var found = authTokenService.findPasswordResetToken(token);
        if (found.isEmpty()) return ResponseEntity.status(404).build();
        String username = found.get().getUsername();
        // update user password
        updatePassword(username, newPassword);
        // revoke all refresh tokens to force re-login on all devices
        try {
            refreshTokenService.revokeAllForUser(username);
        } catch (Exception ignored) {}
        return ResponseEntity.ok(Map.of("message", "password reset"));
    }

    private void enableUser(String username) {
        userRepository.findByUsername(username).ifPresent(u -> { u.setEnabled(true); userRepository.save(u); });
    }

    private void updatePassword(String username, String newPassword) {
        userRepository.findByUsername(username).ifPresent(u -> { u.setPasswordHash(passwordEncoder.encode(newPassword)); userRepository.save(u); });
    }

    // probe logic is delegated to ProbeStrategy (injected or default implementation)

    /** Builds a Set-Cookie header value for the refresh_token cookie. */
    private String buildRefreshCookieHeader(String tokenValue) {
        StringBuilder sc = new StringBuilder();
        sc.append("refresh_token=").append(tokenValue).append("; Path=/; HttpOnly");
        if (this.refreshCookieSecure) sc.append("; Secure");
        if (this.refreshCookieMaxAge > 0) sc.append("; Max-Age=").append(this.refreshCookieMaxAge);
        if (this.refreshCookieSameSite != null && !this.refreshCookieSameSite.isBlank()) {
            String s = this.refreshCookieSameSite.trim();
            if ("None".equalsIgnoreCase(s) && !this.refreshCookieSecure) {
                logger.warn("[AuthController] SameSite=None configured but refreshCookieSecure=false; omitting SameSite attribute");
            } else {
                sc.append("; SameSite=").append(s);
            }
        }
        return sc.toString();
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(@RequestBody AuthRequest req, HttpServletResponse resp) {
        Authentication a;
        try {
            a = authManager.authenticate(new UsernamePasswordAuthenticationToken(req.getUsername(), req.getPassword()));
        } catch (org.springframework.security.authentication.BadCredentialsException e) {
            logger.warn("[AuthController.login] invalid password for user={}", req.getUsername());
            return ResponseEntity.status(401).body(Map.of("error", "Invalid password"));
        } catch (org.springframework.security.core.userdetails.UsernameNotFoundException e) {
            logger.warn("[AuthController.login] user not found: {}", req.getUsername());
            return ResponseEntity.status(401).body(Map.of("error", "User not found"));
        } catch (org.springframework.security.core.AuthenticationException e) {
            logger.warn("[AuthController.login] auth failed for user={}: {}", req.getUsername(), e.getMessage());
            return ResponseEntity.status(401).body(Map.of("error", "Authentication failed: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("[AuthController.login] unexpected error during login for user={}", req.getUsername(), e);
            return ResponseEntity.status(500).body(Map.of("error", "System error during login. Check logs."));
        }
        try {
            java.util.List<String> roles = a.getAuthorities().stream().map(granted -> granted.getAuthority()).toList();
            String accessToken = jwtUtil.generateToken(req.getUsername(), roles);

            // create refresh token entity and set cookie
            RefreshToken rt = new RefreshToken();
            rt.setToken("refresh:" + System.currentTimeMillis());
            rt.setUsername(req.getUsername());
            rt.setExpiresAt(java.time.Instant.now().plusSeconds(60 * 60 * 24 * 7)); // 7 days
            refreshTokenService.save(rt);

            // Build Set-Cookie header manually so we can control SameSite and Secure attrs
            resp.addHeader("Set-Cookie", buildRefreshCookieHeader(rt.getToken()));

            // Update last login time and log audit event
            try {
                userRepository.findByUsername(req.getUsername()).ifPresent(user -> {
                    user.setLastLoginAt(java.time.Instant.now());
                    userRepository.save(user);

                    // Log successful login (audit logging will be added when AuditService is available)
                    logger.info("User logged in successfully: username={}, userId={}", user.getUsername(), user.getId());
                });
            } catch (Exception e) {
                logger.warn("Failed to update last login time for user={}: {}", req.getUsername(), e.getMessage());
            }

            Map<String, String> body = new HashMap<>();
            body.put("accessToken", accessToken);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            logger.error("[AuthController.login] unexpected error while creating tokens for user={}", req.getUsername(), e);
            return ResponseEntity.status(500).body(Map.of("error", "internal server error"));
        }
    }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, String>> refresh(@CookieValue(value = "refresh_token", required = false) String refresh,
                                                       HttpServletResponse resp) {
        String incoming = refresh == null ? "" : refresh;
        logger.trace("[AuthController.refresh] incoming refresh cookie='{}'", incoming);
        Optional<RefreshToken> stored = refreshTokenService.findByToken(incoming);
        if (stored.isEmpty()) {
            logger.trace("[AuthController.refresh] no matching refresh token found for='{}'", incoming);
            return ResponseEntity.status(401).build();
        }
        try {
            // rotate
            refreshTokenService.revoke(stored.get());
            RefreshToken rt = new RefreshToken();
            rt.setToken("refresh:" + System.currentTimeMillis());
            rt.setUsername(stored.get().getUsername());
            rt.setExpiresAt(java.time.Instant.now().plusSeconds(60 * 60 * 24 * 7));
            refreshTokenService.save(rt);

            // Build Set-Cookie header manually for refresh rotation
            resp.addHeader("Set-Cookie", buildRefreshCookieHeader(rt.getToken()));

            Map<String, String> body = new HashMap<>();
            // On refresh we do not have Authentication; rebuild roles from DB
            // Roles already have ROLE_ prefix in database, use them directly
            java.util.List<String> roles = userRepository.findByUsername(rt.getUsername())
                    .map(u -> new java.util.ArrayList<String>(u.getRoles()))
                    .orElse(new java.util.ArrayList<>());
            body.put("accessToken", jwtUtil.generateToken(rt.getUsername(), roles));
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            logger.error("[AuthController.refresh] unexpected error rotating refresh token for='{}'", incoming, e);
            return ResponseEntity.status(500).body(Map.of("error", "internal server error"));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(value = "refresh_token", required = false) String refresh, HttpServletResponse resp) {
        refreshTokenService.findByToken(refresh == null ? "" : refresh).ifPresent(rt -> {
            refreshTokenService.revoke(rt);
        });

        // Clear cookie by setting an expired Set-Cookie header
        resp.addHeader("Set-Cookie", "refresh_token=; Path=/; HttpOnly; Max-Age=0");
        return ResponseEntity.ok().build();
    }

    @org.springframework.web.bind.annotation.GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(org.springframework.security.core.Authentication authentication) {
        // server-side debug: log presence of Authorization header and refresh cookie (masked)
        try {
            var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                var req = attrs.getRequest();
                String authHeader = req.getHeader("Authorization");
                String authMasked = null;
                if (authHeader != null && !authHeader.isBlank()) {
                    authMasked = authHeader.length() > 12 ? authHeader.substring(0, 6) + "…" + authHeader.substring(authHeader.length() - 4) : authHeader;
                }
                String cookieMask = "(none)";
                var cookies = req.getCookies();
                if (cookies != null) {
                    for (var c : cookies) {
                        if ("refresh_token".equals(c.getName())) {
                            String v = c.getValue();
                            cookieMask = v == null ? "(empty)" : (v.length() > 12 ? v.substring(0, 6) + "…" + v.substring(v.length() - 4) : v);
                            break;
                        }
                    }
                }
                logger.debug("[AuthController.me] incoming Authorization={} refresh_cookie={}", authMasked, cookieMask);
            }
        } catch (Exception e) {
            logger.warn("[AuthController.me] failed to inspect incoming request headers", e);
        }

        if (authentication == null || !authentication.isAuthenticated()) return ResponseEntity.status(401).build();
        Map<String, Object> body = new HashMap<>();
        body.put("username", authentication.getName());

        // Always read roles from DB (source of truth) — not from JWT which may be stale
        java.util.List<String> cleanRoles = userRepository.findByUsername(authentication.getName())
                .map(u -> u.getRoles().stream()
                        .map(role -> role.startsWith("ROLE_") ? role.substring(5) : role)
                        .collect(Collectors.toList()))
                .orElseGet(() -> authentication.getAuthorities().stream()
                        .map(a -> a.getAuthority())
                        .map(role -> role.startsWith("ROLE_") ? role.substring(5) : role)
                        .collect(Collectors.toList()));

        body.put("roles", cleanRoles);
        body.put("authorities", cleanRoles);

        return ResponseEntity.ok(body);
    }
}
