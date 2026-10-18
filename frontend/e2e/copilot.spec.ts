import { expect, test } from "@playwright/test";
import type { Page } from "@playwright/test";
test.describe("real local Llama copilot", () => {
  // Test-only credentials observed from actual authenticated browser requests stay in memory.
  // This works against built nginx/Ingress as well as Vite without a production debug export.
  const observedTokens = new WeakMap<Page, string>();
  test.beforeEach(async ({page}) => {
    page.on("request", request => {
      const authorization = request.headers()["authorization"];
      if (request.url().includes("/api/v1/") && authorization?.startsWith("Bearer ")) {
        observedTokens.set(page, authorization);
      }
    });
  });
  test.skip(
    process.env.AI_ENABLED !== "true",
    "Run with AI_ENABLED=true and prepared local models; baseline commerce profile is separate.",
  );
  test.setTimeout(240000);
  async function login(page: Page, user = "alice") {
    await page.goto("/");
    await page
      .getByRole("button", { name: "Sign in", exact: false })
      .first()
      .click();
    await page.getByLabel("Username").fill(user);
    await page
      .getByLabel("Password", { exact: true })
      .fill(`local-${user}-only`);
    await page.getByRole("button", { name: "Sign In", exact: true }).click();
    await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
    await page.getByRole("button", { name: "Local copilot" }).click();
  }
  async function ask(page: Page, text: string) {
    const panel = page.locator("#copilot-panel");
    await panel.getByLabel("Ask the copilot").fill(text);
    await panel
      .getByRole("button", { name: "Send message", exact: true })
      .click();
    return panel;
  }
  async function api(page: Page, path: string, body?: unknown) {
    await expect.poll(() => observedTokens.has(page)).toBe(true);
    const response = await page.request.fetch("/api/v1" + path, {
      method: body === undefined ? "GET" : "POST",
      headers: {Authorization: observedTokens.get(page)!, "Idempotency-Key": crypto.randomUUID()},
      data: body,
    });
    expect(response.ok()).toBe(true);
    return response.json();
  }
  test("cited answer, confirmed action, stale/rejected proposals and owned decline explanation through PKCE/gateway", async ({
    page,
  }) => {
    await login(page);
    const panel = page.locator("#copilot-panel");
    await ask(page, "What is the synthetic demo returns policy window?");
    await expect(
      panel.locator(".copilot-messages .assistant").last(),
    ).toContainText("30", { timeout: 90000 });
    await expect(panel.locator(".copilot-citation").first()).toBeVisible();
    await ask(page, "Compare actual Workspace products priced below 150 USD using current catalog evidence.");
    const comparisonConversation = await panel.getByRole("combobox", {name: /Conversation/}).inputValue();
    await expect.poll(async () => {
      const history = await api(page, `/ai/conversations/${comparisonConversation}/messages?size=100`);
      const last = history.items.at(-1);
      const sources = (last?.role === "ASSISTANT" ? last.citations : []) as {sourceId: string}[];
      return new Set(sources.filter(source => source.sourceId.startsWith("product/")).map(source => source.sourceId)).size;
    }, {timeout: 90000}).toBeGreaterThanOrEqual(2);

    const products = await (
      await page.request.get("/api/v1/products?size=100")
    ).json();
    const product = products.items.find(
      (p: { sku: string }) => p.sku === "BAG-01",
    );
    expect(product).toBeTruthy();
    const before = await api(page, "/cart");
    const quantity = (cart: {
      items: { productId: string; quantity: number }[];
    }) =>
      cart.items.find((item) => item.productId === product.id)?.quantity || 0;
    await ask(
      page,
      `Prepare an ADD cart proposal for exactly 2 units of product UUID ${product.id}. Do not confirm it.`,
    );
    await expect(
      panel.getByRole("button", { name: "Confirm exact change" }),
    ).toBeEnabled({ timeout: 90000 });
    expect(quantity(await api(page, "/cart"))).toBe(quantity(before));
    await panel.getByRole("button", { name: "Confirm exact change" }).click();
    await expect
      .poll(async () => quantity(await api(page, "/cart")))
      .toBe(quantity(before) + 2);
    await expect(panel.locator(".copilot-proposal").last()).toContainText(
      "SUCCEEDED",
    );
    await ask(
      page,
      `Prepare an ADD cart proposal for exactly 1 unit of product UUID ${product.id}. Do not confirm it.`,
    );
    await expect(
      panel.getByRole("button", { name: "Confirm exact change" }),
    ).toBeEnabled({ timeout: 90000 });
    await api(page, "/cart/items", { productId: product.id, quantity: 1 });
    await panel.getByRole("button", { name: "Confirm exact change" }).click();
    await expect(panel.locator(".copilot-proposal").last()).toContainText(
      "FAILED",
      { timeout: 10000 },
    );
    expect(quantity(await api(page, "/cart"))).toBe(quantity(before) + 3);
    await ask(
      page,
      `Prepare an ADD cart proposal for exactly 1 unit of product UUID ${product.id}. Do not confirm it.`,
    );
    await expect(
      panel.getByRole("button", { name: "Reject", exact: true }),
    ).toBeEnabled({ timeout: 90000 });
    await panel.getByRole("button", { name: "Reject", exact: true }).click();
    await expect(panel.locator(".copilot-proposal").last()).toContainText(
      "REJECTED",
    );
    expect(quantity(await api(page, "/cart"))).toBe(quantity(before) + 3);
    const order = await api(page, "/orders", {
      items: [{ productId: product.id, quantity: 1 }],
      paymentScenario: "DECLINE",
    });
    await expect
      .poll(async () => (await api(page, `/orders/${order.id}`)).status, {
        timeout: 20000,
      })
      .toBe("CANCELLED");
    await ask(
      page,
      `Why was my order UUID ${order.id} cancelled? Use its actual order explanation.`,
    );
    await expect(
      panel.locator(".copilot-messages .assistant").last(),
    ).toContainText(/declin/i, { timeout: 90000 });
    await page.setViewportSize({ width: 390, height: 844 });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true);
    await page.screenshot({
      path: "../.cache/verification/ai-copilot-mobile.png",
      fullPage: true,
    });
    await page.getByRole("button", { name: "Sign out" }).click();
    await expect(page.locator("#copilot-panel")).toHaveCount(0);
  });
  test("support sees scoped investigation mode and no cart confirmation controls", async ({
    page,
  }) => {
    await login(page, "support");
    const panel = page.locator("#copilot-panel");
    await expect(panel.getByLabel("Mode")).toHaveValue("SUPPORT");
    await expect(
      panel.getByRole("option", { name: "My shopping" }),
    ).toHaveCount(0);
    await ask(
      page,
      "Use searchKnowledge to explain how support investigates a demo cancellation.",
    );
    await expect(
      panel.locator(".copilot-messages .assistant").last(),
    ).toContainText(/order|cancell|reason/i, { timeout: 90000 });
    await expect(
      panel.getByRole("button", { name: "Confirm exact change" }),
    ).toHaveCount(0);
  });
  test("a different account cannot see another customer conversation", async ({
    page,
  }) => {
    await login(page, "bob");
    const panel = page.locator("#copilot-panel");
    await expect(panel.getByLabel("Ask the copilot")).toBeVisible();
    const history = await api(page, "/ai/conversations");
    expect(history.items).toHaveLength(0);
    await expect(panel.locator(".copilot-messages li")).toHaveCount(0);
  });

  test("saved history and SSE replay survive a page reload without another message", async ({
    page,
  }) => {
    await login(page);
    const histories = await api(page, "/ai/conversations");
    expect(histories.items.length).toBeGreaterThan(0);
    const id = histories.items[0].id;
    const original = await api(
      page,
      `/ai/conversations/${id}/messages?size=100`,
    );
    await page
      .locator("#copilot-panel")
      .getByRole("combobox", {name: /Conversation/})
      .selectOption(id);
    await expect(page.locator(".copilot-messages li")).toHaveCount(
      original.items.length,
    );
    await page
      .locator("#copilot-panel")
      .getByRole("button", { name: "Reconnect to saved result" })
      .click();
    await expect
      .poll(
        async () =>
          (await api(page, `/ai/conversations/${id}/messages?size=100`))
            .totalElements,
      )
      .toBe(original.totalElements);
    await page.reload();
    await page.getByRole("button", { name: "Local copilot" }).click();
    await page
      .locator("#copilot-panel")
      .getByRole("combobox", {name: /Conversation/})
      .selectOption(id);
    await expect(page.locator(".copilot-messages li")).toHaveCount(
      original.items.length,
    );
  });
});
