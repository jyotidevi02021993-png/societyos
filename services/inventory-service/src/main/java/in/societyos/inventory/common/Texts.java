package in.societyos.inventory.common;

import java.util.Locale;

/** Small input-normalising helpers shared by the features. */
public final class Texts {

  private Texts() {}

  /** Trimmed text, or null when blank. */
  public static String clean(String s) {
    if (s == null) {
      return null;
    }
    String t = s.strip();
    return t.isEmpty() ? null : t;
  }

  /** Upper-case code without spaces, or null when blank. */
  public static String code(String s) {
    String t = clean(s);
    return t == null ? null : t.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
  }

  public static String upper(String s) {
    String t = clean(s);
    return t == null ? null : t.toUpperCase(Locale.ROOT);
  }
}
