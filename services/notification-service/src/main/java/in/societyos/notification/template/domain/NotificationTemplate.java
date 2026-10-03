package in.societyos.notification.template.domain;

import in.societyos.notification.platform.jpa.GlobalEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Global (reviewed): platform message templates per code, channel (or ANY) and language (en, hi). */
@Entity
@Table(name = "notification_template")
public class NotificationTemplate extends GlobalEntity {

  public static final String ANY = "ANY";

  @Column(nullable = false) private String code;
  @Column(nullable = false) private String channel;
  @Column(nullable = false) private String lang;
  @Column(nullable = false) private String title;
  @Column(nullable = false) private String body;

  protected NotificationTemplate() {}

  public NotificationTemplate(String code, String channel, String lang, String title, String body) {
    this.code = code;
    this.channel = channel;
    this.lang = lang;
    this.title = title;
    this.body = body;
  }

  public void update(String title, String body) {
    this.title = title;
    this.body = body;
  }

  public String getCode() { return code; }
  public String getChannel() { return channel; }
  public String getLang() { return lang; }
  public String getTitle() { return title; }
  public String getBody() { return body; }
}
