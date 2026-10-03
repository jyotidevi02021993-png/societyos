package in.societyos.society.imports.api;

import in.societyos.society.imports.application.ImportService;
import in.societyos.society.imports.application.ImportService.JobView;
import in.societyos.society.imports.domain.ImportReport;
import in.societyos.society.platform.core.error.ProblemException;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Excel bulk onboarding: upload, then poll the job until it has a final status. */
@RestController
@RequestMapping("/v1/imports")
class ImportController {

  private static final MediaType XLSX =
      MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

  private final ImportService imports;

  ImportController(ImportService imports) {
    this.imports = imports;
  }

  record JobResponse(UUID id, String status, boolean dryRun, String fileName, ImportReport report,
      Instant createdAt, Instant finishedAt) {
    static JobResponse from(JobView v) {
      return new JobResponse(v.job().getId(), v.job().getStatus().name(), v.job().isDryRun(), v.job().getFileName(),
          v.report(), v.job().getCreatedAt(), v.job().getFinishedAt());
    }
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("@perm.has('import:run')")
  ResponseEntity<JobResponse> upload(@RequestPart("file") MultipartFile file,
      @RequestParam(defaultValue = "false") boolean dryRun) {
    String name = file.getOriginalFilename();
    if (name != null && !name.toLowerCase().endsWith(".xlsx")) {
      throw ProblemException.badRequest("IMPORT_NOT_XLSX", "Upload an .xlsx file (Excel 2007 or later)");
    }
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      throw ProblemException.badRequest("IMPORT_UNREADABLE", "The upload could not be read");
    }
    JobView job = imports.start(name, bytes, dryRun);
    return ResponseEntity.accepted().location(URI.create("/v1/imports/" + job.job().getId()))
        .body(JobResponse.from(job));
  }

  @GetMapping
  @PreAuthorize("@perm.has('import:run')")
  List<JobResponse> recent() {
    return imports.recent().stream().map(JobResponse::from).toList();
  }

  @GetMapping("/{id}")
  @PreAuthorize("@perm.has('import:run')")
  JobResponse get(@PathVariable UUID id) {
    return JobResponse.from(imports.get(id));
  }

  @GetMapping("/template")
  @PreAuthorize("@perm.has('import:run')")
  ResponseEntity<byte[]> template() {
    return ResponseEntity.ok()
        .contentType(XLSX)
        .header(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename("societyos-onboarding.xlsx").build().toString())
        .body(imports.template());
  }
}
