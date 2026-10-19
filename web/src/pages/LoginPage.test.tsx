import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";

const { signInMock, passwordSignInMock } = vi.hoisted(() => ({
  signInMock: vi.fn().mockResolvedValue(undefined),
  passwordSignInMock: vi.fn().mockResolvedValue(undefined),
}));
vi.mock("../auth/AuthContext", () => ({
  useAuth: () => ({ status: "unauthenticated", signIn: signInMock,
    signInWithPassword: passwordSignInMock }),
}));
import { LoginPage } from "./LoginPage";

describe("first-party login", () => {
  it("submits the fields by keyboard and keeps SSO available", async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <LoginPage />
      </MemoryRouter>,
    );
    await user.type(screen.getByRole("textbox", { name: "Email" }), "operator@fleetflow.demo");
    await user.type(screen.getByLabelText("Password"), "example-password");
    await user.keyboard("{Enter}");
    expect(passwordSignInMock).toHaveBeenCalledWith("operator@fleetflow.demo", "example-password");
    await user.click(screen.getByRole("button", { name: "Continue with workspace SSO" }));
    expect(signInMock).toHaveBeenCalledOnce();
    expect(screen.getByRole("link", { name: /create a customer account/i })).toHaveAttribute("href", "/signup");
  });
});
