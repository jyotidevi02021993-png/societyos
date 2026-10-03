"use client";

import * as React from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { MapPin, Pencil, Plus } from "lucide-react";
import { LOCATION_KINDS, type Location } from "@societyos/api-client";
import { qk, useApi } from "@societyos/api-client/react";
import { Badge, Button, Card, CardContent, Dialog, EmptyState, Field, Input, Select } from "@societyos/ui";
import { ProblemAlert, QueryState, applyServerFieldErrors, humanize, usePermissions } from "@societyos/shared";
import { SocietyPage, useTowers } from "@/components/society-page";

const locationSchema = z.object({
  kind: z.enum(LOCATION_KINDS),
  name: z.string().trim().min(1, "Enter a name").max(100),
  towerId: z.string(),
  parentId: z.string(),
});

interface TreeNode {
  loc: Location;
  children: TreeNode[];
}

function buildTree(list: Location[]): TreeNode[] {
  const nodes = new Map(list.map((l) => [l.id, { loc: l, children: [] as TreeNode[] }]));
  const roots: TreeNode[] = [];
  for (const n of nodes.values()) {
    const parent = n.loc.parentId ? nodes.get(n.loc.parentId) : undefined;
    (parent ? parent.children : roots).push(n);
  }
  const sort = (ns: TreeNode[]) => {
    ns.sort((a, b) => a.loc.name.localeCompare(b.loc.name));
    ns.forEach((n) => sort(n.children));
  };
  sort(roots);
  return roots;
}

/** Ids of a node and all its descendants (a location cannot be moved under itself). */
function subtreeIds(list: Location[], id: string): Set<string> {
  const out = new Set([id]);
  let grew = true;
  while (grew) {
    grew = false;
    for (const l of list) {
      if (l.parentId && out.has(l.parentId) && !out.has(l.id)) {
        out.add(l.id);
        grew = true;
      }
    }
  }
  return out;
}

export default function LocationsPage() {
  const api = useApi();
  const { can, activeSocietyId } = usePermissions();
  const canManage = can("society:manage");
  const towers = useTowers();
  const list = useQuery({ queryKey: qk.locations(activeSocietyId), queryFn: () => api.society.locations.list(), enabled: !!activeSocietyId });
  const [editing, setEditing] = React.useState<{ location?: Location; parentId?: string } | null>(null);
  const towerName = new Map((towers.data ?? []).map((t) => [t.id, t.name]));
  const tree = React.useMemo(() => buildTree(list.data ?? []), [list.data]);

  const renderNodes = (nodes: TreeNode[], level: number): React.ReactNode => (
    <ul role={level === 1 ? "tree" : "group"} aria-label={level === 1 ? "Locations" : undefined} className={level > 1 ? "ml-5 border-l pl-3" : "grid gap-1"}>
      {nodes.map((n) => (
        <li key={n.loc.id} role="treeitem" aria-level={level} aria-expanded={n.children.length ? true : undefined} aria-selected={false} className="py-0.5">
          <div className="flex flex-wrap items-center gap-2 rounded-md px-2 py-1 hover:bg-muted/50">
            <MapPin className="size-4 text-muted-foreground" aria-hidden="true" />
            <span className="font-medium">{n.loc.name}</span>
            <Badge variant="secondary">{humanize(n.loc.kind)}</Badge>
            {n.loc.towerId ? <Badge variant="outline">{towerName.get(n.loc.towerId) ?? "Tower"}</Badge> : null}
            {canManage ? (
              <span className="ml-auto flex gap-1">
                <Button size="sm" variant="ghost" onClick={() => setEditing({ parentId: n.loc.id })} aria-label={`Add location inside ${n.loc.name}`}>
                  <Plus aria-hidden="true" /> Inside
                </Button>
                <Button size="sm" variant="ghost" onClick={() => setEditing({ location: n.loc })} aria-label={`Edit ${n.loc.name}`}>
                  <Pencil aria-hidden="true" />
                </Button>
              </span>
            ) : null}
          </div>
          {n.children.length ? renderNodes(n.children, level + 1) : null}
        </li>
      ))}
    </ul>
  );

  return (
    <SocietyPage
      title="Locations"
      description="Plant rooms, gates, basements and common areas: where assets live and work happens."
      anyOf={["society:view"]}
      actions={
        canManage ? (
          <Button onClick={() => setEditing({})}>
            <Plus aria-hidden="true" /> Location
          </Button>
        ) : null
      }
    >
      <QueryState isLoading={list.isLoading} error={list.error} onRetry={() => void list.refetch()} />
      {list.data?.length === 0 ? <EmptyState icon={MapPin} title="No locations" description="Add pump rooms, gates, the STP, terraces…" /> : null}
      {tree.length ? (
        <Card>
          <CardContent className="pt-5">{renderNodes(tree, 1)}</CardContent>
        </Card>
      ) : null}
      {editing ? (
        <LocationDialog
          location={editing.location}
          parentId={editing.parentId}
          all={list.data ?? []}
          towers={towers.data ?? []}
          onClose={() => setEditing(null)}
        />
      ) : null}
    </SocietyPage>
  );
}

