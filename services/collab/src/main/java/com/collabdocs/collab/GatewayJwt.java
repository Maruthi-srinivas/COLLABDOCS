package com.collabdocs.collab;

import com.collabdocs.common.ApiException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GatewayJwt {
    private final RSASSAVerifier verifier;

    public GatewayJwt(@Value("${collabdocs.jwt.public-key}") String publicPem) {
        if (publicPem == null || publicPem.isBlank()) {
            throw new IllegalStateException("JWT_PUBLIC_KEY_PEM is required");
        }
        try {
            RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(decodePem(publicPem)));
            this.verifier = new RSASSAVerifier(publicKey);
        } catch (Exception exception) {
            throw new IllegalStateException("JWT public key could not be parsed", exception);
        }
    }

    public Access parse(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(verifier)) {
                throw new ApiException(401, "UNAUTHENTICATED", "Sign in again.");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (claims.getExpirationTime() == null || claims.getExpirationTime().before(new Date(System.currentTimeMillis() - 60_000))) {
                throw new ApiException(401, "UNAUTHENTICATED", "Sign in again.");
            }
            return new Access(UUID.fromString(claims.getSubject()), UUID.fromString(claims.getStringClaim("sid")),
                    Boolean.TRUE.equals(claims.getBooleanClaim("email_verified")));
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(401, "UNAUTHENTICATED", "Sign in again.");
        }
    }

    public record Access(UUID userId, UUID sessionId, boolean emailVerified) {}

    private static byte[] decodePem(String pem) {
        String body = pem.replace("\\n", "\n")
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}
