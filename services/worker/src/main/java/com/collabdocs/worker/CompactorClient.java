package com.collabdocs.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
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
                           @Value("${collabdocs.compactor-url}") String baseUrl,
                           @Value("${collabdocs.internal-token}") String token) {
        this.mapper = mapper;
        this.baseUrl = baseUrl;
        this.token = token;
    }

    public JsonNode project(byte[] snapshot, List<byte[]> updates) throws Exception {
        List<String> encoded = updates.stream().map(bytes -> Base64.getEncoder().encodeToString(bytes)).toList();
        return post("/internal/project", Map.of(
                "snapshotBase64", Base64.getEncoder().encodeToString(snapshot),
                "updatesBase64", encoded));
    }

    public byte[] encode(JsonNode canonical) throws Exception {
        JsonNode response = post("/internal/encode", Map.of("canonicalJson", canonical));
        return Base64.getDecoder().decode(response.path("snapshotBase64").asText());
    }

    private JsonNode post(String path, Object body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("compactor " + response.statusCode());
        }
        return mapper.readTree(response.body());
    }
}
