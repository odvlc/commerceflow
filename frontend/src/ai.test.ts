import { afterEach, describe, expect, it, vi } from "vitest";
vi.mock("./auth", () => ({
  accessToken: vi.fn(async () => "test-header-token"),
}));
import { streamRun } from "./ai";
afterEach(() => vi.unstubAllGlobals());
describe("authenticated copilot stream", () => {
  it("reassembles split UTF-8 frames, ignores heartbeats/duplicates and sends header credentials", async () => {
    const encoded = new TextEncoder().encode(
      ': heartbeat\r\n\r\nid: 4\r\nevent: step\r\ndata: {"text":"café"}\r\n\r\nid: 4\nevent: step\ndata: {}\n\nid: 5\nevent: final\ndata: {"state":"SUCCEEDED"}\n\n',
    );
    const fetcher = vi.fn(
      async (_url: string, _options: RequestInit) =>
        new Response(
          new ReadableStream({
            start(controller) {
              for (let i = 0; i < encoded.length; i += 3)
                controller.enqueue(encoded.slice(i, i + 3));
              controller.close();
            },
          }),
          { status: 200 },
        ),
    );
    vi.stubGlobal("fetch", fetcher);
    const events: unknown[] = [];
    await streamRun("owned-run", 3, new AbortController().signal, (event) =>
      events.push(event),
    );
    expect(events).toEqual([
      { id: 4, type: "step", payload: { text: "café" } },
      { id: 5, type: "final", payload: { state: "SUCCEEDED" } },
    ]);
    const [, options] = fetcher.mock.calls[0] as unknown as [
      string,
      RequestInit,
    ];
    expect(options.headers).toMatchObject({
      Authorization: "Bearer test-header-token",
      "Last-Event-ID": "3",
    });
    expect(fetcher.mock.calls[0]?.[0]).not.toContain("test-header-token");
  });
  it("rejects oversized events instead of buffering without a bound", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(
        async () =>
          new Response("data: " + "x".repeat(129000), { status: 200 }),
      ),
    );
    await expect(
      streamRun("owned-run", 0, new AbortController().signal, () => undefined),
    ).rejects.toThrow("exceeds");
  });
});
