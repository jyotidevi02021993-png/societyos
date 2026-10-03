"use client";

import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Download, FileSpreadsheet, Upload } from "lucide-react";
import { IMPORT_TERMINAL, type ImportJob, type ImportStatus } from "@societyos/api-client";
import { qk, useApi } from "@societyos/api-client/react";
import { Alert, Badge, Button, Card, CardContent, CardDescription, CardHeader, CardTitle, EmptyState, Field, Input, Spinner, Switch, Table, TBody, TD, TH, THead, TR } from "@societyos/ui";
import { ProblemAlert, QueryState, formatDateTime, humanize, usePermissions } from "@societyos/shared";
import { SocietyPage } from "@/components/society-page";

const statusVariant: Record<ImportStatus, "secondary" | "default" | "destructive" | "success" | "warning"> = {
  PENDING: "secondary",
  RUNNING: "default",
  VALIDATION_FAILED: "destructive",
  COMPLETED: "success",
  COMPLETED_WITH_ERRORS: "warning",
  FAILED: "destructive",
};

const isTerminal = (s?: ImportStatus) => !!s && IMPORT_TERMINAL.includes(s);
const MAX_BYTES = 10 * 1024 * 1024;

export default function ImportsPage() {
  const api = useApi();
  const qc = useQueryClient();
  const { activeSocietyId } = usePermissions();
  const [file, setFile] = React.useState<File | null>(null);
  const [fileError, setFileError] = React.useState<string | null>(null);
  const [dryRun, setDryRun] = React.useState(true);
  const [currentId, setCurrentId] = React.useState<string | null>(null);
  const inputRef = React.useRef<HTMLInputElement>(null);

  const history = useQuery({ queryKey: qk.imports(activeSocietyId), queryFn: () => api.society.imports.list(), enabled: !!activeSocietyId });

  const current = useQuery({
    queryKey: qk.importJob(activeSocietyId, currentId ?? "none"),
    queryFn: () => api.society.imports.get(currentId!),
    enabled: !!currentId,
    // Poll every 2 s until the job reaches a final state.
    refetchInterval: (q) => (isTerminal(q.state.data?.status) ? false : 2000),
  });

  React.useEffect(() => {
    if (isTerminal(current.data?.status)) {
      void qc.invalidateQueries({ queryKey: qk.imports(activeSocietyId) });
      if (current.data?.status === "COMPLETED" || current.data?.status === "COMPLETED_WITH_ERRORS") {
        if (!current.data.dryRun) void qc.invalidateQueries({ queryKey: qk.societyRoot(activeSocietyId) });
      }
    }
  }, [current.data?.status, current.data?.dryRun, qc, activeSocietyId]);

  const upload = useMutation({
    mutationFn: ({ f, dry }: { f: File; dry: boolean }) => api.society.imports.upload(f, dry),
    onSuccess: (job) => {
      setCurrentId(job.id);
      qc.setQueryData(qk.importJob(activeSocietyId, job.id), job);
      void qc.invalidateQueries({ queryKey: qk.imports(activeSocietyId) });
    },
  });

  const pick = (f: File | null) => {
    setFileError(null);
    if (f && !/\.xlsx$/i.test(f.name)) {
      setFileError("Choose an Excel .xlsx file.");
      setFile(null);
      return;
    }
    if (f && f.size > MAX_BYTES) {
      setFileError("The file is larger than 10 MB.");
      setFile(null);
      return;
    }
    setFile(f);
  };

  const job = current.data;

  return (
    <SocietyPage
      title="Excel import"
      description="Load towers, flats and residents in bulk. Validate first, then run it for real."
      anyOf={["import:run"]}
      actions={
        <Button variant="outline" asChild>
          <a href={api.society.imports.templateUrl()} download>
            <Download aria-hidden="true" /> Template
          </a>
        </Button>
      }
    >
      <div className="grid gap-6">
        <Card>
          <CardHeader>
            <CardTitle>Upload</CardTitle>
            <CardDescription>The workbook needs the sheets Towers, Flats and Residents (see the template).</CardDescription>
          </CardHeader>
          <CardContent>
            <form
              className="grid gap-4"
              onSubmit={(e) => {
                e.preventDefault();
                if (!file) {
                  setFileError("Choose a file to upload.");
                  return;
                }
                upload.mutate({ f: file, dry: dryRun });
              }}
              noValidate
            >
              <Field id="import-file" label="Workbook (.xlsx)" required error={fileError ?? undefined}>
                {(a) => (
                  <Input
                    {...a}
                    ref={inputRef}
                    type="file"
                    accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    onChange={(e) => pick(e.target.files?.[0] ?? null)}
                  />
                )}
              </Field>
              <Switch label="Dry run: only validate, change nothing" checked={dryRun} onChange={(e) => setDryRun(e.target.checked)} />
              {upload.error ? <ProblemAlert error={upload.error} title="Upload failed" /> : null}
              <div>
                <Button type="submit" loading={upload.isPending}>
                  <Upload aria-hidden="true" /> {dryRun ? "Validate" : "Import"}
                </Button>
              </div>
            </form>
          </CardContent>
        </Card>

        {currentId ? (
          <section aria-live="polite" aria-labelledby="current-h">
            <Card>
              <CardHeader>
                <CardTitle id="current-h">
                  {job?.fileName ?? "Import"} {job?.dryRun ? <Badge variant="outline">Dry run</Badge> : null}
                </CardTitle>
              </CardHeader>
              <CardContent className="grid gap-4">
                {current.error ? <ProblemAlert error={current.error} /> : null}
                {job ? <JobResult job={job} onRunForReal={file && job.dryRun && job.status === "COMPLETED" ? () => { setDryRun(false); upload.mutate({ f: file, dry: false }); } : undefined} /> : <Spinner />}
              </CardContent>
            </Card>
          </section>
        ) : null}

        <section aria-labelledby="history-h" className="grid gap-3">
          <h2 id="history-h" className="text-sm font-semibold text-muted-foreground">
            Recent imports
          </h2>
          <QueryState isLoading={history.isLoading} error={history.error} onRetry={() => void history.refetch()} />
          {history.data?.length === 0 ? <EmptyState icon={FileSpreadsheet} title="No imports yet" /> : null}
          {history.data && history.data.length > 0 ? (
            <Card>
              <Table caption="Recent imports">
                <THead>
                  <TR>
                    <TH>File</TH>
                    <TH>Mode</TH>
                    <TH>Status</TH>
                    <TH>Started</TH>
                    <TH>Errors</TH>
                    <TH className="text-right">Report</TH>
                  </TR>
                </THead>
                <TBody>
                  {[...history.data]
                    .sort((a, b) => (b.createdAt ?? "").localeCompare(a.createdAt ?? ""))
                    .map((j) => (
                      <TR key={j.id}>
                        <TD className="font-medium">{j.fileName}</TD>
                        <TD>{j.dryRun ? "Dry run" : "Import"}</TD>
                        <TD>
                          <Badge variant={statusVariant[j.status] ?? "secondary"}>{humanize(j.status)}</Badge>
                        </TD>
                        <TD>{formatDateTime(j.createdAt)}</TD>
                        <TD>{j.report?.errors?.length ?? 0}</TD>
                        <TD className="text-right">
                          <Button size="sm" variant="ghost" onClick={() => setCurrentId(j.id)} aria-label={`Show report for ${j.fileName}`}>
                            View
                          </Button>
                        </TD>
                      </TR>
                    ))}
                </TBody>
              </Table>
            </Card>
          ) : null}
        </section>
      </div>
    </SocietyPage>
  );
}

