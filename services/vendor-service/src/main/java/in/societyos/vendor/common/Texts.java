package in.societyos.vendor.common;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

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

  /** Distinct, cleaned, upper-cased values. */
  public static List<String> upperAll(Collection<String> values) {
    if (values == null) {
      return List.of();
    }
    return values.stream().map(Texts::upper).filter(Objects::nonNull).distinct().toList();
  }
}
