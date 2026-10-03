package in.societyos.media.media.application;

/**
 * Virus scan behind an interface. The shipped implementation is a stub (EICAR signature check);
 * production wires ClamAV (clamd INSTREAM) here without touching the use cases.
 */
public interface VirusScanner {

  enum Verdict {
    CLEAN,
    INFECTED
  }

  Verdict scan(byte[] content, String contentType);
}
