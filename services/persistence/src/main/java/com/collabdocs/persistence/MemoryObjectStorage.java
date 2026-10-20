package com.collabdocs.persistence;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("test")
public class MemoryObjectStorage implements ObjectStorage {
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public void put(String key, byte[] body, String contentType) {
        objects.put(key, body);
    }

    @Override
    public byte[] get(String key) {
        byte[] body = objects.get(key);
        if (body == null) {
            throw new IllegalStateException("Missing object");
        }
        return body;
    }

    @Override
    public void delete(String key) {
        objects.remove(key);
    }
}
