package com.collabdocs.api.auth;

import com.collabdocs.common.ApiException;
import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class PasswordPolicy {
    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
    private final Set<String> denylist = new HashSet<>();

    @PostConstruct
    void loadDenylist() throws Exception {
        try (var reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("denylist/passwords.txt").getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    denylist.add(line.trim().toLowerCase());
                }
            }
        }
    }

    public void check(String password) {
        if (password == null || password.length() < 12 || password.length() > 128) {
            throw new ApiException(400, "VALIDATION_FAILED", "Password must be 12 to 128 characters.",
                    List.of(new ApiException.FieldDetail("password", "length")));
        }
        boolean letter = password.codePoints().anyMatch(Character::isLetter);
        boolean number = password.codePoints().anyMatch(Character::isDigit);
        if (!letter || !number || denylist.contains(password.toLowerCase())) {
            throw new ApiException(400, "VALIDATION_FAILED", "Choose a stronger password.",
                    List.of(new ApiException.FieldDetail("password", "weak")));
        }
    }

    public String hash(String password) {
        return encoder.encode(password);
    }

    public boolean matches(String password, String hash) {
        return hash != null && encoder.matches(password, hash);
    }
}
