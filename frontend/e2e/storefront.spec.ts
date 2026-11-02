// Tests PKCE login, purchase and decline compensation, and administration in the public storefront.
import {expect, test} from "@playwright/test";

test("a real customer logs in with PKCE, purchases, observes confirmation, and sees decline compensation",
    async ({
               page,
           }) => {
        // Start at the public entry point to exercise browser routing and PKCE login.
        await page.goto("/");
        await expect(
            page.getByRole("heading", {name: "The collection"}),
        ).toBeVisible();
        await page
            .getByRole("button", {name: "Sign in", exact: false})
            .first()
            .click();
        await page.getByLabel("Username").fill("alice");
        await page.getByLabel("Password", {exact: true}).fill("local-alice-only");
        await page.getByRole("button", {name: "Sign In", exact: true}).click();
        await expect(page.getByRole("button", {name: "Sign out"})).toBeVisible();
        await page.reload();
        await expect(page.getByRole("button", {name: "Sign out"})).toBeVisible();
        await page
            .getByRole("textbox", {name: "Search products"})
            .fill("Morning ceramic mug");
        await page.getByRole("button", {name: "Search", exact: true}).click();
        await page
            .getByRole("button", {name: "Add Morning ceramic mug to cart"})
            .click();
        await expect(page.getByRole("status")).toContainText("added to your cart");
        await page.getByRole("button", {name: "Place order"}).click();
        await expect(
            page.getByRole("heading", {name: "Your orders"}),
        ).toBeVisible();
        await expect(
            page.getByText("Confirmed", {exact: true}).first(),
        ).toBeVisible({timeout: 20000});
        await expect(
            page.getByRole("heading", {name: "Latest updates"}),
        ).toBeVisible();
        await page.getByRole("button", {name: "Collection", exact: true}).click();
        await page
            .getByRole("textbox", {name: "Search products"})
            .fill("Pocket notebook");
        await page.getByRole("button", {name: "Search", exact: true}).click();
        await page
            .getByRole("button", {name: "Add Pocket notebook to cart"})
            .click();
        await page.getByLabel("Simulated payment").selectOption("DECLINE");
        await page.getByRole("button", {name: "Place order"}).click();
        await expect(
            page.getByText("Cancelled", {exact: true}).first(),
        ).toBeVisible({timeout: 20000});
        await expect(
            page
                .getByText("Your order was cancelled. Reserved stock has been released.")
                .first(),
        ).toBeVisible();
    });

test("search filters use real Elasticsearch and the layout fits a mobile viewport",
    async ({
               page,
           }) => {
        await page.goto("/");
        await page.locator(".product-card").first().waitFor();
        await page.screenshot({
            path: "../.cache/verification/storefront.png",
            fullPage: true,
        });
        await page.setViewportSize({width: 390, height: 844});
        await page.goto("/");
        await page
            .getByRole("textbox", {name: "Search products"})
            .fill("mechanical");
        await page.getByRole("button", {name: "Search", exact: true}).click();
        await expect(
            page.getByRole("heading", {name: "Studio mechanical keyboard"}),
        ).toBeVisible();
        await expect(
            page.getByRole("heading", {name: "Morning ceramic mug"}),
        ).toHaveCount(0);
        expect(
            await page.evaluate(
                () => document.documentElement.scrollWidth <= window.innerWidth,
            ),
        ).toBe(true);
    });

test("administrator creates a product and sets stock through authenticated forms",
    async ({
               page,
           }) => {
        await page.goto("/");
        await page
            .getByRole("button", {name: "Sign in", exact: false})
            .first()
            .click();
        await page.getByLabel("Username").fill("admin");
        await page.getByLabel("Password", {exact: true}).fill("local-admin-only");
        await page.getByRole("button", {name: "Sign In", exact: true}).click();
        await page
            .getByRole("button", {name: "Administration", exact: true})
            .click();
        const name = "Browser-tested product " + Date.now();
        await page
            .getByLabel("SKU", {exact: true})
            .fill("BROWSER-ADMIN-" + Date.now());
        await page.getByLabel("Name", {exact: true}).fill(name);
        await page.getByLabel("Category", {exact: true}).fill("Everyday");
        await page
            .getByLabel("Description", {exact: true})
            .fill("Created using the administration interface.");
        await page.getByLabel("Price", {exact: true}).fill("15.00");
        await page
            .getByRole("button", {name: "Create product", exact: true})
            .click();
        await expect(
            page.locator(".admin-products").getByText(name, {exact: true}),
        ).toBeVisible();
        await page
            .getByLabel("Product", {exact: true})
            .selectOption({label: name});
        await page.getByLabel("Available units", {exact: true}).fill("12");
        await page.getByRole("button", {name: "Update stock", exact: true}).click();
        await expect(
            page.getByText("0 units are reserved for active orders."),
        ).toBeVisible();
    });

test("support can monitor orders without customer or catalog-mutation controls",
    async ({
               page,
           }) => {
        await page.goto("/");
        await page
            .getByRole("button", {name: "Sign in", exact: false})
            .first()
            .click();
        await page.getByLabel("Username").fill("support");
        await page.getByLabel("Password", {exact: true}).fill("local-support-only");
        await page.getByRole("button", {name: "Sign In", exact: true}).click();
        await page
            .getByRole("button", {name: "Administration", exact: true})
            .click();
        await expect(
            page.getByRole("heading", {name: "Order monitoring"}),
        ).toBeVisible();
        await expect(
            page.getByRole("button", {name: "My orders", exact: true}),
        ).toHaveCount(0);
        await expect(
            page.getByRole("button", {name: "Create product", exact: true}),
        ).toHaveCount(0);
        await expect(
            page.getByRole("complementary", {name: "Shopping cart"}),
        ).toHaveCount(0);
        await expect(page.getByRole("alert")).toHaveCount(0);
    });
