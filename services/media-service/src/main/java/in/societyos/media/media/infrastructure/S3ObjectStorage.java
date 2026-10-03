package in.societyos.media.media.infrastructure;

import in.societyos.media.media.application.MediaProperties;
import in.societyos.media.media.application.ObjectStorage;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/** AWS SDK v2 against S3 or MinIO (path-style, endpoint override). */
public class S3ObjectStorage implements ObjectStorage, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(S3ObjectStorage.class);

  private final S3Client s3;
  private final S3Presigner presigner;
  private final String bucket;

  public S3ObjectStorage(MediaProperties props) {
    this.bucket = props.getBucket();
    AwsCredentialsProvider creds = props.getAccessKey().isBlank()
        ? DefaultCredentialsProvider.builder().build()
        : StaticCredentialsProvider.create(AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey()));
    Region region = Region.of(props.getRegion());
    var clientBuilder = S3Client.builder()
        .region(region)
        .credentialsProvider(creds)
        .forcePathStyle(props.isPathStyle())
        // presigned PUTs from phones carry no SDK checksum headers
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
    if (!props.getEndpoint().isBlank()) {
      clientBuilder.endpointOverride(URI.create(props.getEndpoint()));
    }
    this.s3 = clientBuilder.build();
    var presignerBuilder = S3Presigner.builder()
        .region(region)
        .credentialsProvider(creds)
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(props.isPathStyle()).build());
    if (!props.getPublicEndpoint().isBlank()) {
      presignerBuilder.endpointOverride(URI.create(props.getPublicEndpoint()));
    }
    this.presigner = presignerBuilder.build();
  }

  /** Local development and tests only: creates the bucket when missing. */
  public void ensureBucket() {
    try {
      s3.headBucket(b -> b.bucket(bucket));
    } catch (S3Exception e) {
      if (e.statusCode() != 404) {
        throw e;
      }
      s3.createBucket(b -> b.bucket(bucket));
      log.info("Created bucket {}", bucket);
    }
  }

  @Override
  public Presigned presignPut(String key, String contentType, long sizeBytes, Duration ttl) {
    PutObjectRequest put = PutObjectRequest.builder()
        .bucket(bucket).key(key).contentType(contentType).contentLength(sizeBytes).build();
    PresignedPutObjectRequest p = presigner.presignPutObject(r -> r.signatureDuration(ttl).putObjectRequest(put));
    Map<String, String> headers = new LinkedHashMap<>();
    p.signedHeaders().forEach((name, values) -> {
      if (!"host".equalsIgnoreCase(name)) {
        headers.put(name, String.join(",", values));
      }
    });
    if (headers.keySet().stream().noneMatch("content-type"::equalsIgnoreCase)) {
      headers.put("Content-Type", contentType);
    }
    return new Presigned(toUri(p.url()), "PUT", headers, p.expiration());
  }

  @Override
  public Presigned presignGet(String key, String contentType, String downloadName, Duration ttl) {
    var get = GetObjectRequest.builder().bucket(bucket).key(key).responseContentType(contentType);
    if (downloadName != null) {
      get.responseContentDisposition("inline; filename=\"" + downloadName.replace("\"", "") + "\"");
    }
    GetObjectRequest request = get.build();
    PresignedGetObjectRequest p = presigner.presignGetObject(r -> r.signatureDuration(ttl).getObjectRequest(request));
    return new Presigned(toUri(p.url()), "GET", Map.of(), p.expiration());
  }

  @Override
  public Optional<ObjectInfo> head(String key) {
    try {
      HeadObjectResponse h = s3.headObject(b -> b.bucket(bucket).key(key));
      return Optional.of(new ObjectInfo(h.contentLength(), h.contentType()));
    } catch (NoSuchKeyException e) {
      return Optional.empty();
    } catch (S3Exception e) {
      if (e.statusCode() == 404) {
        return Optional.empty();
      }
      throw e;
    }
  }

  @Override
  public byte[] read(String key, long maxBytes) {
    ObjectInfo info = head(key).orElseThrow(() -> new IllegalStateException("Object missing: " + key));
    if (info.sizeBytes() > maxBytes) {
      throw new IllegalStateException("Object too large to process: " + info.sizeBytes());
    }
    return s3.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asByteArray();
  }

  @Override
  public void write(String key, byte[] bytes, String contentType) {
    s3.putObject(b -> b.bucket(bucket).key(key).contentType(contentType), RequestBody.fromBytes(bytes));
  }

  @Override
  public void delete(String key) {
    s3.deleteObject(b -> b.bucket(bucket).key(key));
  }

  @Override
  public void close() {
    presigner.close();
    s3.close();
  }

  private static URI toUri(URL url) {
    try {
      return url.toURI();
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }
}
