import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { AddMemberForm } from "./add-member-form";
import { nav, visible } from "./test-nav";

const flats = [
  { id: "f1", label: "A-101" },
  { id: "f2", label: "A-102" },
];

describe("AddMemberForm", () => {
  it("normalises the phone to E.164 and submits the member body", async () => {
    const onSubmit = vi.fn();
    render(<AddMemberForm flats={flats} defaultFlatId="f2" onSubmit={onSubmit} />);
    await userEvent.type(screen.getByLabelText(/Name/), "Ravi Kumar");
    await userEvent.type(screen.getByLabelText(/Mobile number/), "098765 43210");
    await userEvent.selectOptions(screen.getByLabelText(/Kind/), "TENANT");
    await userEvent.click(screen.getByLabelText(/Primary contact/));
    await userEvent.click(screen.getByRole("button", { name: "Add resident" }));
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit.mock.calls[0]![0]).toEqual({ flatId: "f2", name: "Ravi Kumar", phone: "+919876543210", kind: "TENANT", isPrimary: true });
  });

  it("rejects an invalid phone number", async () => {
    const onSubmit = vi.fn();
    render(<AddMemberForm flats={flats} onSubmit={onSubmit} />);
    await userEvent.type(screen.getByLabelText(/Name/), "Ravi");
    await userEvent.type(screen.getByLabelText(/Mobile number/), "12345");
    await userEvent.click(screen.getByRole("button", { name: "Add resident" }));
    expect(await screen.findByText(/Enter a valid mobile number/)).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });
});

describe("sidebar permissions", () => {
  it("shows only sections the user holds a permission for", () => {
    expect(visible(["society:view"], false)).toEqual(["society"]);
    expect(nav(["society:view"], false)).toContain("/society/towers-flats");
    expect(nav(["society:view"], false)).not.toContain("/society/imports");
    expect(visible([], true)).toEqual(["platform"]);
    expect(visible(["member:manage", "import:run", "audit:view"], false)).toEqual(["society", "governance"]);
  });
});
