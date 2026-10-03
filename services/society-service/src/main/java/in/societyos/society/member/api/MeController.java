package in.societyos.society.member.api;

import in.societyos.society.member.api.MemberController.MemberResponse;
import in.societyos.society.member.application.MemberService;
import in.societyos.society.member.domain.Resident;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in resident's own view: their flats and directory choice. */
@RestController
@RequestMapping("/v1/me")
class MeController {

  private final MemberService members;

  MeController(MemberService members) {
    this.members = members;
  }

  record DirectoryChoice(boolean optIn) {}

  record DirectoryChoiceResponse(UUID residentId, boolean optIn) {}

  @GetMapping("/flats")
  @PreAuthorize("isAuthenticated()")
  List<MemberResponse> myFlats() {
    return members.myFlats().stream().map(MemberResponse::from).toList();
  }

  @PutMapping("/directory")
  @PreAuthorize("isAuthenticated()")
  DirectoryChoiceResponse directory(@RequestBody DirectoryChoice choice) {
    Resident r = members.setDirectoryOptIn(choice.optIn());
    return new DirectoryChoiceResponse(r.getId(), r.isDirectoryOptIn());
  }
}
