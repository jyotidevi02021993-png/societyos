package in.societyos.society.common;

import in.societyos.society.platform.core.error.ProblemException;
import java.util.regex.Pattern;

/** Phone numbers in E.164. Bare 10-digit Indian mobiles (and 0/91 prefixes) become +91. */
public final class Phones {

  private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");
  private static final Pattern INDIAN_MOBILE = Pattern.compile("^[6-9]\\d{9}$");

  private Phones() {}

  public static String normalize(String raw) {
    if (raw == null) {
      throw invalid();
    }
    String s = raw.replaceAll("[\\s\\-().]", "");
    if (s.startsWith("00")) {
      s = "+" + s.substring(2);
    }
    if (!s.startsWith("+")) {
      if (s.length() == 11 && s.startsWith("0")) {
        s = s.substring(1);
      } else if (s.length() == 12 && s.startsWith("91")) {
        s = s.substring(2);
      }
      if (!INDIAN_MOBILE.matcher(s).matches()) {
        throw invalid();
      }
      s = "+91" + s;
    }
    if (!E164.matcher(s).matches()) {
      throw invalid();
    }
    return s;
  }

  private static ProblemException invalid() {
    return ProblemException.badRequest("INVALID_PHONE", "phone must be a mobile number like +919876543210");
  }
}
