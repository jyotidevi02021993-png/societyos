package in.societyos.identity.auth.application;

/**
 * Sends the login OTP. The default implementation logs (local dev); the MSG91 / WhatsApp adapter
 * replaces it in staging and prod. OTP SMS is sent directly (not via notification-service)
 * because login must work even when notification-service is down.
 */
public interface SmsSender {

  void sendOtp(String phoneE164, String code, String lang);
}
