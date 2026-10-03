package in.societyos.media.media.domain;

/** A media rule was broken; the application layer maps {@link #code()} to a 4xx problem. */
public class MediaRuleException extends RuntimeException {

  private final String code;

  public MediaRuleException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
