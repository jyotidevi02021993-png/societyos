package in.societyos.identity.auth.infrastructure;

import in.societyos.identity.auth.application.SmsSender;
import in.societyos.identity.platform.core.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/** Local development: writes the OTP to the log instead of sending an SMS. */
@Component
@ConditionalOnMissingBean(name = "msg91SmsSender")
class LoggingSmsSender implements SmsSender {

  private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

  @Override
  public void sendOtp(String phoneE164, String code, String lang) {
    log.warn("[DEV SMS] OTP for {} is {}", Hashing.maskPhone(phoneE164), code);
  }
}
