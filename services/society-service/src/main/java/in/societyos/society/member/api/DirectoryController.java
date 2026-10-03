package in.societyos.society.member.api;

import in.societyos.society.member.application.MemberService;
import in.societyos.society.member.application.MemberService.DirectoryEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The opt-in resident directory: names and flats only, never contact details. */
@RestController
@RequestMapping("/v1/directory")
class DirectoryController {

  private final MemberService members;

  DirectoryController(MemberService members) {
    this.members = members;
  }

  @GetMapping
  @PreAuthorize("@perm.has('directory:view')")
  List<DirectoryEntry> list(@RequestParam(required = false) UUID towerId) {
    return members.directory(towerId);
  }
}
