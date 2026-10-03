package in.societyos.media.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.societyos.media.media.domain.MediaFile;
import in.societyos.media.media.domain.MediaPurpose;
import in.societyos.media.media.domain.MediaRuleException;
import in.societyos.media.media.domain.MediaStatus;
import in.societyos.media.platform.core.UuidV7;
import in.societyos.media.platform.core.tenant.TenantContext;
import in.societyos.media.platform.test.TestTenants;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MediaRulesTest {

  static final Instant NOW = Instant.parse("2026-09-29T06:00:00Z");
  final UUID society = UuidV7.next();
  final UUID uploader = UuidV7.next();

  @BeforeEach
  void bind() {
    TenantContext.set(TestTenants.user(uploader, society, "RESIDENT_OWNER"));
  }

  @AfterEach
  void clear() {
    TenantContext.clear();
  }

  MediaFile pending(MediaPurpose purpose, String type, long size) {
    return MediaFile.requestUpload(UuidV7.next(), society, purpose, type, size, "C:\\photos\\gate cam#1.jpg", NOW,
        Duration.ofMinutes(15));
  }

  @Test
  void purposeLimitsContentTypeAndSize() {
    assertThat(MediaPurpose.VISITOR_PHOTO.violation("image/JPEG; charset=binary", 1000)).isNull();
    assertThat(MediaPurpose.VISITOR_PHOTO.violation("application/pdf", 1000)).isEqualTo("CONTENT_TYPE_NOT_ALLOWED");
    assertThat(MediaPurpose.VISITOR_PHOTO.violation("image/png", 6L * 1024 * 1024)).isEqualTo("FILE_TOO_LARGE");
    assertThat(MediaPurpose.RECEIPT_PDF.violation("application/pdf", 0)).isEqualTo("EMPTY_FILE");
    assertThat(MediaPurpose.THUMBNAIL.violation("image/jpeg", 10)).isEqualTo("PURPOSE_NOT_UPLOADABLE");
    assertThatThrownBy(() -> pending(MediaPurpose.AVATAR, "image/png", 3L * 1024 * 1024))
        .isInstanceOf(MediaRuleException.class).hasMessageContaining("FILE_TOO_LARGE");
  }

  @Test
  void keyIsSocietyPrefixedAndFileNameSanitised() {
    MediaFile m = pending(MediaPurpose.VISITOR_PHOTO, "image/jpeg", 1000);
    assertThat(m.getObjectKey()).startsWith(society + "/visitor_photo/2026/09/").endsWith(m.getId().toString());
    assertThat(m.getFileName()).isEqualTo("gate cam_1.jpg");
    assertThat(m.getOwnerService()).isEqualTo("security");
    assertThat(m.getStatus()).isEqualTo(MediaStatus.PENDING);
  }

  @Test
  void confirmChecksActualObjectAgainstDeclaration() {
    MediaFile ok = pending(MediaPurpose.VISITOR_PHOTO, "image/jpeg", 1000);
    ok.confirmUpload(900, "image/jpeg", NOW.plusSeconds(60), Duration.ofDays(180));
    assertThat(ok.getStatus()).isEqualTo(MediaStatus.UPLOADED);
    assertThat(ok.getRetainUntil()).isEqualTo(NOW.plusSeconds(60).plus(Duration.ofDays(180)));

    MediaFile bigger = pending(MediaPurpose.VISITOR_PHOTO, "image/jpeg", 1000);
    bigger.confirmUpload(1001, "image/jpeg", NOW, null);
    assertThat(bigger.getStatus()).isEqualTo(MediaStatus.REJECTED);
    assertThat(bigger.getRejectReason()).isEqualTo("SIZE_MISMATCH");

    MediaFile wrongType = pending(MediaPurpose.VISITOR_PHOTO, "image/jpeg", 1000);
    wrongType.confirmUpload(1000, "image/png", NOW, null);
    assertThat(wrongType.getRejectReason()).isEqualTo("CONTENT_TYPE_MISMATCH");

    MediaFile late = pending(MediaPurpose.VISITOR_PHOTO, "image/jpeg", 1000);
    assertThatThrownBy(() -> late.confirmUpload(1000, "image/jpeg", NOW.plus(Duration.ofMinutes(16)), null))
        .isInstanceOf(MediaRuleException.class).hasMessageContaining("expired");
    assertThat(late.isUploadWindowOver(NOW.plus(Duration.ofMinutes(16)))).isTrue();
  }

  @Test
  void scanOutcomeDrivesState() {
    MediaFile m = pending(MediaPurpose.COMPLAINT_PHOTO, "image/png", 1000);
    assertThatThrownBy(() -> m.markClean(1, 1, null, NOW)).isInstanceOf(MediaRuleException.class);
    m.confirmUpload(1000, "image/png", NOW, null);
    m.markInfected(NOW);
    assertThat(m.getStatus()).isEqualTo(MediaStatus.REJECTED);
    assertThat(m.getRejectReason()).isEqualTo("VIRUS_DETECTED");
  }

  @Test
  void privatePurposesAreReadableByUploaderOrManagerOnly() {
    MediaFile kyc = pending(MediaPurpose.KYC_DOCUMENT, "application/pdf", 1000);
    // createdBy is set on persist, so before that only a manager qualifies
    assertThat(kyc.readableBy(uploader, false)).isFalse();
    assertThat(kyc.readableBy(UuidV7.next(), true)).isTrue();
    MediaFile photo = pending(MediaPurpose.VISITOR_PHOTO, "image/jpeg", 1000);
    assertThat(photo.readableBy(UuidV7.next(), false)).isTrue();
    assertThat(photo.deletableBy(UuidV7.next(), false)).isFalse();
    assertThat(photo.deletableBy(UuidV7.next(), true)).isTrue();
  }

  @Test
  void retentionDueOnlyWhenReadyAndPast() {
    MediaFile m = pending(MediaPurpose.VISITOR_PHOTO, "image/jpeg", 1000);
    m.confirmUpload(1000, "image/jpeg", NOW, Duration.ofDays(1));
    m.markClean(10, 10, null, NOW);
    assertThat(m.isRetentionDue(NOW.plus(Duration.ofHours(23)))).isFalse();
    assertThat(m.isRetentionDue(NOW.plus(Duration.ofDays(2)))).isTrue();
    m.delete(NOW);
    assertThat(m.isRetentionDue(NOW.plus(Duration.ofDays(2)))).isFalse();
  }
}
