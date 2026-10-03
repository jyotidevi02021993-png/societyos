package in.societyos.media.media.infrastructure;

import in.societyos.media.media.application.Thumbnailer;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;
import javax.imageio.ImageIO;

/**
 * JDK ImageIO thumbnails (JPEG/PNG; WebP only with a plugin). Re-encoding drops EXIF from the
 * thumbnail.
 */
public class ImageIoThumbnailer implements Thumbnailer {

  @Override
  public Optional<Thumbnail> thumbnail(byte[] image, int maxEdge) {
    BufferedImage src;
    try {
      src = ImageIO.read(new ByteArrayInputStream(image));
    } catch (IOException | RuntimeException e) {
      return Optional.empty();
    }
    if (src == null) {
      return Optional.empty();
    }
    int w = src.getWidth();
    int h = src.getHeight();
    double scale = Math.min(1.0, (double) maxEdge / Math.max(w, h));
    int tw = Math.max(1, (int) Math.round(w * scale));
    int th = Math.max(1, (int) Math.round(h * scale));
    BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = out.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      g.setColor(Color.WHITE);
      g.fillRect(0, 0, tw, th);
      g.drawImage(src, 0, 0, tw, th, null);
    } finally {
      g.dispose();
    }
    try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
      if (!ImageIO.write(out, "jpg", bytes)) {
        return Optional.empty();
      }
      return Optional.of(new Thumbnail(bytes.toByteArray(), tw, th, w, h));
    } catch (IOException e) {
      return Optional.empty();
    }
  }
}
