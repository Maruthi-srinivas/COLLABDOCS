package com.collabdocs.api.web;

import com.collabdocs.common.ApiException;
import com.collabdocs.common.Ids;
import com.collabdocs.persistence.ObjectStorage;
import jakarta.servlet.http.HttpServletRequest;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Map;
import javax.imageio.ImageIO;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class AvatarController {
    private final JdbcTemplate jdbc;
    private final ObjectStorage storage;

    public AvatarController(JdbcTemplate jdbc, ObjectStorage storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    @PostMapping(value = "/api/v1/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, String> upload(HttpServletRequest request, @RequestParam("file") MultipartFile file) throws Exception {
        var user = AuthController.current(request);
        byte[] bytes = file.getBytes();
        if (bytes.length > 2_000_000) {
            throw new ApiException(400, "UNSUPPORTED_IMAGE", "Image must be 2 MB or smaller.");
        }
        String type = sniff(bytes);
        if (("image/png".equals(type) || "image/jpeg".equals(type))) {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                throw new ApiException(400, "UNSUPPORTED_IMAGE", "Image could not be read.");
            }
            if (Math.max(image.getWidth(), image.getHeight()) > 2048) {
                throw new ApiException(400, "UNSUPPORTED_IMAGE", "Image is larger than 2048 pixels.");
            }
        }
        String key = "avatars/" + user.userId() + "/" + Ids.uuidV7();
        storage.put(key, bytes, type);
        String previous = jdbc.query("SELECT avatar_object_key FROM users WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, user.userId());
        jdbc.update("UPDATE users SET avatar_object_key = ?, updated_at = now() WHERE id = ?", key, user.userId());
        if (previous != null) {
            storage.delete(previous);
        }
        return Map.of("avatarUrl", "/api/v1/me/avatar");
    }

    @GetMapping("/api/v1/me/avatar")
    ResponseEntity<byte[]> download(HttpServletRequest request) {
        String key = jdbc.query("SELECT avatar_object_key FROM users WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, AuthController.current(request).userId());
        if (key == null) {
            throw new ApiException(404, "NOT_FOUND", "No profile image.");
        }
        byte[] body = storage.get(key);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(sniff(body))).body(body);
    }

    @DeleteMapping("/api/v1/me/avatar")
    ResponseEntity<Void> delete(HttpServletRequest request) {
        var user = AuthController.current(request);
        String key = jdbc.query("SELECT avatar_object_key FROM users WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, user.userId());
        jdbc.update("UPDATE users SET avatar_object_key = NULL, updated_at = now() WHERE id = ?", user.userId());
        if (key != null) {
            storage.delete(key);
        }
        return ResponseEntity.noContent().build();
    }

    static String sniff(byte[] bytes) {
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47) {
            return "image/png";
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        throw new ApiException(400, "UNSUPPORTED_IMAGE", "Use a PNG, JPEG, or WebP image.");
    }
}
