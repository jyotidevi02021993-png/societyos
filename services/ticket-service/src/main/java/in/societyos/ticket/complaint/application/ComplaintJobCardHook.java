package in.societyos.ticket.complaint.application;

import in.societyos.ticket.jobcard.application.JobCardSourceHook;
import in.societyos.ticket.jobcard.domain.JobCard;
import org.springframework.stereotype.Component;

/** Keeps a complaint in step with its job card: verified → RESOLVED, reopened → REOPENED, closed → CLOSED. */
@Component
class ComplaintJobCardHook implements JobCardSourceHook {

  private final ComplaintService complaints;

  ComplaintJobCardHook(ComplaintService complaints) {
    this.complaints = complaints;
  }

  @Override
  public String sourceType() {
    return "COMPLAINT";
  }

  @Override
  public void started(JobCard card) {
    complaints.jobCardStarted(card.getSourceId(), card.getId());
  }

  @Override
  public void verified(JobCard card) {
    complaints.jobCardVerified(card.getSourceId(), card.getId());
  }

  @Override
  public void reopened(JobCard card) {
    complaints.jobCardReopened(card.getSourceId(), card.getId());
  }

  @Override
  public void closed(JobCard card) {
    complaints.jobCardClosed(card.getSourceId(), card.getId());
  }
}
