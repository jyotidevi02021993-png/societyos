package in.societyos.audit.log.domain;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Makes an event's data safe and small enough to keep for years: drops keys that may carry personal
 * data (events should not have any, this is the second line of defence), cuts long strings and
 * arrays, flattens deep nesting, and caps the total size.
 */
public final class PayloadTrimmer {

  public static final int MAX_STRING = 256;
  public static final int MAX_ARRAY = 50;
  public static final int MAX_DEPTH = 4;
  public static final int MAX_BYTES = 8 * 1024;

  /** Key fragments (lower case) never stored. */
  static final Set<String> DROPPED_FRAGMENTS = Set.of("phone", "mobile", "email", "aadhaar", "aadhar", "passport",
      "password", "otp", "secret", "token", "address", "birth", "idnumber", "pannumber");

  /** Free-text keys (resident text, notice bodies): not needed for the trail, may contain anything. */
  static final Set<String> DROPPED_KEYS = Set.of("text", "body", "description", "note", "pan", "dob");

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  public record Result(JsonNode payload, boolean trimmed) {}

  private PayloadTrimmer() {}

  public static Result trim(JsonNode data) {
    if (data == null || data.isNull() || data.isMissingNode()) {
      return new Result(NODES.objectNode(), false);
    }
    boolean[] changed = {false};
    JsonNode out = copy(data, 0, changed);
    if (out.toString().length() > MAX_BYTES) {
      ObjectNode flat = NODES.objectNode();
      if (out.isObject()) {
        for (Map.Entry<String, JsonNode> e : out.properties()) {
          if (e.getValue().isValueNode()) {
            flat.set(e.getKey(), e.getValue());
          }
        }
      }
      flat.put("_truncated", true);
      out = flat;
      changed[0] = true;
    }
    return new Result(out, changed[0]);
  }

  static boolean isDropped(String key) {
    String k = key.toLowerCase(Locale.ROOT);
    if (DROPPED_KEYS.contains(k)) {
      return true;
    }
    if (k.endsWith("id") || k.endsWith("ids")) {
      return false; // identifiers (flatId, photoMediaId, residentUserIds) are what the trail is for
    }
    for (String f : DROPPED_FRAGMENTS) {
      if (k.contains(f)) {
        return true;
      }
    }
    return false;
  }

  private static JsonNode copy(JsonNode node, int depth, boolean[] changed) {
    if (node.isString()) {
      String s = node.asString();
      if (s.length() > MAX_STRING) {
        changed[0] = true;
        return NODES.stringNode(s.substring(0, MAX_STRING) + "…");
      }
      return node;
    }
    if (node.isValueNode()) {
      return node;
    }
    if (depth >= MAX_DEPTH) {
      changed[0] = true;
      return NODES.stringNode("[nested]");
    }
    if (node.isArray()) {
      ArrayNode arr = NODES.arrayNode();
      int i = 0;
      for (JsonNode item : node) {
        if (i++ == MAX_ARRAY) {
          changed[0] = true;
          arr.add("…" + (node.size() - MAX_ARRAY) + " more");
          break;
        }
        arr.add(copy(item, depth + 1, changed));
      }
      return arr;
    }
    ObjectNode obj = NODES.objectNode();
    for (Map.Entry<String, JsonNode> e : node.properties()) {
      if (isDropped(e.getKey())) {
        changed[0] = true;
        continue;
      }
      obj.set(e.getKey(), copy(e.getValue(), depth + 1, changed));
    }
    return obj;
  }
}