function JobResult({ job, onRunForReal }: { job: ImportJob; onRunForReal?: () => void }) {
  const running = !isTerminal(job.status);
  const errors = job.report?.errors ?? [];
  return (
    <>
      <div className="flex flex-wrap items-center gap-3">
        <Badge variant={statusVariant[job.status] ?? "secondary"}>{humanize(job.status)}</Badge>
        {running ? (
          <span className="inline-flex items-center gap-2 text-sm text-muted-foreground">
            <Spinner label="" className="size-4" /> Working… this page updates by itself.
          </span>
        ) : (
          <span className="text-sm text-muted-foreground">Finished {formatDateTime(job.finishedAt)}</span>
        )}
      </div>
      {job.report ? (
        <dl className="grid grid-cols-3 gap-3 text-center sm:max-w-md">
          {(["towers", "flats", "residents"] as const).map((k) => (
            <div key={k} className="rounded-md border p-3">
              <dt className="text-xs uppercase tracking-wide text-muted-foreground">{k}</dt>
              <dd className="text-xl font-semibold">{job.report?.[k] ?? 0}</dd>
            </div>
          ))}
        </dl>
      ) : null}
      {job.status === "COMPLETED" && job.dryRun ? (
        <Alert variant="success" title="The workbook is valid">
          Nothing was changed. {onRunForReal ? "Run the import to apply it." : "Upload it again with dry run off to apply it."}
          {onRunForReal ? (
            <div className="mt-2">
              <Button size="sm" onClick={onRunForReal}>
                Run import now
              </Button>
            </div>
          ) : null}
        </Alert>
      ) : null}
      {job.status === "COMPLETED" && !job.dryRun ? <Alert variant="success">Import applied.</Alert> : null}
      {job.status === "FAILED" ? <Alert variant="error">The import failed. Nothing was changed; try again or contact support.</Alert> : null}
      {errors.length > 0 ? (
        <div className="grid gap-2">
          <h3 className="text-sm font-semibold">Validation report ({errors.length})</h3>
          <Table caption="Validation errors">
            <THead>
              <TR>
                <TH>Sheet</TH>
                <TH>Row</TH>
                <TH>Column</TH>
                <TH>Problem</TH>
              </TR>
            </THead>
            <TBody>
              {errors.map((e, i) => (
                <TR key={`${e.sheet}-${e.row}-${e.column ?? ""}-${i}`}>
                  <TD>{e.sheet}</TD>
                  <TD>{e.row}</TD>
                  <TD>{e.column ?? "—"}</TD>
                  <TD>{e.message}</TD>
                </TR>
              ))}
            </TBody>
          </Table>
        </div>
      ) : null}
    </>
  );
}
