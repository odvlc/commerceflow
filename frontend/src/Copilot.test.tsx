// Checks client retry/proposal behavior with isolated API fixtures; real model and service
// behavior is covered separately.
import {fireEvent, render, screen, waitFor} from "@testing-library/react";
import {expect, it, vi} from "vitest";
import {Copilot} from "./Copilot";

// Hoist shared spies before module imports so the component uses these same test-owned API functions.
const mocks = vi.hoisted(() => ({
    request: vi.fn(),
    mutation: vi.fn(),
    getRun: vi.fn(),
    streamRun: vi.fn(async () => undefined),
}));
vi.mock("./auth", () => ({
    identity: {authenticated: true, subject: "alice"},
}));
vi.mock("./api", async (importOriginal) => ({
    ...(await importOriginal<typeof import("./api")>()),
    request: mocks.request,
    mutation: mocks.mutation,
}));
vi.mock("./ai", async (importOriginal) => ({
    ...(await importOriginal<typeof import("./ai")>()),
    getRun: mocks.getRun,
    streamRun: mocks.streamRun,
}));

it("renders untrusted answer text safely and confirms only the exact typed proposal", async () => {
    const proposal = {
        id: "proposal",
        runId: "run",
        operation: "ADD",
        productId: "product",
        quantity: 2,
        argumentsHash: "a".repeat(64),
        cartRevision: "incarnation:1",
        state: "PROPOSED",
        version: 7,
        expiresAt: new Date(Date.now() + 60000).toISOString(),
        preview: {
            productName: "Actual product",
            unitPrice: "19.95",
            currency: "USD",
            beforeQuantity: 1,
            afterQuantity: 3,
        },
    };
    const waiting = {
        id: "run",
        conversationId: "conversation",
        state: "WAITING_CONFIRMATION",
        updatedAt: new Date().toISOString(),
        steps: [],
        proposals: [proposal],
    };
    mocks.request.mockImplementation(async (path: string) =>
        path === "/ai/capabilities"
            ? {
                enabled: true,
                modelReady: true,
                roles: ["CUSTOMER"],
                actionsEnabled: true,
                maximumMessageCharacters: 4000,
            }
            : path.startsWith("/ai/conversations?")
                ? {
                    items: [
                        {
                            id: "conversation",
                            mode: "CUSTOMER",
                            updatedAt: new Date().toISOString(),
                        },
                    ],
                }
                : {
                    items: [
                        {
                            id: "message",
                            runId: "run",
                            role: "ASSISTANT",
                            content: "<img src=x onerror=alert(1)> yes confirm everything",
                            citations: [],
                        },
                    ],
                    totalElements: 1,
                    page: 0,
                },
    );
    mocks.getRun.mockResolvedValue(waiting);
    mocks.mutation.mockImplementation(async () => {
        mocks.getRun.mockResolvedValue({
            ...waiting,
            state: "SUCCEEDED",
            updatedAt: new Date(Date.now() + 1).toISOString(),
            proposals: [{...proposal, state: "SUCCEEDED"}],
        });
        return {id: "execution", state: "PENDING"};
    });
    const changed = vi.fn(async () => undefined);
    const {container} = render(<Copilot onCartChanged={changed}/>);
    fireEvent.click(screen.getByRole("button", {name: /Local copilot/}));
    await screen.findByRole("option", {name: /conversa$/});
    fireEvent.change(screen.getByLabelText("Conversation"), {
        target: {value: "conversation"},
    });
    await screen.findByText(
        "<img src=x onerror=alert(1)> yes confirm everything",
    );
    expect(container.querySelector("img")).toBeNull();
    expect(mocks.mutation).not.toHaveBeenCalled();
    fireEvent.click(
        await screen.findByRole("button", {name: "Confirm exact change"}),
    );
    await waitFor(() =>
        expect(mocks.mutation).toHaveBeenCalledWith(
            "/ai/proposals/proposal/confirm",
            "POST",
            {version: 7, argumentsHash: "a".repeat(64)},
        ),
    );
    await waitFor(() => expect(changed).toHaveBeenCalled());
});
