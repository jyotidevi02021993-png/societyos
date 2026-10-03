package in.societyos.notification.template.infrastructure;

import in.societyos.notification.template.domain.NotificationTemplate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, UUID> {

  List<NotificationTemplate> findByCode(String code);

  List<NotificationTemplate> findAllByOrderByCodeAscChannelAscLangAsc();
}
