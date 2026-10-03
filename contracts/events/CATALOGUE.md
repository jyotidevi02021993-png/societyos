# Event contract catalogue (v1)

The single source of truth for events between services. A producer publishes exactly these
fields (it may add optional fields later; never remove or rename within v1). A consumer
declares its own record with the fields it needs and ignores the rest.

## Rules

- Topic: `sos.<context>.events.v1`, where `<context>` is the service name without `-service`
  (`identity`, `society`, `security`, `billing`, `community`, `ticket`, `asset`, `utility`,
  `vendor`, `inventory`, `compliance`, `notification`, `workflow`, `media`, `ai`, `marketplace`).
- Event type: `<context>.<entity>.<past-tense-verb>`: the prefix always equals the topic context.
- Envelope: CloudEvents 1.0 JSON written by `DomainEvents.publish` (`id`, `source`, `type`, `time`,
  `subject`, `societyid`, `actorid`, `actortype`, `data`). Kafka key = aggregate id; headers `ce_type`,
  `ce_id`, `ce_societyid`.
- Ids are UUID strings; times ISO-8601 UTC; money is `…Paise` (long); dates `YYYY-MM-DD`.
- **No PII**: no phone numbers, e-mails, photos or ID numbers in events. Names only where a guard or
  manager screen needs them (visitor name, staff name). Photos are referenced by `mediaId`.
- Consumer groups: `<service>.<purpose>`, each with a DLQ topic `sos.dlq.<group>`.
- **Notifications**: a service that wants a message sent publishes
  `<context>.notification.requested` on its own topic (payload below); notification-service
  subscribes to those types on every topic.

### `<context>.notification.requested` (any producer)
`{ recipientUserIds: [uuid], category, template, params: {string: string}, channels: [PUSH|SMS|WHATSAPP|EMAIL|INAPP], priority: HIGH|NORMAL, dedupeKey }`
Categories: `GATE`, `BILLING`, `COMPLAINT`, `JOBCARD`, `NOTICE`, `BOOKING`, `APPROVAL`, `ALERT`, `SYSTEM`.

---

## identity (identity-service): built

| Type | data |
|---|---|
| `identity.user.registered` | `userId, channel (OTP\|INVITE), preferredLang` |
| `identity.role.assigned` | `assignmentId, userId, roleCode, source (MANUAL\|MEMBERSHIP\|BOOTSTRAP)` |
| `identity.role.revoked` | `assignmentId, userId, roleCode` |
| `identity.role.updated` | `roleId, roleCode, permissions[]` |
| `identity.device.registered` | `deviceId, userId, platform, pushToken` |

## society (society-service): built

| Type | data |
|---|---|
| `society.created` | `societyId, name, city, state, timezone` |
| `society.settings.updated` | `societyId, settings {gateApprovalTimeoutSeconds, visitorRetentionDays, billingDueDay, …}` |
| `society.tower.created` | `towerId, name, code, floorsCount` |
| `society.flat.created` / `society.flat.updated` | `flatId, towerId, towerName, number, label ("A-1203"), floor, areaSqft, flatType, status (OCCUPIED\|VACANT\|UNDER_RENOVATION)` |
| `society.location.created` | `locationId, kind, name, towerId, parentId` |
| `society.facility.created` / `.updated` | `facilityId, kind, name, capacity, chargeable, chargePaise, bookingRules {slotMinutes, maxAdvanceDays, maxPerFlatPerWeek}, status (ACTIVE\|INACTIVE)` |
| `society.membership.created` / `.ended` | `membershipId, flatId, userId, residentId, residentName, kind (OWNER\|TENANT\|FAMILY), isPrimary` |
| `society.vehicle.registered` / `.removed` | `vehicleId, flatId, regNo, kind (CAR\|BIKE\|OTHER), rfidTag` |
| `society.domesticstaff.registered` / `.updated` | `staffId, name, kind (MAID\|COOK\|DRIVER\|NANNY\|OTHER), flatIds[], kycStatus, photoMediaId, status (ACTIVE\|BLOCKED)` |

