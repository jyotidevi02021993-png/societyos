package in.societyos.ticket.breakdown.application;

import in.societyos.ticket.jobcard.application.JobCardSourceHook;
import in.societyos.ticket.jobcard.domain.JobCard;
import org.springframework.stereotype.Component;

/** Work started → breakdown IN_REPAIR; job card closed → breakdown RESOLVED with downtime. */
@Component
class BreakdownJobCardHook implements JobCardSourceHook {

  private final BreakdownService breakdowns;

  BreakdownJobCardHook(BreakdownService breakdowns) {
    this.breakdowns = breakdowns;
  }

  @Override
  public String sourceType() {
    return "BREAKDOWN";
  }

  @Override
  public void started(JobCard card) {
    breakdowns.repairStarted(card.getSourceId());
  }

  @Override
  public void closed(JobCard card) {
    breakdowns.jobCardClosed(card.getSourceId(), card.getClosedAt());
  }
}
