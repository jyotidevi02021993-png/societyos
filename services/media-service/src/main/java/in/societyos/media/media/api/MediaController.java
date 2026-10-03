package in.societyos.media.media.api;

import in.societyos.media.media.application.MediaService;
import in.societyos.media.media.application.MediaService.UploadRequest;
import in.societyos.media.media.application.MediaViews.DownloadLink;
import in.societyos.media.media.application.MediaViews.MediaView;
import in.societyos.media.media.application.MediaViews.UploadTicket;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Any signed-in member of the active society may upload; reads are limited to the society (RLS)
 * and, for private purposes, to the uploader or {@code media:manage}.
 */
@RestController
@RequestMapping("/v1")
@PreAuthorize("isAuthenticated()")
public class MediaController {

  public record UploadBody(@NotBlank String purpose, @NotBlank String contentType, @Positive long sizeBytes,
      @Size(max = 255) String fileName) {}

  private final MediaService media;

  public MediaController(MediaService media) {
    this.media = media;
  }

  @PostMapping("/uploads")
  @ResponseStatus(HttpStatus.CREATED)
  public UploadTicket upload(@Valid @RequestBody UploadBody body) {
    return media.requestUpload(new UploadRequest(body.purpose(), body.contentType(), body.sizeBytes(), body.fileName()));
  }

  @PostMapping("/uploads/{id}/complete")
  public MediaView complete(@PathVariable UUID id) {
    return media.complete(id);
  }

  @GetMapping("/media/{id}")
  public MediaView get(@PathVariable UUID id) {
    return media.get(id);
  }

  @GetMapping("/media/{id}/download-url")
  public DownloadLink downloadUrl(@PathVariable UUID id, @RequestParam(required = false) String variant) {
    return media.downloadLink(id, variant);
  }

  /** 302 to the signed URL, for {@code <img src>} style use. */
  @GetMapping("/media/{id}/content")
  public ResponseEntity<Void> content(@PathVariable UUID id, @RequestParam(required = false) String variant) {
    DownloadLink link = media.downloadLink(id, variant);
    return ResponseEntity.status(HttpStatus.FOUND).location(link.url()).header("Cache-Control", "no-store").build();
  }

  @DeleteMapping("/media/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) {
    media.delete(id);
  }
}