## security (security-service: Gate & Security)

| Type | data |
|---|---|
| `security.entry.requested` | `entryId, flatId, flatLabel, gateId, visitorName, purpose (GUEST\|CAB\|DELIVERY\|SERVICE\|STAFF), photoMediaId, guardUserId, residentUserIds[], expiresAt` |
| `security.entry.approved` / `.denied` | `entryId, flatId, decidedBy, decidedAt` |
| `security.entry.expired` | `entryId, flatId` |
| `security.entry.checked_in` / `.checked_out` | `entryId, flatId, purpose, at` |
| `security.pass.created` | `passId, flatId, kind, validFrom, validTo, maxUses` |
| `security.sos.raised` | `sosId, flatId, raisedBy, kind (MEDICAL\|FIRE\|SECURITY\|OTHER), at` |
| `security.incident.reported` | `incidentId, kind, severity (LOW\|MEDIUM\|HIGH\|CRITICAL), locationText, reportedBy` |

## billing (billing-service)

| Type | data |
|---|---|
| `billing.billrun.completed` | `billRunId, period (YYYYMM), billCount, totalPaise` |
| `billing.bill.generated` | `billId, flatId, number, period, dueDate, amountPaise, gstPaise, totalPaise` |
| `billing.payment.succeeded` | `paymentId, billId, flatId, amountPaise, method, paidAt, refType, refId` |
| `billing.payment.failed` | `paymentId, billId, flatId, amountPaise, reason` |
| `billing.receipt.issued` | `receiptId, paymentId, flatId, number` |
| `billing.dues.overdue` | `flatId, billId, overduePaise, daysOverdue` |
| `billing.expense.recorded` | `expenseId, category, amountPaise, assetId, vendorId, at` |

## asset (asset-service: Assets & PM)

| Type | data |
|---|---|
| `asset.asset.created` / `.updated` | `assetId, code, name, categoryGroup, locationId, status` |
| `asset.asset.status_changed` | `assetId, code, from, to` |
| `asset.pmtask.due` / `.overdue` | `pmTaskId, pmPlanId, assetId, assetCode, assetName, dueOn, checklistTemplateId` |
| `asset.warranty.expiring` / `asset.amc.expiring` | `assetId, contractId, vendorId, endsOn, daysLeft` |

## ticket (ticket-service: complaints, breakdowns, incidents, job cards)

| Type | data |
|---|---|
| `ticket.complaint.created` | `complaintId, number, flatId, locationId, assetId, categoryId, categoryName, priority (P1..P4), raisedBy, text` (resident text, no phone numbers) |
| `ticket.complaint.resolved` / `.reopened` | `complaintId, number, at` |
| `ticket.breakdown.reported` | `breakdownId, number, assetId, priority` |
| `ticket.incident.raised` | `incidentId, number, kind, severity, locationId` |
| `ticket.jobcard.created` | `jobCardId, number, sourceType (COMPLAINT\|BREAKDOWN\|PM\|CHECKLIST\|INCIDENT), sourceId, assetId, priority` |
| `ticket.jobcard.assigned` | `jobCardId, number, assigneeUserId, vendorId, assetId, priority` |
| `ticket.jobcard.completed` | `jobCardId, number, labourCostPaise, spareCostPaise, totalCostPaise` |
| `ticket.jobcard.closed` | `jobCardId, number, assetId, sourceType, sourceId, labourCostPaise, spares [{spareId, qty, unitCostPaise}], rootCause, closedAt, recoverableFlatId` |

## workflow (workflow-service: approvals, SLA timers, escalation)

