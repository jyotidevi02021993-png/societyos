import { EmptyState, PageHeader } from "@societyos/ui";

export default function NoAccess() {
  return (
    <>
      <PageHeader title="No access yet" />
      <EmptyState
        title="You have no permissions in this society"
        description="Pick another society from the switcher, or ask the society administrator to assign you a role."
      />
    </>
  );
}
