import { test, expect } from "@playwright/test";

async function tabUntil(page, predicate, maxTabs = 20) {
    for (let step = 0; step < maxTabs; step++) {
        await page.keyboard.press("Tab");
        const matched = await page.evaluate(predicate);
        if (matched) return true;
    }
    return false;
}

test("skip link moves keyboard focus directly to #main-content", async ({ page }) => {
    await page.goto("/login");
    await page.keyboard.press("Tab");

    const skipLink = page.getByRole("link", { name: "Skip to main content", exact: true });
    await expect(skipLink).toBeFocused();
    await expect(skipLink).toBeVisible();

    await page.keyboard.press("Enter");
    await expect(page.locator("#main-content")).toBeFocused();

    await page.goto("/workspace-home");
    await page.waitForLoadState("domcontentloaded");
    await page.keyboard.press("Tab");
    await expect(skipLink).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(page.locator("#main-content")).toBeFocused();
});

test("pure keyboard login reaches /workspaces empty state, traps dialog focus, and restores focus on Escape", async ({ page }) => {
    await page.goto("/login");

    const reachedEmail = await tabUntil(page, () => document.activeElement?.getAttribute("name") === "email");
    expect(reachedEmail).toBe(true);
    await page.keyboard.type("demo@example.com");

    const reachedPassword = await tabUntil(page, () => document.activeElement?.getAttribute("name") === "password", 5);
    expect(reachedPassword).toBe(true);
    await page.keyboard.type("demo-password-123");

    const reachedRemember = await tabUntil(
        page,
        () => document.activeElement?.getAttribute("type") === "checkbox",
        5
    );
    expect(reachedRemember).toBe(true);
    await page.keyboard.press("Space");
    await expect(page.getByRole("checkbox", { name: "Remember me" })).toBeChecked();

    const reachedSubmit = await tabUntil(
        page,
        () => document.activeElement?.getAttribute("type") === "submit",
        5
    );
    expect(reachedSubmit).toBe(true);
    await page.keyboard.press("Enter");

    await expect(page).toHaveURL(/\/workspaces$/);
    await expect(page.getByRole("heading", { name: "No workspaces yet", exact: true })).toBeVisible();

    const createBtn = page.getByRole("button", { name: "+ Create Workspace", exact: true });
    await createBtn.focus();
    await expect(createBtn).toBeFocused();
    await page.keyboard.press("Enter");

    const dialog = page.getByRole("dialog", { name: "Create Workspace" });
    await expect(dialog).toBeVisible();
    await expect(page.locator("#ws-name")).toBeFocused();

    // Shift+Tab from first field wraps to last focusable control inside dialog.
    await page.keyboard.press("Shift+Tab");
    const insideAfterShiftTab = await page.evaluate(() =>
        Boolean(document.activeElement?.closest('[role="dialog"]'))
    );
    expect(insideAfterShiftTab).toBe(true);

    // Tabbing repeatedly stays trapped inside the dialog.
    for (let i = 0; i < 7; i++) {
        await page.keyboard.press("Tab");
        const insideDialog = await page.evaluate(() =>
            Boolean(document.activeElement?.closest('[role="dialog"]'))
        );
        expect(insideDialog).toBe(true);
    }

    // Escape closes the modal and restores focus to the trigger button.
    await page.keyboard.press("Escape");
    await expect(dialog).toBeHidden();
    await expect(createBtn).toBeFocused();
});

test("/workspaces displays ErrorState on 503 and recovers via keyboard Retry to workspace card", async ({ page }) => {
    let shouldFail = true;
    await page.route("**/api/v1/workspaces", async (route) => {
        if (route.request().method() !== "GET") {
            await route.continue();
            return;
        }
        if (shouldFail) {
            await route.fulfill({
                status: 503,
                contentType: "application/json",
                body: JSON.stringify({
                    title: "Service Unavailable",
                    detail: "Database maintenance in progress.",
                }),
            });
            return;
        }
        await route.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify({
                items: [
                    {
                        id: "ws-core-1",
                        name: "Core Platform",
                        slug: "core-platform",
                        role: "OWNER",
                        status: "ACTIVE",
                        description: "Production delivery workspace.",
                    },
                ],
            }),
        });
    });

    await page.goto("/login");
    await page.locator('input[name="email"]:visible').fill("demo@example.com");
    await page.locator('input[name="password"]:visible').fill("demo-password-123");
    await page.getByRole("button", { name: "Sign In", exact: true }).click();
    await expect(page).toHaveURL(/\/workspaces$/);

    const alert = page.locator("main").getByRole("alert");
    await expect(alert).toBeVisible();
    await expect(alert.getByText("Database maintenance in progress.", { exact: true })).toBeVisible();
    await expect(alert.getByText("HTTP_503", { exact: true })).toBeVisible();

    shouldFail = false;
    const retryBtn = alert.getByRole("button", { name: "Retry", exact: true });
    await retryBtn.focus();
    await page.keyboard.press("Enter");

    await expect(alert).toBeHidden();
    await expect(page.getByRole("heading", { name: "Core Platform", exact: true })).toBeVisible();
    await expect(page.getByText("slug: core-platform", { exact: true })).toBeVisible();
});