| Type | data |
|---|---|
| `workflow.approval.requested` | `taskId, instanceId, subjectType, subjectId, step, approverRole, approverUserId, amountPaise` |
| `workflow.instance.approved` / `.rejected` | `instanceId, subjectType, subjectId, decidedBy, comment` |
| `workflow.sla.warning` / `.breached` | `timerId, subjectType, subjectId, kind (RESPOND\|RESOLVE), dueAt` |
| `workflow.escalated` | `subjectType, subjectId, level, toRole` |

Subjects: `subjectType` is `COMPLAINT`, `JOBCARD`, `PO`, `EXPENSE`, `BUDGET`, …; workflow-service starts
approvals on `vendor.po.submitted`, `billing.expense.submitted`, `ticket.jobcard.completed` (cost above
threshold) and SLA timers on `ticket.complaint.created` (stops on `ticket.complaint.resolved`).

## community (community-service)

| Type | data |
|---|---|
| `community.notice.published` | `noticeId, title, audience {all \| towerIds[] \| roles[]}, pinned, publishAt` |
| `community.poll.closed` | `pollId, question, results [{optionId, label, votes}]` |
| `community.event.created` | `eventId, kind, title, startsAt, endsAt, feePaise` |
| `community.booking.requested` / `.confirmed` / `.cancelled` | `bookingId, facilityId, flatId, userId, startsAt, endsAt, chargePaise` |

## notification (notification-service)

| Type | data |
|---|---|
| `notification.message.delivered` / `.failed` | `notificationId, userId, channel, category, reason` |

## media (media-service)

| Type | data |
|---|---|
| `media.file.uploaded` | `mediaId, ownerService, purpose, contentType, sizeBytes` |
| `media.file.processed` | `mediaId, ownerService, purpose, thumbnailMediaId` |
| `media.file.rejected` | `mediaId, ownerService, reason` |

## utility (utility-service: WTP, STP, DG, lifts, readings, checklists)

| Type | data |
|---|---|
| `utility.reading.recorded` | `readingId, assetId, meterId, metric, value, unit, at` |
| `utility.reading.anomaly` | `readingId, assetId, metric, value, expectedMin, expectedMax` |
| `utility.checklist.completed` | `runId, templateId, templateName, assetId, locationId, okCount, failedCount` |
| `utility.checklist.item_failed` | `runId, itemCode, itemLabel, assetId, locationId, note` |
| `utility.signoff.completed` | `signoffId, date, managerUserId` |

## vendor (vendor-service: vendors, agents, quotes, POs, GRN, invoices)

| Type | data |
|---|---|
| `vendor.vendor.created` / `.updated` | `vendorId, code, name, workScopes[], status` |
| `vendor.agent.created` | `agentId, userId, vendorId, role, name` |
| `vendor.po.submitted` / `.approved` / `.rejected` | `poId, number, vendorId, amountPaise` |
| `vendor.grn.recorded` | `grnId, poId, storeId, lines [{itemCode, spareId, qty, unitCostPaise}]` |
| `vendor.invoice.approved` | `invoiceId, vendorId, poId, amountPaise` |

## inventory (inventory-service: spares, stock, stores)

| Type | data |
|---|---|
| `inventory.spare.issued` | `issueId, spareId, storeId, qty, unitCostPaise, jobCardId` |
| `inventory.stock.low` | `spareId, spareName, storeId, qty, reorderLevel` |

## compliance (compliance-service: certificates, statutory documents)

| Type | data |
|---|---|
| `compliance.document.uploaded` | `documentId, kind, mediaId, linkedType, linkedId` |
| `compliance.certificate.expiring` | `itemId, kind, name, expiresOn, daysLeft` |
| `compliance.certificate.expired` | `itemId, kind, name, expiredOn` |

## ai (ai-service, Python)

| Type | data |
|---|---|
| `ai.classification.suggested` | `complaintId, categoryName, department, priority, confidence (0..1)` |

## marketplace (marketplace-service, Phase 3)

| Type | data |
|---|---|
| `marketplace.servicebooking.confirmed` | `bookingId, flatId, providerName, serviceName, expectedAt` |
