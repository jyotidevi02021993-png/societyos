package in.societyos.notification.template.domain;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** {@code {{name}}} placeholders filled from the request's params; unknown names render empty. */
public final class TemplateRenderer {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.]+)\\s*}}");

  /** A rendered message. */
  public record Rendered(String title, String body) {}

  private TemplateRenderer() {}

  public static String fill(String text, Map<String, String> params) {
    Matcher m = PLACEHOLDER.matcher(text);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String v = params.getOrDefault(m.group(1), "");
      m.appendReplacement(out, Matcher.quoteReplacement(v == null ? "" : v));
    }
    m.appendTail(out);
    return out.toString().replace("\\n", "\n").trim();
  }

  public static Rendered render(String title, String body, Map<String, String> params) {
    return new Rendered(fill(title, params), fill(body, params));
  }

  /** For a template nobody has written yet: "Billing update" + the display params. */
  public static Rendered fallback(String category, String template, Map<String, String> params) {
    String c = category == null || category.isBlank() ? "SocietyOS" : category.charAt(0)
        + category.substring(1).toLowerCase().replace('_', ' ');
    String body = params.entrySet().stream()
        .filter(e -> !e.getKey().endsWith("Id") && e.getValue() != null && !e.getValue().isBlank())
        .sorted(Map.Entry.comparingByKey())
        .map(e -> e.getKey() + ": " + e.getValue())
        .collect(Collectors.joining(", "));
    return new Rendered(c + " update", body.isEmpty() ? template : body);
  }
}
