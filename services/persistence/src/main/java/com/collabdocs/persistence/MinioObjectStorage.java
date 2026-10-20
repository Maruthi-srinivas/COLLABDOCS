package com.collabdocs.persistence;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.ByteArrayInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class MinioObjectStorage implements ObjectStorage, InitializingBean {
    private static final Logger log = LoggerFactory.getLogger(MinioObjectStorage.class);
    private final MinioClient client;
    private final String bucket;

    public MinioObjectStorage(@Value("${collabdocs.minio.endpoint}") String endpoint,
                              @Value("${collabdocs.minio.access-key}") String accessKey,
                              @Value("${collabdocs.minio.secret-key}") String secretKey,
                              @Value("${collabdocs.minio.bucket}") String bucket) {
        this.client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
        this.bucket = bucket;
    }

    @Override
    public void afterPropertiesSet() {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception exception) {
            log.warn("Object storage bucket is not ready yet");
        }
    }

    @Override
    public void put(String key, byte[] body, String contentType) {
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket).object(key).contentType(contentType)
                    .stream(new ByteArrayInputStream(body), body.length, -1).build());
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to store object", exception);
        }
    }

    @Override
    public byte[] get(String key) {
        try (var stream = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            return stream.readAllBytes();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to read object", exception);
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception exception) {
            log.warn("Unable to delete object {}", key);
        }
    }
}
