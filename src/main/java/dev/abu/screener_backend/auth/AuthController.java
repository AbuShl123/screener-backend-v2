package dev.abu.screener_backend.auth;

import dev.abu.screener_backend.auth.dto.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RegisterResponse register(@RequestBody RegisterRequest req) {
        return authService.register(req);
    }

    /**
     * Called by the SPA verification page when the user clicks Confirm (no JWT). Consumes the
     * single-use token and returns the outcome as {@code success|expired|invalid} so the page can show
     * the result (with a resend affordance on failure). This is a POST — not the passive GET on the
     * email link — so link scanners that merely load the page never consume the token.
     */
    @PostMapping("/verify-email")
    public VerifyEmailResponse verifyEmail(@RequestBody VerifyEmailRequest req) {
        return new VerifyEmailResponse(authService.verifyEmail(req.token()).status());
    }

    /**
     * Re-sends a verification link. Always 202 with a generic body regardless of whether the email
     * exists / is already verified / is on cooldown — no account enumeration, no cooldown oracle.
     */
    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ResendVerificationResponse resendVerification(@RequestBody ResendVerificationRequest req) {
        authService.resendVerification(req.email());
        return new ResendVerificationResponse(
                "If an unverified account exists for that email, a new verification link has been sent.");
    }

    @PostMapping("/login")
    public AuthResponse login(@RequestBody LoginRequest req) {
        return authService.login(req);
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@RequestBody RefreshRequest req) {
        return authService.refresh(req.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        authService.logout(principal.userId());
    }

    @GetMapping("/me")
    public UserProfileResponse me(Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        return authService.me(principal.userId());
    }
}
