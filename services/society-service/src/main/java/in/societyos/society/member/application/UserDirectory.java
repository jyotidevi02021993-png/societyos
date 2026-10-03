package in.societyos.society.member.application;

import java.util.UUID;

/** identity-service as seen from here: phone → user id, registering the person if needed. */
public interface UserDirectory {

  /**
   * @param phone E.164 number
   * @param name display name, used only when the user is new
   * @return the user's id
   */
  UUID resolveByPhone(String phone, String name);
}
