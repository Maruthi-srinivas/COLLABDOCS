package com.collabdocs.api;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.sql.Connection;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/health/live")
    public Map<String, String> live() {
        return Map.of("status", "up");
    }

    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        String postgres = checkPostgres();
        String redis = checkRedis();
        boolean up = "up".equals(postgres) && "up".equals(redis);
        return ResponseEntity.status(up ? 200 : 503).body(Map.of(
                "status", up ? "up" : "down",
                "checks", Map.of("postgres", postgres, "redis", redis)));
    }

    private String checkPostgres() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2) ? "up" : "down";
        } catch (Exception exception) {
            return "down";
        }
    }

    private String checkRedis() {
        String url = System.getenv().getOrDefault("REDIS_URL", "redis://redis:6379");
        try {
            URI uri = URI.create(url);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 1000);
                socket.setSoTimeout(1000);
                socket.getOutputStream().write("PING\r\n".getBytes());
                byte[] buffer = new byte[32];
                int read = socket.getInputStream().read(buffer);
                if (read > 0 && new String(buffer, 0, read).contains("PONG")) {
                    return "up";
                }
            }
        } catch (Exception exception) {
            return "down";
        }
        return "down";
    }
}
