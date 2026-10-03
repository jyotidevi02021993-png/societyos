package in.societyos.media.media;

import static org.assertj.core.api.Assertions.assertThat;

import in.societyos.media.media.application.VirusScanner;
import in.societyos.media.media.infrastructure.ImageIoThumbnailer;
import in.societyos.media.media.infrastructure.StubVirusScanner;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ThumbnailAndScanTest {

  static final String EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

  static byte[] png(int w, int h) throws Exception {
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      ImageIO.write(img, "png", out);
      return out.toByteArray();
    }
  }

  @Test
  void thumbnailKeepsAspectWithinEdge() throws Exception {
    var t = new ImageIoThumbnailer().thumbnail(png(1200, 600), 320).orElseThrow();
    assertThat(t.width()).isEqualTo(320);
    assertThat(t.height()).isEqualTo(160);
    assertThat(t.sourceWidth()).isEqualTo(1200);
    assertThat(ImageIO.read(new ByteArrayInputStream(t.jpeg()))).isNotNull();
    assertThat(new ImageIoThumbnailer().thumbnail("not an image".getBytes(StandardCharsets.UTF_8), 320)).isEmpty();
  }

  @Test
  void stubScannerFlagsEicar() {
    var scanner = new StubVirusScanner();
    assertThat(scanner.scan(EICAR.getBytes(StandardCharsets.US_ASCII), "image/png"))
        .isEqualTo(VirusScanner.Verdict.INFECTED);
    assertThat(scanner.scan(new byte[] {1, 2, 3}, "image/png")).isEqualTo(VirusScanner.Verdict.CLEAN);
  }
}
