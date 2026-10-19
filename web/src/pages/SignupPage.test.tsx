import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";

vi.mock("../auth/AuthContext", () => ({
  useAuth: () => ({ status: "unauthenticated" }),
}));
import { SignupPage } from "./SignupPage";

afterEach(() => vi.unstubAllGlobals());

describe("customer signup", () => {
  it("links back to login and creates a customer account without a role field", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ status: 201 });
    vi.stubGlobal("fetch", fetchMock);
    const user = userEvent.setup();
    render(<MemoryRouter><SignupPage /></MemoryRouter>);
    expect(screen.getAllByRole("link", { name: /sign in/i })[0]).toHaveAttribute("href", "/login");
    await user.type(screen.getByRole("textbox", { name: "Your name" }), "Alex Morgan");
    await user.type(screen.getByRole("textbox", { name: "Email" }), "alex@example.com");
    await user.type(screen.getByLabelText("Password"), "a long customer passphrase");
    await user.type(screen.getByLabelText("Confirm password"), "a long customer passphrase");
    await user.click(screen.getByRole("button", { name: "Create customer account" }));
    expect(fetchMock).toHaveBeenCalledOnce();
    const request = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(request[0]).toBe("/api/v1/auth/signup");
    expect(JSON.parse(request[1].body as string)).toEqual({
      displayName: "Alex Morgan", email: "alex@example.com", password: "a long customer passphrase",
    });
    expect(await screen.findByText("You're all set.")).toBeInTheDocument();
  });
});
