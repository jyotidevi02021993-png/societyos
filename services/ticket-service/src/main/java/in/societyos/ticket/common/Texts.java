package in.societyos.ticket.common;

import java.util.regex.Pattern;

/** Text helpers for what may leave the service in events. */
public final class Texts {

  private static final Pattern EMAIL =
      Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
  private static final Pattern PHONE = Pattern.compile("(?<!\\w)\\+?\\d[\\d ()-]{7,}\\d(?!\\w)");

  private Texts() {}

  /** Resident text with phone numbers and e-mails removed (no PII in events). */
  public static String redactContact(String text) {
    if (text == null) {
      return null;
    }
    return PHONE.matcher(EMAIL.matcher(text).replaceAll("[redacted email]")).replaceAll("[redacted phone]");
  }

  public static String clean(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
