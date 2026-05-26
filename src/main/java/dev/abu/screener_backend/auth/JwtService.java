package dev.abu.screener_backend.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.abu.screener_backend.config.JwtProperties;
import dev.abu.screener_backend.user.User;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final OctetSequenceKey signingKey;
    private final Duration accessTokenExpiry;

    public JwtService(JwtProperties props) {
        byte[] secretBytes = Base64.getDecoder().decode(props.secret());
        this.signingKey = new OctetSequenceKey.Builder(secretBytes).build();
        this.accessTokenExpiry = props.accessTokenExpiry();
    }

    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(accessTokenExpiry)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(new MACSigner(signingKey));
        } catch (JOSEException e) {
            throw new RuntimeException("JWT signing failed", e);
        }
        return jwt.serialize();
    }

    public String generateRawRefreshToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    public AuthenticatedUser validateAndExtract(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(new MACVerifier(signingKey))) return null;
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (claims.getExpirationTime().before(new Date())) return null;
            return new AuthenticatedUser(
                    UUID.fromString(claims.getSubject()),
                    (String) claims.getClaim("email"),
                    (String) claims.getClaim("role")
            );
        } catch (Exception e) {
            return null;
        }
    }

    public long accessTokenExpirySeconds() {
        return accessTokenExpiry.getSeconds();
    }
}
