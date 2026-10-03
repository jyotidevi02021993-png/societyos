# media-service (port 8091)

Stores photos and documents for every other service. Files go straight from the client to S3
(MinIO locally) through presigned URLs. This service keeps one `media_file` row per object: owner,
purpose, content type, size, scan status and retention. Other services only ever store a `mediaId`.

## Flow

```
POST /v1/uploads {purpose, contentType, sizeBytes, fileName?}
  → 201 {mediaId, uploadUrl, method: PUT, headers, expiresAt (15 min), maxBytes}
client PUTs the bytes to uploadUrl with the returned headers
POST /v1/uploads/{id}/complete      → HEAD on the object: size ≤ declared, same content type
  → UPLOADED + media.file.uploaded  (a mismatch → REJECTED + media.file.rejected, object deleted)
db-scheduler "media-process" (every sos.media.process-poll)
  → virus scan (VirusScanner) → images: 320 px JPEG thumbnail as its own media_file (purpose THUMBNAIL)
  → READY + media.file.processed {thumbnailMediaId}   or   REJECTED + media.file.rejected
GET /v1/media/{id}/download-url[?variant=thumbnail] → {url, contentType, expiresAt (5 min)}
```

## API (all need a valid token; the active society is `sid` or `X-Society-Id`)

| Method | Path | Rule |
|---|---|---|
| POST | `/v1/uploads` | Any member of the active society. Purpose limits content type and size (400 `CONTENT_TYPE_NOT_ALLOWED`, `FILE_TOO_LARGE`, `EMPTY_FILE`, `INVALID_PURPOSE`) |
| POST | `/v1/uploads/{id}/complete` | Uploader only; 409 `OBJECT_NOT_UPLOADED`, `UPLOAD_WINDOW_EXPIRED` |
| GET | `/v1/media/{id}` | Metadata |
| GET | `/v1/media/{id}/download-url` | Signed GET URL; 409 `MEDIA_NOT_READY` until scanned |
| GET | `/v1/media/{id}/content` | 302 to the signed URL (for `<img src>`) |
| DELETE | `/v1/media/{id}` | Uploader or `media:manage`; deletes the objects and thumbnails, keeps a `DELETED` tombstone |

Access: row-level security limits every lookup to the caller's society, so another society's id
returns 404. Private purposes (`KYC_DOCUMENT`) are readable only by the uploader or `media:manage`.

## Purposes

| Purpose | Owner | Types | Max | Retention |
|---|---|---|---|---|
| VISITOR_PHOTO | security | jpeg/png/webp | 5 MB | society `visitorRetentionDays` (default 180 d) |
| STAFF_PHOTO | society | images | 5 MB | until deleted |
| KYC_DOCUMENT (private) | society | images, pdf | 10 MB | until deleted |
| COMPLAINT_PHOTO | ticket | images | 10 MB | until deleted |
| JOBCARD_EVIDENCE | ticket | images, mp4 | 50 MB | until deleted |
| ASSET_PHOTO / ASSET_DOCUMENT | asset | images / images+pdf | 10 / 20 MB | until deleted |
| RECEIPT_PDF | billing | pdf | 5 MB | until deleted |
| NOTICE_ATTACHMENT | community | images, pdf | 10 MB | until deleted |
| COMPLIANCE_DOCUMENT | compliance | images, pdf | 25 MB | until deleted |
| CHECKLIST_EVIDENCE | utility | images | 10 MB | until deleted |
| AVATAR | identity | images | 2 MB | until deleted |

Object keys are `<societyId>/<purpose>/<yyyy>/<MM>/<mediaId>`, so IAM can scope by society prefix.

## Events

Publishes on `sos.media.events.v1`: `media.file.uploaded`, `media.file.processed`, `media.file.rejected`
(as in the catalogue) and `media.file.deleted {mediaId, ownerService, purpose, reason: OWNER_DELETE|RETENTION}`
(not in the catalogue yet). No file names or personal data go into events.

Consumes `society.settings.updated` (group `media.society-settings`, DLQ `sos.dlq.media.society-settings`)
to keep `visitorRetentionDays`.

## Jobs (db-scheduler, ADR-0004)

- `media-process`: every `sos.media.process-poll` (5 s): scan and thumbnail UPLOADED files.
- `media-retention-purge`: nightly (`sos.media.retention-cron`, 02:45 IST): PENDING slots past their
  window become EXPIRED; READY files past `retain_until` are deleted from storage and tombstoned.

Both find societies through the SECURITY DEFINER function `media_societies_with_work()` and then open
one RLS-scoped transaction per society and file.

## Configuration (`sos.media.*`)

`bucket`, `endpoint` (MinIO `http://localhost:9000`; empty on AWS), `public-endpoint` (the host written
into presigned URLs, if clients reach storage on a different address), `region`, `access-key`/`secret-key`
(empty = AWS default chain / IRSA), `path-style`, `create-bucket` (local only), `upload-ttl`, `download-ttl`,
`process-poll`, `retention-cron`, `thumbnail-edge`.

Locally: `docker compose --profile object-storage up minio` in `infra/docker`.

## Not done yet

- The virus scanner is a stub (`StubVirusScanner` flags the EICAR test string). Wire ClamAV (clamd INSTREAM)
  as another `VirusScanner` bean.
- EXIF stripping of the original and video transcoding (Phase 2). Thumbnails are re-encoded, so they carry no EXIF.
- Completion is client-driven (`/complete`). An S3 event notification can call the same use case later.
- The sync permission check against the owning service (doc 01, dependency rules) is replaced by the purpose rules above.

## Tests

```bash
./mvnw test                   # domain rules, thumbnailer, scanner stub, ArchUnit
./mvnw verify -Pintegration   # + Postgres (RLS), Kafka, Redis and S3Mock (S3-compatible) containers
```

`MediaFlowIntegrationTest` covers presign → PUT → complete → scan/thumbnail → signed download, isolation
between societies, private purposes, limits, EICAR rejection, delete, and retention driven by `society.settings.updated`.
