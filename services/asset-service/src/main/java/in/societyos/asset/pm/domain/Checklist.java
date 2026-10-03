package in.societyos.asset.pm.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** PM checklist items (on the plan) and their results (on the task), with the checking rules. */
public final class Checklist {

  private Checklist() {}

  public record Item(String code, String label, boolean required) {}

  public enum Outcome { OK, NOT_OK, NA }

  public record Result(String code, String label, Outcome outcome, String note) {}

  /** Summary of a completed checklist. */
  public record Evaluation(List<Result> results, int ok, int failed, int na) {
    public List<Result> failures() {
      return results.stream().filter(r -> r.outcome() == Outcome.NOT_OK).toList();
    }
  }

  /** Normalises item codes and rejects duplicates or blank labels. */
  public static List<Item> validateItems(List<Item> items) {
    if (items == null) {
      return List.of();
    }
    Set<String> codes = new HashSet<>();
    List<Item> clean = new ArrayList<>();
    for (Item i : items) {
      if (i.label() == null || i.label().isBlank()) {
        throw new IllegalArgumentException("Every checklist item needs a label");
      }
      String code = i.code() == null || i.code().isBlank()
          ? "ITEM_" + (clean.size() + 1)
          : i.code().trim().toUpperCase(Locale.ROOT);
      if (!codes.add(code)) {
        throw new IllegalArgumentException("Duplicate checklist item code " + code);
      }
      clean.add(new Item(code, i.label().trim(), i.required()));
    }
    return clean;
  }

  /**
   * Checks results against the plan's items: unknown codes are rejected, every required item
   * must be answered, and a NOT_OK needs a note so the follow-up ticket is actionable.
   */
  public static Evaluation evaluate(List<Item> items, List<Result> results) {
    Map<String, Item> byCode = new HashMap<>();
    items.forEach(i -> byCode.put(i.code(), i));
    Map<String, Result> answered = new HashMap<>();
    for (Result r : results == null ? List.<Result>of() : results) {
      if (r.code() == null || r.outcome() == null) {
        throw new IllegalArgumentException("Every result needs a code and an outcome");
      }
      String code = r.code().trim().toUpperCase(Locale.ROOT);
      Item item = byCode.get(code);
      if (!items.isEmpty() && item == null) {
        throw new IllegalArgumentException("Unknown checklist item " + code);
      }
      if (answered.containsKey(code)) {
        throw new IllegalArgumentException("Checklist item " + code + " answered twice");
      }
      if (r.outcome() == Outcome.NOT_OK && (r.note() == null || r.note().isBlank())) {
        throw new IllegalArgumentException("Item " + code + " is NOT_OK: add a note");
      }
      String label = item != null ? item.label() : (r.label() == null ? code : r.label());
      answered.put(code, new Result(code, label, r.outcome(), r.note()));
    }
    for (Item i : items) {
      if (i.required() && !answered.containsKey(i.code())) {
        throw new IllegalArgumentException("Required item " + i.code() + " is not answered");
      }
    }
    List<Result> ordered = new ArrayList<>();
    items.forEach(i -> { if (answered.containsKey(i.code())) ordered.add(answered.remove(i.code())); });
    ordered.addAll(answered.values());
    int ok = 0, failed = 0, na = 0;
    for (Result r : ordered) {
      switch (r.outcome()) {
        case OK -> ok++;
        case NOT_OK -> failed++;
        case NA -> na++;
      }
    }
    return new Evaluation(List.copyOf(ordered), ok, failed, na);
  }
}
