-- Platform templates (channel ANY = every channel), English and Hindi. A template missing here
-- still sends: the renderer falls back to a generic title and the request's params.
INSERT INTO notification_template (id, code, channel, lang, title, body) VALUES
  (gen_random_uuid(), 'community.notice.published', 'ANY', 'en', 'New notice', '{{title}}'),
  (gen_random_uuid(), 'community.notice.published', 'ANY', 'hi', 'नई सूचना', '{{title}}'),
  (gen_random_uuid(), 'community.poll.created', 'ANY', 'en', 'New poll', 'Cast your vote: {{question}}'),
  (gen_random_uuid(), 'community.poll.created', 'ANY', 'hi', 'नया मतदान', 'अपना वोट दें: {{question}}'),
  (gen_random_uuid(), 'community.event.created', 'ANY', 'en', 'New event', '{{title}} is coming up. RSVP in the app.'),
  (gen_random_uuid(), 'community.event.created', 'ANY', 'hi', 'नया कार्यक्रम', '{{title}} जल्द आ रहा है। ऐप में RSVP करें।'),
  (gen_random_uuid(), 'community.booking.confirmed', 'ANY', 'en', 'Booking confirmed', '{{facility}} is booked for you.'),
  (gen_random_uuid(), 'community.booking.confirmed', 'ANY', 'hi', 'बुकिंग पक्की', '{{facility}} आपके लिए बुक हो गया है।'),
  (gen_random_uuid(), 'community.booking.cancelled', 'ANY', 'en', 'Booking cancelled', 'Your {{facility}} booking was cancelled.'),
  (gen_random_uuid(), 'community.booking.cancelled', 'ANY', 'hi', 'बुकिंग रद्द', 'आपकी {{facility}} बुकिंग रद्द कर दी गई।'),
  (gen_random_uuid(), 'gate.entry.requested', 'ANY', 'en', 'Visitor at the gate', '{{visitorName}} is at the gate for {{flatLabel}}. Approve or deny.'),
  (gen_random_uuid(), 'gate.entry.requested', 'ANY', 'hi', 'गेट पर आगंतुक', '{{visitorName}} {{flatLabel}} के लिए गेट पर हैं। अनुमति दें या मना करें।'),
  (gen_random_uuid(), 'billing.bill.published', 'ANY', 'en', 'Your bill is ready', 'Bill {{billNumber}} for {{flatLabel}}: Rs {{amount}}, due {{dueDate}}.'),
  (gen_random_uuid(), 'billing.bill.published', 'ANY', 'hi', 'आपका बिल तैयार है', '{{flatLabel}} का बिल {{billNumber}}: ₹{{amount}}, देय तिथि {{dueDate}}।'),
  (gen_random_uuid(), 'billing.bill.published', 'EMAIL', 'en', 'Your SocietyOS bill {{billNumber}}',
   'Hello,\n\nBill {{billNumber}} for {{flatLabel}} ({{period}}) is Rs {{amount}}, due on {{dueDate}}.\nPay in the SocietyOS app.\n');
