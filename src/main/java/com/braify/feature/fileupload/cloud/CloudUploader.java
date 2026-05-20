package com.braify.feature.fileupload.cloud;

/**
 * Strategy interface for cloud storage operations.
 *
 * <p>Implementations must be stateless — all provider-specific configuration
 * (credentials, region, bucket name) is supplied via the request objects
 * so that one instance can serve multiple organisations with different credentials.
 *
 * <p>Registered implementations:
 * <ul>
 *   <li>{@link AwsS3Uploader} — AWS S3</li>
 *   <li>{@link AzureBlobUploader} — Azure Blob Storage</li>
 *   <li>{@link GcpStorageUploader} — Google Cloud Storage</li>
 * </ul>
 *
 * @see CloudUploaderFactory
 */
public interface CloudUploader {

    /**
     * Uploads a file to cloud storage.
     *
     * @param request upload parameters including credentials and data
     * @return result containing the final storage key and optional public URL
     * @throws RuntimeException if the upload fails
     */
    CloudUploadResult upload(CloudUploadRequest request);

    /**
     * Generates a time-limited pre-signed URL for downloading a stored object.
     *
     * @param request download parameters including credentials and expiration
     * @return a pre-signed URL string valid for the requested duration
     * @throws RuntimeException if URL generation fails
     */
    String generatePresignedUrl(CloudDownloadRequest request);

    /**
     * Permanently deletes an object from cloud storage.
     *
     * @param request identifies the object to delete
     * @throws RuntimeException if deletion fails
     */
    void delete(CloudDownloadRequest request);
}
