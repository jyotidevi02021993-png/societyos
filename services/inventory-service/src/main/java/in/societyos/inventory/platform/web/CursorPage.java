package in.societyos.inventory.platform.web;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Cursor pagination response: {@code { items, nextCursor }}. Cursors encode the (createdAt, id)
 * of the last row, which matches the {@code order by created_at desc, id desc} convention.
 */
public record CursorPage<T>(List<T> items, String nextCursor) {

  public static final int DEFAULT_LIMIT = 50;
  public static final int MAX_LIMIT = 200;

  public record Cursor(Instant createdAt, UUID id) {}

  /** Builds a page from {@code limit + 1} fetched rows. */
  public static <E, T> CursorPage<T> of(
      List<E> rows, int limit, Function<E, T> mapper, Function<E, Cursor> cursorOf) {
    boolean more = rows.size() > limit;
    List<E> page = more ? rows.subList(0, limit) : rows;
    String next = more ? encode(cursorOf.apply(page.getLast())) : null;
    return new CursorPage<>(page.stream().map(mapper).toList(), next);
  }

  public static int clampLimit(Integer limit) {
    if (limit == null || limit <= 0) {
      return DEFAULT_LIMIT;
    }
    return Math.min(limit, MAX_LIMIT);
  }

  public static String encode(Cursor c) {
    String raw = c.createdAt().toEpochMilli() + ":" + c.id();
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  public static Cursor decode(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      int i = raw.indexOf(':');
      return new Cursor(Instant.ofEpochMilli(Long.parseLong(raw.substring(0, i))), UUID.fromString(raw.substring(i + 1)));
    } catch (RuntimeException e) {
      throw in.societyos.inventory.platform.core.error.ProblemException.badRequest("INVALID_CURSOR", "Invalid cursor");
    }
  }
}
