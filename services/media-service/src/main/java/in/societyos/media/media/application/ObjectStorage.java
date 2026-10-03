package in.societyos.media.media.application;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** S3-compatible object store (S3 in AWS, MinIO locally). Implemented in infrastructure. */
public interface ObjectStorage {

  record Presigned(URI url, String method, Map<String, String> headers, Instant expiresAt) {}

  record ObjectInfo(long sizeBytes, String contentType) {}

  /** A URL that accepts exactly one PUT of {@code contentType} and {@code sizeBytes} to {@code key}. */
  Presigned presignPut(String key, String contentType, long sizeBytes, Duration ttl);

  /** A short-lived GET URL; {@code downloadName} sets Content-Disposition when not null. */
  Presigned presignGet(String key, String contentType, String downloadName, Duration ttl);

  Optional<ObjectInfo> head(String key);

  byte[] read(String key, long maxBytes);

  void write(String key, byte[] bytes, String contentType);

  void delete(String key);
}
