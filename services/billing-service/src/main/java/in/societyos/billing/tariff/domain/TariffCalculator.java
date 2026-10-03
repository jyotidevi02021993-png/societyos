package in.societyos.billing.tariff.domain;

import in.societyos.billing.common.Paise;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Computes one flat's bill lines for a period from the society's charge heads, its pending one-off
 * charges and the GST policy. Pure and exact: long paise only (see {@link Paise} for rounding).
 *
 * <ul>
 *   <li>{@code FIXED}: the head's rate per flat.
 *   <li>{@code PER_SQFT}: area (sq ft) x rate (paise per sq ft); a flat without an area gets no line
 *       and a warning.
 *   <li>{@code FLAT_TYPE}: the rate for the flat's type, else the head's rate as the fallback.
 *   <li>Heads with {@code appliesToVacant = false} skip VACANT flats. Zero amounts give no line.
 * </ul>
 *
 * <p>GST (RWA rule): when the society is GST-registered and the flat's GST-applicable recurring
 * charges for the month exceed the exemption threshold (Rs 7,500 by default), GST is due on the
 * <em>whole</em> of those charges, not just the excess; at or below the threshold they are exempt.
 * GST-applicable one-off charges (e.g. facility bookings) are taxed whenever the society is
 * registered. GST is computed per line and rounded half-up to the paisa.
 */
public final class TariffCalculator {

  private TariffCalculator() {}

  public enum Basis { FIXED, PER_SQFT, FLAT_TYPE }

  public record Tariff(String code, String name, Basis basis, long ratePaise, Map<String, Long> flatTypeRates,
      boolean gstApplicable, boolean appliesToVacant) {
    public Tariff {
      flatTypeRates = flatTypeRates == null ? Map.of() : Map.copyOf(flatTypeRates);
    }
  }

  public record FlatProfile(String label, Integer areaSqft, String flatType, String status) {}

  public record OneOffCharge(UUID ref, String description, long amountPaise, boolean gstApplicable) {}

  public record GstPolicy(boolean registered, int rateBps, long exemptionThresholdPaise) {
    public static final GstPolicy NONE = new GstPolicy(false, 0, 0);
  }

  public record Line(String kind, String code, String description, long amountPaise, long gstPaise, UUID sourceRef) {
    public long totalPaise() {
      return Paise.plus(amountPaise, gstPaise);
    }
  }

  public record Result(List<Line> lines, List<String> warnings) {
    public long amountPaise() {
      return lines.stream().mapToLong(Line::amountPaise).reduce(0, Math::addExact);
    }

    public long gstPaise() {
      return lines.stream().mapToLong(Line::gstPaise).reduce(0, Math::addExact);
    }

    public long totalPaise() {
      return Math.addExact(amountPaise(), gstPaise());
    }

    public boolean isEmpty() {
      return lines.isEmpty();
    }
  }

  public static Result calculate(FlatProfile flat, List<Tariff> tariffs, List<OneOffCharge> oneOffs, GstPolicy gst) {
    List<String> warnings = new ArrayList<>();
    List<Line> recurring = new ArrayList<>();
    List<Boolean> taxable = new ArrayList<>();
    boolean vacant = "VACANT".equalsIgnoreCase(flat.status());
    for (Tariff t : tariffs) {
      if (vacant && !t.appliesToVacant()) {
        continue;
      }
      long amount;
      String description;
      switch (t.basis()) {
        case FIXED -> {
          amount = t.ratePaise();
          description = t.name();
        }
        case PER_SQFT -> {
          if (flat.areaSqft() == null || flat.areaSqft() <= 0) {
            warnings.add(flat.label() + ": no area, " + t.name() + " not charged");
            continue;
          }
          amount = Paise.times(t.ratePaise(), flat.areaSqft());
          description = "%s (%d sq ft x Rs %s)".formatted(t.name(), flat.areaSqft(), Paise.rupees(t.ratePaise()));
        }
        case FLAT_TYPE -> {
          Long rate = flat.flatType() == null ? null : t.flatTypeRates().get(flat.flatType().toUpperCase());
          if (rate == null) {
            rate = t.ratePaise();
            if (rate == 0) {
              warnings.add(flat.label() + ": no " + t.name() + " rate for type " + flat.flatType());
            }
          }
          amount = rate;
          description = flat.flatType() == null ? t.name() : t.name() + " (" + flat.flatType() + ")";
        }
        default -> throw new IllegalStateException(t.basis().name());
      }
      if (amount > 0) {
        recurring.add(new Line("CHARGE", t.code(), description, amount, 0, null));
        taxable.add(t.gstApplicable());
      }
    }

    long taxableRecurring = 0;
    for (int i = 0; i < recurring.size(); i++) {
      if (taxable.get(i)) {
        taxableRecurring = Paise.plus(taxableRecurring, recurring.get(i).amountPaise());
      }
    }
    boolean gstOnRecurring = gst.registered() && taxableRecurring > gst.exemptionThresholdPaise();

    List<Line> lines = new ArrayList<>(recurring.size() + oneOffs.size());
    for (int i = 0; i < recurring.size(); i++) {
      Line l = recurring.get(i);
      long tax = gstOnRecurring && taxable.get(i) ? Paise.percent(l.amountPaise(), gst.rateBps()) : 0;
      lines.add(new Line(l.kind(), l.code(), l.description(), l.amountPaise(), tax, null));
    }
    for (OneOffCharge c : oneOffs) {
      long tax = gst.registered() && c.gstApplicable() ? Paise.percent(c.amountPaise(), gst.rateBps()) : 0;
      lines.add(new Line("ONE_OFF", null, c.description(), c.amountPaise(), tax, c.ref()));
    }
    return new Result(List.copyOf(lines), List.copyOf(warnings));
  }
}
