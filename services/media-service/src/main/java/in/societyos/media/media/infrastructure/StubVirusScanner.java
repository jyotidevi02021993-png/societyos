package in.societyos.media.media.infrastructure;

import in.societyos.media.media.application.VirusScanner;
import java.nio.charset.StandardCharsets;

/**
 * Development stand-in for ClamAV: flags the EICAR test signature, passes everything else.
 * Replace with a clamd INSTREAM client in production (same interface).
 */
public class StubVirusScanner implements VirusScanner {

  static final byte[] EICAR_MARKER = "EICAR-STANDARD-ANTIVIRUS-TEST-FILE".getBytes(StandardCharsets.US_ASCII);

  @Override
  public Verdict scan(byte[] content, String contentType) {
    return indexOf(content, EICAR_MARKER) >= 0 ? Verdict.INFECTED : Verdict.CLEAN;
  }

  static int indexOf(byte[] haystack, byte[] needle) {
    for (int i = 0; i <= haystack.length - needle.length; i++) {
      int j = 0;
      while (j < needle.length && haystack[i + j] == needle[j]) {
        j++;
      }
      if (j == needle.length) {
        return i;
      }
    }
    return -1;
  }
}
