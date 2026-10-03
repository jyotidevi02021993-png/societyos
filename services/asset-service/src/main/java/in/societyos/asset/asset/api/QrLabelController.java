package in.societyos.asset.asset.api;

import in.societyos.asset.asset.application.AssetService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * QR label sheets. Returns the label data (code, name, location, QR payload); the web app lays
 * it out and prints it. Printing is then confirmed so reprints can be tracked.
 */
@RestController
@RequestMapping("/v1/qr-labels")
public class QrLabelController {

  private final AssetService assets;

  public QrLabelController(AssetService assets) {
    this.assets = assets;
  }

  public record LabelRequest(List<UUID> assetIds) {}

  public record PrintedRequest(@NotEmpty List<UUID> assetIds) {}

  public record LabelResponse(UUID assetId, String assetCode, String name, String category, String locationName,
      String qrToken, String qrPayload) {
    static LabelResponse from(AssetService.QrLabel l) {
      return new LabelResponse(l.assetId(), l.assetCode(), l.name(), l.category(), l.locationName(), l.qrToken(),
          l.qrPayload());
    }
  }

  /** Label data for the given assets, or every labelled asset when the list is empty. */
  @PostMapping
  @PreAuthorize("@perm.has('asset:manage')")
  public List<LabelResponse> labels(@RequestBody(required = false) LabelRequest request) {
    return assets.labels(request == null ? List.of() : request.assetIds()).stream().map(LabelResponse::from).toList();
  }

  @PostMapping("/printed")
  @PreAuthorize("@perm.has('asset:manage')")
  public Map<String, Integer> printed(@Valid @RequestBody PrintedRequest request) {
    return Map.of("marked", assets.markPrinted(request.assetIds()));
  }
}
