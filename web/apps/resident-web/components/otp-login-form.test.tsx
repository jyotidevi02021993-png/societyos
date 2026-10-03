import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { OtpLoginForm } from "./otp-login-form";

const session = { userId: "u1", platformAdmin: false, mfaSetupRequired: false, societies: [], roles: [], permissions: [] };
const ok = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { "content-type": "application/json" } });

afterEach(() => vi.unstubAllGlobals());

describe("resident OtpLoginForm", () => {
  it("requests a code, then verifies it and signs in", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(ok({ phone: "+919876543210", expiresInSeconds: 300 }))
      .mockResolvedValueOnce(ok({ session, newUser: true }));
    vi.stubGlobal("fetch", fetchMock);
    const onSignedIn = vi.fn();
    render(<OtpLoginForm onSignedIn={onSignedIn} />);

    await userEvent.type(screen.getByLabelText(/Mobile number/), "98765 43210");
    await userEvent.click(screen.getByRole("button", { name: "Send code" }));
    expect(JSON.parse(fetchMock.mock.calls[0]![1].body as string)).toEqual({ phone: "98765 43210" });

    const code = await screen.findByLabelText(/One-time code/);
    expect(screen.getByText(/\+91 98••••••10/)).toBeInTheDocument();
    await userEvent.type(code, "123456");
    await userEvent.click(screen.getByRole("button", { name: "Verify and sign in" }));
    await waitFor(() => expect(onSignedIn).toHaveBeenCalledWith(session));
    expect(fetchMock.mock.calls[1]![0]).toBe("/api/auth/otp/verify");
    expect(JSON.parse(fetchMock.mock.calls[1]![1].body as string)).toEqual({ phone: "+919876543210", code: "123456" });
  });

  it("shows a wrong-code error on the field", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(ok({ phone: "+919876543210", expiresInSeconds: 300 }))
        .mockResolvedValueOnce(new Response(JSON.stringify({ status: 401, code: "OTP_INVALID", detail: "The code is not correct" }), { status: 401 })),
    );
    render(<OtpLoginForm onSignedIn={vi.fn()} />);
    await userEvent.type(screen.getByLabelText(/Mobile number/), "9876543210");
    await userEvent.click(screen.getByRole("button", { name: "Send code" }));
    await userEvent.type(await screen.findByLabelText(/One-time code/), "000000");
    await userEvent.click(screen.getByRole("button", { name: "Verify and sign in" }));
    expect(await screen.findByText("The code is not correct")).toBeInTheDocument();
    expect(screen.getByLabelText(/One-time code/)).toHaveAttribute("aria-invalid", "true");
  });

  it("does not call the server for an invalid number", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    render(<OtpLoginForm onSignedIn={vi.fn()} />);
    await userEvent.type(screen.getByLabelText(/Mobile number/), "12345");
    await userEvent.click(screen.getByRole("button", { name: "Send code" }));
    expect(await screen.findByText(/valid 10-digit mobile number/)).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
