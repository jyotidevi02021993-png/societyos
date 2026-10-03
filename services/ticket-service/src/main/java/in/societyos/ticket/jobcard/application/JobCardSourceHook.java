package in.societyos.ticket.jobcard.application;

import in.societyos.ticket.jobcard.domain.JobCard;

/**
 * Lets the ticket a job card was raised from (complaint, breakdown) follow the card's progress
 * without the job card feature depending on it. Called inside the job card's transaction.
 */
public interface JobCardSourceHook {

  /** {@code COMPLAINT}, {@code BREAKDOWN}, … */
  String sourceType();

  default void started(JobCard card) {}

  default void verified(JobCard card) {}

  default void reopened(JobCard card) {}

  default void closed(JobCard card) {}
}
