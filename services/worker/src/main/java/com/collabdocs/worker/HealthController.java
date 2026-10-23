package com.collabdocs.worker;

import com.collabdocs.common.AppMetrics;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.sql.Connection;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public HealthController(DataSource dataSource, JdbcTemplate jdbc) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
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
        AppMetrics.INSTANCE.gauge("db_up", "up".equals(postgres) ? 1 : 0, "service", "worker");
        AppMetrics.INSTANCE.gauge("redis_up", "up".equals(redis) ? 1 : 0, "service", "worker");
        if (!up) AppMetrics.INSTANCE.increment("ready_check_failures_total", "service", "worker");
        return ResponseEntity.status(up ? 200 : 503).body(Map.of(
                "status", up ? "up" : "down",
                "checks", Map.of("postgres", postgres, "redis", redis)));
    }

    @GetMapping(value = "/metrics", produces = "text/plain")
    public String metrics() {
        String postgres = checkPostgres();
        String redis = checkRedis();
        AppMetrics.INSTANCE.gauge("db_up", "up".equals(postgres) ? 1 : 0, "service", "worker");
        AppMetrics.INSTANCE.gauge("redis_up", "up".equals(redis) ? 1 : 0, "service", "worker");
        jdbc.query("SELECT status, COUNT(*) AS n FROM export_jobs GROUP BY status", (rs, row) -> {
            AppMetrics.INSTANCE.gauge("export_jobs", rs.getLong("n"), "status", rs.getString("status"));
            return null;
        });
        Number age = jdbc.queryForObject(
                """
                SELECT COALESCE(EXTRACT(EPOCH FROM (now() - MIN(created_at))), 0)
                FROM export_jobs WHERE status IN ('queued', 'running')
                """,
                Number.class);
        AppMetrics.INSTANCE.gauge("export_queue_age_seconds", age == null ? 0 : age.longValue());
        Number lag = jdbc.queryForObject(
                """
                SELECT COALESCE((
                  SELECT MAX(e.last_seq - COALESCE(s.through_seq, 0))
                  FROM documents d
                  JOIN document_epochs e ON e.document_id = d.id AND e.epoch = d.epoch
                  LEFT JOIN LATERAL (
                    SELECT through_seq FROM document_snapshots
                    WHERE document_id = d.id AND epoch = d.epoch AND state IS NOT NULL
                    ORDER BY through_seq DESC LIMIT 1
                  ) s ON true
                ), 0)
                """,
                Number.class);
        AppMetrics.INSTANCE.gauge("snapshot_lag_updates_max", lag == null ? 0 : lag.longValue());
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
