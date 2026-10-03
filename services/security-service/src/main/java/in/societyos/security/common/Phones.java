package in.societyos.security.common;

import in.societyos.security.platform.core.error.ProblemException;

/** Indian mobile numbers in E.164 ({@code +919876543210}); the form hashed and encrypted. */
public final class Phones {

  private Phones() {}

  public static String normalise(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String digits = raw.replaceAll("[\\s()-]", "");
    if (digits.startsWith("+91")) {
      digits = digits.substring(3);
    } else if (digits.startsWith("0091")) {
      digits = digits.substring(4);
    } else if (digits.startsWith("91") && digits.length() == 12) {
      digits = digits.substring(2);
    } else if (digits.startsWith("0") && digits.length() == 11) {
      digits = digits.substring(1);
    }
    if (!digits.matches("[6-9]\\d{9}")) {
      throw ProblemException.badRequest("INVALID_PHONE", "Enter a 10-digit Indian mobile number");
    }
    return "+91" + digits;
  }
}
