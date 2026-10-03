package in.societyos.dashboard.platform.events.internal;

/** Topic naming: {@code sos.<context>.events.v1}. Must match the Debezium route replacement. */
public final class OutboxTopics {

  private OutboxTopics() {}

  public static String topicFor(String context) {
    return "sos." + context + ".events.v1";
  }
}
