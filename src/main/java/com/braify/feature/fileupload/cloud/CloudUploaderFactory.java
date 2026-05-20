package com.braify.feature.fileupload.cloud;

import com.braify.feature.cloudconfig.model.OrgCloudConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Returns the correct {@link CloudUploader} implementation for a given
 * {@link OrgCloudConfig.CloudProvider}.
 *
 * <p>All uploader beans are injected via constructor so Spring manages their lifecycle.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CloudUploaderFactory {

    private final AwsS3Uploader     awsS3Uploader;
    private final AzureBlobUploader azureBlobUploader;
    private final GcpStorageUploader gcpStorageUploader;

    /**
     * Returns the uploader for the given provider.
     *
     * @param provider cloud provider enum value
     * @return the matching {@link CloudUploader}
     * @throws IllegalArgumentException if {@code provider} is {@code null} or unsupported
     */
    public CloudUploader get(OrgCloudConfig.CloudProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("Cloud provider must not be null");
        }
        return switch (provider) {
            case AWS   -> awsS3Uploader;
            case AZURE -> azureBlobUploader;
            case GCP   -> gcpStorageUploader;
        };
    }
}
