package in.societyos.media.media.application;

import java.util.Optional;

/** Makes a JPEG thumbnail of an image; empty when the bytes are not a decodable image. */
public interface Thumbnailer {

  record Thumbnail(byte[] jpeg, int width, int height, int sourceWidth, int sourceHeight) {}

  Optional<Thumbnail> thumbnail(byte[] image, int maxEdge);
}
