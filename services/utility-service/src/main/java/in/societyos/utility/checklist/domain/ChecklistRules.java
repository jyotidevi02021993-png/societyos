package in.societyos.utility.checklist.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Checklist template items and the rules for answering them.
 *
 * <ul>
 *   <li>CHECK items are answered OK / NOT_OK / NA.
 *   <li>NUMBER items carry a value; a value outside [min, max] is NOT_OK whatever was ticked
 *       (e.g. DG oil pressure, STP pH).
 *   <li>TEXT items are informational: OK unless ticked NOT_OK.
 *   <li>Every required item must be answered; NA is an answer.
 * </ul>
 */
public final class ChecklistRules {

  private ChecklistRules() {}

  public enum ItemType { CHECK, NUMBER, TEXT }

  public enum Result { OK, NOT_OK, NA }

  public record Item(String code, String label, ItemType type, boolean required, BigDecimal min, BigDecimal max,
      String unit) {}

  public record Answer(String code, Result result, BigDecimal value, String textValue, String note,
      java.util.UUID photoMediaId) {}

  public record Scored(Item item, Result result, BigDecimal value, String textValue, String note,
      java.util.UUID photoMediaId, boolean outOfRange) {}

  public record Evaluation(List<Scored> answers, int ok, int failed, int na) {
    public List<Scored> failures() {
      return answers.stream().filter(a -> a.result() == Result.NOT_OK).toList();
    }
  }

  public static List<Item> validateItems(List<Item> items) {
    if (items == null || items.isEmpty()) {
      throw new IllegalArgumentException("A checklist needs at least one item");
    }
    Set<String> codes = new HashSet<>();
    List<Item> clean = new ArrayList<>();
    for (Item i : items) {
      if (i.label() == null || i.label().isBlank()) {
        throw new IllegalArgumentException("Every item needs a label");
      }
      String code = i.code() == null || i.code().isBlank() ? "ITEM_" + (clean.size() + 1)
          : i.code().trim().toUpperCase(Locale.ROOT);
      if (!codes.add(code)) {
        throw new IllegalArgumentException("Duplicate item code " + code);
      }
      ItemType type = i.type() == null ? ItemType.CHECK : i.type();
      if (i.min() != null && i.max() != null && i.min().compareTo(i.max()) > 0) {
        throw new IllegalArgumentException("Item " + code + ": min is above max");
      }
      if (type != ItemType.NUMBER && (i.min() != null || i.max() != null)) {
        throw new IllegalArgumentException("Item " + code + ": min/max apply to NUMBER items only");
      }
      clean.add(new Item(code, i.label().trim(), type, i.required(), i.min(), i.max(), i.unit()));
    }
    return clean;
  }

  public static Evaluation evaluate(List<Item> items, List<Answer> answers) {
    Map<String, Answer> byCode = new LinkedHashMap<>();
    for (Answer a : answers == null ? List.<Answer>of() : answers) {
      if (a.code() == null) {
        throw new IllegalArgumentException("Every answer needs an item code");
      }
      String code = a.code().trim().toUpperCase(Locale.ROOT);
      if (byCode.put(code, a) != null) {
        throw new IllegalArgumentException("Item " + code + " answered twice");
      }
    }
    Set<String> known = new HashSet<>();
    items.forEach(i -> known.add(i.code()));
    for (String code : byCode.keySet()) {
      if (!known.contains(code)) {
        throw new IllegalArgumentException("Unknown item " + code);
      }
    }
    List<Scored> scored = new ArrayList<>();
    int ok = 0, failed = 0, na = 0;
    for (Item item : items) {
      Answer a = byCode.get(item.code());
      if (a == null) {
        if (item.required()) {
          throw new IllegalArgumentException("Required item " + item.code() + " (" + item.label() + ") is not answered");
        }
        continue;
      }
      Scored s = score(item, a);
      scored.add(s);
      switch (s.result()) {
        case OK -> ok++;
        case NOT_OK -> failed++;
        case NA -> na++;
      }
    }
    return new Evaluation(List.copyOf(scored), ok, failed, na);
  }

  private static Scored score(Item item, Answer a) {
    Result result = a.result();
    boolean outOfRange = false;
    if (item.type() == ItemType.NUMBER && result != Result.NA) {
      if (a.value() == null) {
        throw new IllegalArgumentException("Item " + item.code() + " needs a value");
      }
      outOfRange = (item.min() != null && a.value().compareTo(item.min()) < 0)
          || (item.max() != null && a.value().compareTo(item.max()) > 0);
      if (outOfRange) {
        result = Result.NOT_OK;
      } else if (result == null) {
        result = Result.OK;
      }
    }
    if (result == null) {
      if (item.type() == ItemType.TEXT) {
        result = Result.OK;
      } else {
        throw new IllegalArgumentException("Item " + item.code() + " needs a result");
      }
    }
    String note = a.note();
    if (outOfRange && (note == null || note.isBlank())) {
      note = "Value " + a.value().stripTrailingZeros().toPlainString()
          + (item.unit() == null ? "" : " " + item.unit()) + " outside "
          + (item.min() == null ? "-" : item.min().toPlainString()) + ".."
          + (item.max() == null ? "-" : item.max().toPlainString());
    }
    return new Scored(item, result, a.value(), a.textValue(), note, a.photoMediaId(), outOfRange);
  }
}
