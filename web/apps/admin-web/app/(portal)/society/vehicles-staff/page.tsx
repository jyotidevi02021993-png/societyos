"use client";

import * as React from "react";
import { Select } from "@societyos/ui";
import { StaffPanel, VehiclesPanel, usePermissions } from "@societyos/shared";
import { SocietyPage, useFlats } from "@/components/society-page";

export default function VehiclesStaffPage() {
  const { can } = usePermissions();
  const canManage = can("member:manage");
  const flats = useFlats();
  const [flatId, setFlatId] = React.useState("");

  return (
    <SocietyPage
      title="Vehicles & domestic staff"
      description="Choose a flat to add or remove its vehicles and staff. Blocking staff applies at every gate."
      anyOf={["member:manage", "member:view"]}
    >
      <div className="grid gap-4">
        <div className="grid w-56 gap-1">
          <label htmlFor="vs-flat" className="text-xs font-medium">
            Flat
          </label>
          <Select id="vs-flat" value={flatId} onChange={(e) => setFlatId(e.target.value)}>
            <option value="">All flats</option>
            {flats.data?.map((f) => (
              <option key={f.id} value={f.id}>
                {f.label}
              </option>
            ))}
          </Select>
        </div>
        <VehiclesPanel key={`v-${flatId}`} flatId={flatId || undefined} canManage={canManage} />
        <StaffPanel key={`s-${flatId}`} flatId={flatId || undefined} canManage={canManage} canBlock={canManage} />
      </div>
    </SocietyPage>
  );
}
