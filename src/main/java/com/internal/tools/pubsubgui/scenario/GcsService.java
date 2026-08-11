package com.internal.tools.pubsubgui.scenario;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.internal.tools.pubsubgui.scenario.config.ScenarioProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Uploads scenario batch inputs to the Perf inbound GCS path using Application Default Credentials.
 * Every upload is Perf-guarded: when {@code scenario.enforce-perf-only} is on, an upload for any other
 * environment is rejected before any GCS call is made. This is the only write this tool performs to
 * GCS and only ever targets the configured Perf inbound bucket/prefix.
 */
@Service
public class GcsService {

    private static final Logger log = LoggerFactory.getLogger(GcsService.class);

    private final ScenarioProperties props;
    private volatile Storage storage;

    public GcsService(ScenarioProperties props) {
        this.props = props;
    }

    private Storage storage() {
        Storage local = storage;
        if (local == null) {
            synchronized (this) {
                if (storage == null) {
                    storage = StorageOptions.getDefaultInstance().getService();
                }
                local = storage;
            }
        }
        return local;
    }

    /**
     * Upload {@code content} to {@code gs://bucket/objectName}. Returns the {@code gs://} URI.
     *
     * @throws IllegalStateException when the environment is not allowed for injection
     */
    public String upload(String env, String bucket, String objectName, byte[] content, String contentType) {
        if (!props.isAllowed(env)) {
            throw new IllegalStateException(
                    "Scenario injection is Perf-only. Refusing GCS upload for environment '" + env + "'.");
        }
        BlobId blobId = BlobId.of(bucket, objectName);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                .setContentType(contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType)
                .build();
        storage().create(blobInfo, content == null ? new byte[0] : content);
        String uri = "gs://" + bucket + "/" + objectName;
        log.info("scenario gcs | env={} uploaded {} bytes -> {}", env, content == null ? 0 : content.length, uri);
        return uri;
    }
}
