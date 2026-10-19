// Verifies duplicate-click and ambiguous-retry guards; these client tests complement server
// idempotency enforcement.
import {afterEach, describe, expect, it, vi} from "vitest";
import {cleanup, fireEvent, render, screen, waitFor,} from "@testing-library/react";
import {CheckoutAction} from "./CheckoutAction";
import {checkoutKey} from "./checkout";

afterEach(cleanup);
describe("checkout retries", () => {
    it("retains the idempotency key across an ambiguous failure and changes it for different contents", () => {
        sessionStorage.clear();
        // The same serialized intent simulates retrying after losing the original HTTP response.
        const key = checkoutKey("customer-a:product-a:1");
        expect(checkoutKey("customer-a:product-a:1")).toBe(key);
        expect(checkoutKey("customer-a:product-a:2")).not.toBe(key);
    });
    it("blocks repeated submissions until the first operation settles", async () => {
        let finish: () => void = () => {
        };
        const action = vi.fn(
            () =>
                new Promise<void>((resolve) => {
                    finish = resolve;
                }),
        );
        render(<CheckoutAction disabled={false} onCheckout={action}/>);
        fireEvent.click(screen.getByRole("button"));
        fireEvent.click(screen.getByRole("button"));
        expect(action).toHaveBeenCalledTimes(1);
        expect(screen.getByRole("button")).toBeDisabled();
        // Keep the promise pending across both clicks, then settle it to prove the guard is released.
        finish();
        await waitFor(() => expect(screen.getByRole("button")).toBeEnabled());
    });
});
