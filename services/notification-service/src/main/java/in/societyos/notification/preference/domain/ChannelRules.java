package in.societyos.notification.preference.domain;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which requested channels a user accepts: {@code disabled} maps a category (or {@code *} for all)
 * to channels the user switched off. The in-app inbox cannot be switched off for HIGH priority.
 */
public final class ChannelRules {

  public static final Set<String> CHANNELS = Set.of("PUSH", "SMS", "WHATSAPP", "EMAIL", "INAPP");

  private ChannelRules() {}

  public static Set<String> allowed(Collection<String> requested, Map<String, List<String>> disabled, String category,
      String priority) {
    Set<String> off = new LinkedHashSet<>();
    if (disabled != null) {
      off.addAll(disabled.getOrDefault("*", List.of()));
      off.addAll(disabled.getOrDefault(category, List.of()));
    }
    Set<String> out = new LinkedHashSet<>();
    for (String c : requested) {
      String ch = c == null ? "" : c.trim().toUpperCase();
      if (!CHANNELS.contains(ch)) {
        continue;
      }
      if (!off.contains(ch) || ("INAPP".equals(ch) && "HIGH".equals(priority))) {
        out.add(ch);
      }
    }
    return out;
  }
}
