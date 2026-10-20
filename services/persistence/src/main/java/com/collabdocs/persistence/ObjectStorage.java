package com.collabdocs.persistence;

public interface ObjectStorage {
    void put(String key, byte[] body, String contentType);

    byte[] get(String key);

    void delete(String key);
}
