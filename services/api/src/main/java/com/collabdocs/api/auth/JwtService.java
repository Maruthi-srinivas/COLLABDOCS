package com.collabdocs.api.auth;

import com.collabdocs.common.ApiException;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtService {
    public static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    private final RSASSASigner signer;
    private final RSASSAVerifier verifier;

    public JwtService(@Value("${collabdocs.jwt.private-key}") String privatePem,
                      @Value("${collabdocs.jwt.public-key}") String publicPem) {
        if (privatePem == null || privatePem.isBlank() || publicPem == null || publicPem.isBlank()) {
            throw new IllegalStateException("JWT_PRIVATE_KEY_PEM and JWT_PUBLIC_KEY_PEM are required");
        }
        try {
            RSAPrivateKey privateKey = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(decodePem(privatePem)));
            RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(decodePem(publicPem)));
            this.signer = new RSASSASigner(privateKey);
            this.verifier = new RSASSAVerifier(publicKey);
        } catch (Exception exception) {
            throw new IllegalStateException("JWT keys could not be parsed", exception);
        }
    }

    public String issue(UUID userId, UUID sessionId, boolean emailVerified) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("collabdocs")
                .audience("collabdocs-api")
                .subject(userId.toString())
                .jwtID(UUID.randomUUID().toString())
                .claim("sid", sessionId.toString())
                .claim("email_verified", emailVerified)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ACCESS_TTL)))
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(signer);
            return jwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public AccessClaims parse(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(verifier)) {
                throw new ApiException(401, "UNAUTHENTICATED", "Sign in again.");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (claims.getExpirationTime() == null || claims.getExpirationTime().before(new Date(System.currentTimeMillis() - 60_000))) {
                throw new ApiException(401, "UNAUTHENTICATED", "Sign in again.");
            }
            return new AccessClaims(UUID.fromString(claims.getSubject()), UUID.fromString(claims.getStringClaim("sid")),
                    Boolean.TRUE.equals(claims.getBooleanClaim("email_verified")));
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(401, "UNAUTHENTICATED", "Sign in again.");
        }
    }

    public record AccessClaims(UUID userId, UUID sessionId, boolean emailVerified) {}

    static byte[] decodePem(String pem) {
        String normalized = pem.replace("\\n", "\n");
        String body = normalized.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}
