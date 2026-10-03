import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { LoginForm } from "./login-form";

const session = { userId: "u1", platformAdmin: false, mfaSetupRequired: false, societies: [], roles: [], permissions: [] };

function problem(status: number, code: string, detail: string) {
  return new Response(JSON.stringify({ status, code, title: code, detail }), { status, headers: { "content-type": "application/problem+json" } });
}

afterEach(() => vi.unstubAllGlobals());

describe("admin LoginForm", () => {
  it("validates before calling the server", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    render(<LoginForm onSignedIn={vi.fn()} />);
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));
    expect(await screen.findByText("Enter your e-mail")).toBeInTheDocument();
    expect(screen.getByLabelText(/E-mail/)).toHaveAttribute("aria-invalid", "true");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("asks for the TOTP when identity answers MFA_REQUIRED, then signs in", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(problem(401, "MFA_REQUIRED", "Enter the code from your authenticator app"))
      .mockResolvedValueOnce(new Response(JSON.stringify({ session, newUser: false }), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    const onSignedIn = vi.fn();
    render(<LoginForm onSignedIn={onSignedIn} />);

    await userEvent.type(screen.getByLabelText(/E-mail/), "admin@societyos.in");
    await userEvent.type(screen.getByLabelText(/Password/), "ChangeMe!2026");
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));

    const code = await screen.findByLabelText(/Authenticator code/);
    await waitFor(() => expect(code).toHaveFocus());
    await userEvent.type(code, "123456");
    await userEvent.click(screen.getByRole("button", { name: "Verify and sign in" }));

    await waitFor(() => expect(onSignedIn).toHaveBeenCalledTimes(1));
    const body = JSON.parse(fetchMock.mock.calls[1]![1].body as string);
    expect(body).toEqual({ email: "admin@societyos.in", password: "ChangeMe!2026", totp: "123456" });
    expect(fetchMock.mock.calls[1]![0]).toBe("/api/auth/login");
  });

  it("shows the problem+json message for wrong credentials", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(problem(401, "INVALID_CREDENTIALS", "E-mail or password is not correct")));
    render(<LoginForm onSignedIn={vi.fn()} />);
    await userEvent.type(screen.getByLabelText(/E-mail/), "admin@societyos.in");
    await userEvent.type(screen.getByLabelText(/Password/), "wrong");
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("E-mail or password is not correct");
    expect(alert).toHaveTextContent("INVALID_CREDENTIALS");
  });
});
