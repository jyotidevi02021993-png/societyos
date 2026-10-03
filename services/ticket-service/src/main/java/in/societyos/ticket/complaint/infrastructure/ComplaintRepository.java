package in.societyos.ticket.complaint.infrastructure;

import in.societyos.ticket.complaint.domain.Complaint;
import in.societyos.ticket.complaint.domain.ComplaintStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ComplaintRepository extends JpaRepository<Complaint, UUID> {

  List<Complaint> findAllByOrderByCreatedAtDesc();

  List<Complaint> findByStatusOrderByCreatedAtDesc(ComplaintStatus status);

  /** A resident's view: what they raised, plus complaints about their flats. */
  @Query("select c from Complaint c where c.raisedBy = ?1 or c.flatId in ?2 order by c.createdAt desc")
  List<Complaint> findVisibleTo(UUID userId, Collection<UUID> flatIds);
}
