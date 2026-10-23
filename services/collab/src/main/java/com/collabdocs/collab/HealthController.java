package com.collabdocs.collab;

import com.collabdocs.common.AppMetrics;
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
        AppMetrics.INSTANCE.gauge("db_up", "up".equals(postgres) ? 1 : 0, "service", "collab");
        AppMetrics.INSTANCE.gauge("redis_up", "up".equals(redis) ? 1 : 0, "service", "collab");
        if (!up) AppMetrics.INSTANCE.increment("ready_check_failures_total", "service", "collab");
        return ResponseEntity.status(up ? 200 : 503).body(Map.of(
                "status", up ? "up" : "down",
                "checks", Map.of("postgres", postgres, "redis", redis)));
    }

    @GetMapping(value = "/metrics", produces = "text/plain")
    public String metrics() {
        String postgres = checkPostgres();
        String redis = checkRedis();
        AppMetrics.INSTANCE.gauge("db_up", "up".equals(postgres) ? 1 : 0, "service", "collab");
        AppMetrics.INSTANCE.gauge("redis_up", "up".equals(redis) ? 1 : 0, "service", "collab");
        AppMetrics.INSTANCE.gauge("collab_sockets_open", Gateway.openSockets());
        return AppMetrics.INSTANCE.render();
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
