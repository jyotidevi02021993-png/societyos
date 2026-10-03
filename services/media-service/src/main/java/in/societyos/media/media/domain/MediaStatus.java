package in.societyos.media.media.domain;

/** PENDING (URL issued) → UPLOADED (object verified) → READY | REJECTED; EXPIRED and DELETED are terminal. */
public enum MediaStatus {
  PENDING,
  UPLOADED,
  READY,
  REJECTED,
  EXPIRED,
  DELETED
}
