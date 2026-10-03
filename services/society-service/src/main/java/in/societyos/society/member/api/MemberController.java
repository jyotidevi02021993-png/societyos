package in.societyos.society.member.api;

import in.societyos.society.member.application.MemberService;
import in.societyos.society.member.application.MemberService.MemberView;
import in.societyos.society.member.domain.FlatMembership;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/members")
class MemberController {

  private final MemberService members;

  MemberController(MemberService members) {
    this.members = members;
  }

  record AddRequest(@NotNull UUID flatId, @NotBlank String phone, @NotBlank @Size(max = 120) String name,
      @NotBlank String kind, LocalDate fromDate, boolean isPrimary) {}

  record EndRequest(LocalDate toDate) {}

  record MemberResponse(UUID membershipId, UUID flatId, String flatLabel, UUID residentId, UUID userId,
      String residentName, String kind, LocalDate fromDate, LocalDate toDate, boolean isPrimary, boolean active) {
    static MemberResponse from(MemberView v) {
      FlatMembership m = v.membership();
      return new MemberResponse(m.getId(), m.getFlatId(), v.flatLabel(), m.getResidentId(), m.getUserId(),
          v.residentName(), m.getKind(), m.getFromDate(), m.getToDate(), m.isPrimary(), m.isActive());
    }
  }

  @GetMapping
  @PreAuthorize("@perm.has('member:view') or @perm.has('member:manage')")
  List<MemberResponse> list(
      @RequestParam(required = false) UUID flatId, @RequestParam(defaultValue = "false") boolean includeEnded) {
    List<MemberView> found = flatId == null ? members.listAll(includeEnded) : members.listByFlat(flatId, includeEnded);
    return found.stream().map(MemberResponse::from).toList();
  }

  @PostMapping
  @PreAuthorize("@perm.has('member:manage')")
  ResponseEntity<MemberResponse> add(@Valid @RequestBody AddRequest r) {
    MemberView added = members.add(
        new MemberService.NewMember(r.flatId(), r.phone(), r.name(), r.kind(), r.fromDate(), r.isPrimary()));
    return ResponseEntity.created(URI.create("/v1/members/" + added.membership().getId()))
        .body(MemberResponse.from(added));
  }

  @PostMapping("/{membershipId}/end")
  @PreAuthorize("@perm.has('member:manage')")
  MemberResponse end(@PathVariable UUID membershipId, @RequestBody(required = false) EndRequest r) {
    return MemberResponse.from(members.end(membershipId, r == null ? null : r.toDate()));
  }
}
