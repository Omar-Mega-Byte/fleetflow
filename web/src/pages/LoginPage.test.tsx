import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";

const { signInMock } = vi.hoisted(() => ({
  signInMock: vi.fn().mockResolvedValue(undefined),
}));
vi.mock("../auth/AuthContext", () => ({
  useAuth: () => ({ status: "unauthenticated", signIn: signInMock }),
}));
import { LoginPage } from "./LoginPage";

describe("login accessibility", () => {
  it("supports keyboard focus and Enter activation on the sign-in control", async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <LoginPage />
      </MemoryRouter>,
    );
    const button = screen.getByRole("button", { name: "Continue to sign in" });
    await user.tab();
    expect(button).toHaveFocus();
    await user.keyboard("{Enter}");
    expect(signInMock).toHaveBeenCalledOnce();
  });
});
