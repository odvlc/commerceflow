import { afterEach, describe, expect, it, vi } from "vitest";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { CheckoutAction } from "./CheckoutAction";
import { checkoutKey } from "./checkout";

afterEach(cleanup);
describe("checkout retries", () => {
  it("retains the idempotency key across an ambiguous failure and changes it for different contents", () => {
    sessionStorage.clear();
    const key = checkoutKey("customer-a:product-a:1");
    expect(checkoutKey("customer-a:product-a:1")).toBe(key);
    expect(checkoutKey("customer-a:product-a:2")).not.toBe(key);
  });
  it("blocks repeated submissions until the first operation settles", async () => {
    let finish: () => void = () => {};
    const action = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve;
        }),
    );
    render(<CheckoutAction disabled={false} onCheckout={action} />);
    fireEvent.click(screen.getByRole("button"));
    fireEvent.click(screen.getByRole("button"));
    expect(action).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button")).toBeDisabled();
    finish();
    await waitFor(() => expect(screen.getByRole("button")).toBeEnabled());
  });
});
