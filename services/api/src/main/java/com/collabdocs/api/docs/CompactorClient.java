package com.collabdocs.api.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CompactorClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String token;

    public CompactorClient(ObjectMapper mapper,
                           @Value("${collabdocs.compactor-url:http://compactor:8083}") String baseUrl,
                           @Value("${collabdocs.internal-token}") String token) {
        this.mapper = mapper;
        this.baseUrl = baseUrl;
        this.token = token;
    }

    public JsonNode project(byte[] snapshot) throws Exception {
        return post("/internal/project", Map.of("snapshotBase64", Base64.getEncoder().encodeToString(snapshot), "updatesBase64", java.util.List.of()));
    }

    public JsonNode compact(String documentId, String requestId) throws Exception {
        return post("/internal/compact", Map.of("documentId", documentId, "requestId", requestId));
    }

    private JsonNode post(String path, Object body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("compactor " + response.statusCode());
        }
        return response.body() == null || response.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(response.body());
    }
}
