package in.societyos.media.media.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code sos.media.*}: storage connection and upload/download limits. */
@ConfigurationProperties(prefix = "sos.media")
public class MediaProperties {

  private String bucket = "societyos-media";
  /** Endpoint the service uses (MinIO locally, empty for AWS S3). */
  private String endpoint = "";
  /** Endpoint written into presigned URLs (what phones and browsers can reach); defaults to endpoint. */
  private String publicEndpoint = "";
  private String region = "ap-south-1";
  private String accessKey = "";
  private String secretKey = "";
  private boolean pathStyle = true;
  private boolean createBucket = false;
  private Duration uploadTtl = Duration.ofMinutes(15);
  private Duration downloadTtl = Duration.ofMinutes(5);
  private Duration processPoll = Duration.ofSeconds(5);
  private String retentionCron = "0 45 2 * * *";
  private int thumbnailEdge = 320;
  private int batchSize = 50;

  public String getBucket() { return bucket; }
  public void setBucket(String v) { bucket = v; }
  public String getEndpoint() { return endpoint; }
  public void setEndpoint(String v) { endpoint = v; }
  public String getPublicEndpoint() { return publicEndpoint == null || publicEndpoint.isBlank() ? endpoint : publicEndpoint; }
  public void setPublicEndpoint(String v) { publicEndpoint = v; }
  public String getRegion() { return region; }
  public void setRegion(String v) { region = v; }
  public String getAccessKey() { return accessKey; }
  public void setAccessKey(String v) { accessKey = v; }
  public String getSecretKey() { return secretKey; }
  public void setSecretKey(String v) { secretKey = v; }
  public boolean isPathStyle() { return pathStyle; }
  public void setPathStyle(boolean v) { pathStyle = v; }
  public boolean isCreateBucket() { return createBucket; }
  public void setCreateBucket(boolean v) { createBucket = v; }
  public Duration getUploadTtl() { return uploadTtl; }
  public void setUploadTtl(Duration v) { uploadTtl = v; }
  public Duration getDownloadTtl() { return downloadTtl; }
  public void setDownloadTtl(Duration v) { downloadTtl = v; }
  public Duration getProcessPoll() { return processPoll; }
  public void setProcessPoll(Duration v) { processPoll = v; }
  public String getRetentionCron() { return retentionCron; }
  public void setRetentionCron(String v) { retentionCron = v; }
  public int getThumbnailEdge() { return thumbnailEdge; }
  public void setThumbnailEdge(int v) { thumbnailEdge = v; }
  public int getBatchSize() { return batchSize; }
  public void setBatchSize(int v) { batchSize = v; }
}