function LocationDialog({
  location,
  parentId,
  all,
  towers,
  onClose,
}: {
  location?: Location;
  parentId?: string;
  all: Location[];
  towers: { id: string; name: string }[];
  onClose: () => void;
}) {
  const api = useApi();
  const qc = useQueryClient();
  const { activeSocietyId } = usePermissions();
  const form = useForm<z.infer<typeof locationSchema>>({
    resolver: zodResolver(locationSchema),
    defaultValues: {
      kind: location?.kind ?? "COMMON_AREA",
      name: location?.name ?? "",
      towerId: location?.towerId ?? all.find((l) => l.id === parentId)?.towerId ?? "",
      parentId: location?.parentId ?? parentId ?? "",
    },
  });
  const excluded = location ? subtreeIds(all, location.id) : new Set<string>();
  const parents = all.filter((l) => !excluded.has(l.id)).sort((a, b) => a.name.localeCompare(b.name));
  const save = useMutation({
    mutationFn: (v: z.infer<typeof locationSchema>) => {
      const body = { kind: v.kind, name: v.name, towerId: v.towerId || null, parentId: v.parentId || null };
      return location ? api.society.locations.update(location.id, body) : api.society.locations.create(body);
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: qk.locations(activeSocietyId) });
      onClose();
    },
    onError: (err) => applyServerFieldErrors(err, form.setError, ["kind", "name", "towerId", "parentId"]),
  });
  const e = form.formState.errors;
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={location ? `Edit ${location.name}` : "Add location"}>
      <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="grid gap-4" noValidate>
        {save.error ? <ProblemAlert error={save.error} /> : null}
        <Field id="loc-name" label="Name" required error={e.name?.message}>
          {(a) => <Input {...a} placeholder="Tower A pump room" {...form.register("name")} />}
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field id="loc-kind" label="Kind" required>
            {(a) => (
              <Select {...a} {...form.register("kind")}>
                {LOCATION_KINDS.map((k) => (
                  <option key={k} value={k}>
                    {humanize(k)}
                  </option>
                ))}
              </Select>
            )}
          </Field>
          <Field id="loc-tower" label="Tower">
            {(a) => (
              <Select {...a} {...form.register("towerId")}>
                <option value="">Society-wide</option>
                {towers.map((t) => (
                  <option key={t.id} value={t.id}>
                    {t.name}
                  </option>
                ))}
              </Select>
            )}
          </Field>
        </div>
        <Field id="loc-parent" label="Inside" hint="Optional parent location, e.g. Basement 1 → Pump room">
          {(a) => (
            <Select {...a} {...form.register("parentId")}>
              <option value="">Top level</option>
              {parents.map((l) => (
                <option key={l.id} value={l.id}>
                  {l.name}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" loading={save.isPending}>
            {location ? "Save" : "Add location"}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