test("/workspace-home supports full keyboard interaction across menu, search, notifications, and dialog", async ({ page }) => {
    await page.goto("/workspace-home");

    // 1. Account dropdown: Enter opens, ArrowDown / End / Home navigate, Escape restores focus.
    const accountTrigger = page.getByRole("button", { name: "Account", exact: true });
    await accountTrigger.focus();
    await page.keyboard.press("Enter");

    const accountMenu = page.getByRole("menu", { name: "Account menu", exact: true });
    await expect(accountMenu).toBeVisible();
    await expect(accountMenu.getByRole("menuitem", { name: /Profile/ })).toBeFocused();

    await page.keyboard.press("ArrowDown");
    await expect(accountMenu.getByRole("menuitem", { name: /Security/ })).toBeFocused();

    await page.keyboard.press("End");
    await expect(accountMenu.getByRole("menuitem", { name: "Sign out", exact: true })).toBeFocused();

    await page.keyboard.press("Home");
    await expect(accountMenu.getByRole("menuitem", { name: /Profile/ })).toBeFocused();

    await page.keyboard.press("Escape");
    await expect(accountMenu).toBeHidden();
    await expect(accountTrigger).toBeFocused();

    // 2. Search no-match EmptyState + Clear search CTA + Escape focus return.
    const searchToggle = page.getByRole("button", { name: "Search workspaces", exact: true });
    await searchToggle.focus();
    await page.keyboard.press("Enter");

    const searchInput = page.getByRole("searchbox", { name: "Filter workspaces by name", exact: true });
    await expect(searchInput).toBeFocused();
    await page.keyboard.type("nonexistent-workspace-xyz");

    await expect(page.getByRole("heading", { name: "No matching workspaces found", exact: true })).toBeVisible();
    const clearSearchBtn = page.getByRole("button", { name: "Clear search", exact: true });
    await clearSearchBtn.focus();
    await page.keyboard.press("Enter");

    await expect(page.getByRole("cell", { name: "Owner" }).first()).toBeVisible();
    await expect(searchToggle).toBeFocused();

    // Escape closes open search and restores focus to trigger.
    await page.keyboard.press("Enter");
    await expect(searchInput).toBeFocused();
    await page.keyboard.press("Escape");
    await expect(searchInput).toBeHidden();
    await expect(searchToggle).toBeFocused();

    // 3. Notifications filter + Mark as read + Mark all as read -> EmptyState.
    const unreadFilter = page.getByRole("button", { name: /^Unread · \d+$/ });
    await unreadFilter.focus();
    await page.keyboard.press("Enter");
    await expect(unreadFilter).toHaveAttribute("aria-pressed", "true");

    const markSingleBtn = page.getByRole("button", { name: "Mark as read: Task assigned", exact: true });
    await markSingleBtn.focus();
    await page.keyboard.press("Enter");
    await expect(markSingleBtn).toBeHidden();

    const markAllBtn = page.getByRole("button", { name: "Mark all as read", exact: true });
    await markAllBtn.focus();
    await page.keyboard.press("Enter");

    await expect(page.getByRole("heading", { name: "You’re all caught up.", exact: true })).toBeVisible();
    await expect(markAllBtn).toBeDisabled();

    // 4. Create workspace dialog opens, focuses input, and Escape restores trigger focus.
    const createWsBtn = page.getByRole("button", { name: "Create workspace", exact: true });
    await createWsBtn.focus();
    await page.keyboard.press("Enter");

    const dialog = page.getByRole("dialog", { name: "Create workspace" });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByLabel("Workspace name")).toBeFocused();

    await page.keyboard.press("Escape");
    await expect(dialog).toBeHidden();
    await expect(createWsBtn).toBeFocused();
});

test("accessible 404 route renders main landmark and keyboard-navigable links", async ({ page }) => {
    const response = await page.goto("/this-page-does-not-exist");
    expect(response?.status()).toBe(404);

    await expect(page.locator("main#main-content")).toBeVisible();
    await expect(page.getByRole("heading", { name: "Page not found", exact: true })).toBeVisible();
    await expect(page.getByRole("link", { name: "My Workspaces", exact: true })).toBeVisible();
    await expect(page.getByRole("link", { name: "Workspace Home", exact: true })).toBeVisible();

    const signInLink = page.getByRole("link", { name: "Sign In", exact: true });
    await signInLink.focus();
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/\/login$/);
});
