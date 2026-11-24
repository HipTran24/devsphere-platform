import { test, expect } from '@playwright/test';

test.describe('Sprint Board & Kanban UI', () => {
  test('renders ProjectNavBar, Sprints tab selected, SprintHeader, and 4 columns at /p/DSP/sprints', async ({ page }) => {
    await page.goto('/p/DSP/sprints');
    await page.waitForLoadState('networkidle');

    // Main content landmark
    const mainContent = page.locator('#main-content');
    await expect(mainContent).toBeVisible();

    // Sprints tab active
    const sprintsTab = page.getByRole('tab', { name: 'Sprints', exact: true });
    await expect(sprintsTab).toBeVisible();
    await expect(sprintsTab).toHaveAttribute('aria-selected', 'true');

    // Page heading
    await expect(page.getByRole('heading', { name: 'Sprint Board & Flow Control' })).toBeVisible();

    // Sprint Header & metrics
    await expect(page.getByRole('heading', { name: /Sprint 1/ })).toBeVisible();
    await expect(page.locator('span[aria-label="Sprint status: ACTIVE"]')).toBeVisible();

    const progressBar = page.locator('div[role="progressbar"]');
    await expect(progressBar).toBeVisible();

    // 4 Kanban Columns rendered
    await expect(page.getByRole('heading', { name: 'To Do', exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'In Progress', exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'In Review', exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Done', exact: true })).toBeVisible();

    // Cards in their respective columns
    await expect(page.getByText('DSP-10', { exact: true })).toBeVisible();
    await expect(page.getByText('DSP-9', { exact: true })).toBeVisible();
    await expect(page.getByText('DSP-12', { exact: true })).toBeVisible();
    await expect(page.getByText('DSP-7', { exact: true })).toBeVisible();
  });

  test('renders workspace sprints route /workspaces/devsphere-core/sprints', async ({ page }) => {
    await page.goto('/workspaces/devsphere-core/sprints');
    await page.waitForLoadState('networkidle');

    await expect(page.locator('#main-content')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Sprint Board & Flow Control' })).toBeVisible();
    await expect(page.getByText('DSP-10', { exact: true })).toBeVisible();
  });

  test('moves a card between columns using Prev and Next buttons', async ({ page }) => {
    await page.goto('/p/DSP/sprints');
    await page.waitForLoadState('networkidle');

    // Card DSP-10 starts in To Do
    const todoColumn = page.locator('section[aria-label*="To Do column"]');
    await expect(todoColumn.getByText('DSP-10', { exact: true })).toBeVisible();

    // Click Next button on DSP-10
    const moveNextBtn = page.getByRole('button', { name: 'Move DSP-10 to next column' });
    await expect(moveNextBtn).toBeVisible();
    await moveNextBtn.click();

    // Now DSP-10 should be in In Progress column
    const inProgressColumn = page.locator('section[aria-label*="In Progress column"]');
    await expect(inProgressColumn.getByText('DSP-10', { exact: true })).toBeVisible();
    await expect(todoColumn.getByText('DSP-10', { exact: true })).toBeHidden();

    // Click Prev button on DSP-10 to move back to To Do
    const movePrevBtn = page.getByRole('button', { name: 'Move DSP-10 to previous column' });
    await expect(movePrevBtn).toBeVisible();
    await movePrevBtn.click();

    // Now DSP-10 is back in To Do
    await expect(todoColumn.getByText('DSP-10', { exact: true })).toBeVisible();
  });

  test('opens and closes slide-over task drawer on card click', async ({ page }) => {
    await page.goto('/p/DSP/sprints');
    await page.waitForLoadState('networkidle');

    // Click on DSP-12 card title
    const cardTitle = page.getByRole('heading', { name: 'Integrate PM Acceptance dialog with Quality Gate evaluation' });
    await expect(cardTitle).toBeVisible();
    await cardTitle.click();

    // Task drawer should be open
    const drawer = page.locator('aside[role="dialog"]');
    await expect(drawer).toBeVisible();
    await expect(drawer.getByRole('heading', { name: 'Integrate PM Acceptance dialog with Quality Gate evaluation' })).toBeVisible();

    // Check acceptance criteria presence
    await expect(drawer.getByText('All automated CI checks must pass')).toBeVisible();

    // Check time spent
    await expect(drawer.getByText('Time Spent')).toBeVisible();

    // Close via close button
    const closeBtn = drawer.getByRole('button', { name: 'Close task details drawer' });
    await expect(closeBtn).toBeVisible();
    await closeBtn.click();

    // Drawer is closed
    await expect(drawer).toBeHidden();
  });

  test('inspects Quality Gate details flyout and blockers', async ({ page }) => {
    await page.goto('/p/DSP/sprints');
    await page.waitForLoadState('networkidle');

    // Quality gate button on DSP-12 (Passed)
    const qgBtn12 = page.getByRole('button', { name: /Quality Gate status: Passed for DSP-12/ });
    await expect(qgBtn12).toBeVisible();
    await qgBtn12.click();

    // Flyout should open
    const flyout = page.getByRole('dialog', { name: 'Quality Gate details for DSP-12' });
    await expect(flyout).toBeVisible();
    await expect(flyout.getByText('CI Build')).toBeVisible();
    await expect(flyout.getByText('Peer Review')).toBeVisible();
    await expect(flyout.getByText('Acceptance Criteria')).toBeVisible();

    // Press Escape to close flyout
    await page.keyboard.press('Escape');
    await expect(flyout).toBeHidden();

    // Quality gate button on DSP-14 (Failed)
    const qgBtn14 = page.getByRole('button', { name: /Quality Gate status: Failed for DSP-14/ });
    await expect(qgBtn14).toBeVisible();
    await qgBtn14.click();

    const flyout14 = page.getByRole('dialog', { name: 'Quality Gate details for DSP-14' });
    await expect(flyout14).toBeVisible();
    await expect(flyout14.getByText('Active Blockers')).toBeVisible();
    await expect(flyout14.getByText('CI build step failed on test suite execution')).toBeVisible();

    // Close flyout14
    await page.keyboard.press('Escape');
    await expect(flyout14).toBeHidden();
  });

  test('PM Acceptance Flow enforces PM role and Quality Gate validation before transitioning IN_REVIEW to DONE', async ({ page }) => {
    await page.goto('/p/DSP/sprints');
    await page.waitForLoadState('networkidle');

    // 1. Move DSP-12 (In Review, Quality Gate Passed) to next column
    const moveNextBtn12 = page.getByRole('button', { name: 'Move DSP-12 to next column' });
    await expect(moveNextBtn12).toBeVisible();
    await moveNextBtn12.click();

    // PM Acceptance dialog should appear
    const acceptanceDialog = page.getByRole('dialog', { name: 'PM Acceptance Flow (In Review → Done)' });
    await expect(acceptanceDialog).toBeVisible();
    await expect(acceptanceDialog.getByText('Quality Gate PASSED')).toBeVisible();

    // "PM Accept & Complete" button is available
    const acceptBtn = acceptanceDialog.getByRole('button', { name: 'PM Accept & Complete' });
    await expect(acceptBtn).toBeVisible();
    await acceptBtn.click();

    // Dialog closes and DSP-12 is now in Done column
    await expect(acceptanceDialog).toBeHidden();
    const doneColumn = page.locator('section[aria-label*="Done column"]');
    await expect(doneColumn.getByText('DSP-12', { exact: true })).toBeVisible();

    // 2. Test non-PM role rejection behavior:
    // Switch role to DEV
    const roleSelect = page.getByRole('combobox', { name: 'Simulate user role for acceptance flow' });
    await roleSelect.selectOption('DEV');

    // Move DSP-14 to next column
    const moveNextBtn14 = page.getByRole('button', { name: 'Move DSP-14 to next column' });
    await expect(moveNextBtn14).toBeVisible();
    await moveNextBtn14.click();

    // Dialog should show PM Role Required
    await expect(acceptanceDialog).toBeVisible();
    await expect(acceptanceDialog.getByText('PM Role Required')).toBeVisible();
    await expect(acceptanceDialog.getByRole('button', { name: 'PM Accept & Complete' })).toBeHidden();

    // Close dialog
    await acceptanceDialog.getByRole('button', { name: 'Cancel' }).click();
    await expect(acceptanceDialog).toBeHidden();

    // 3. Test failed Quality Gate rejection flow as PM:
    // Switch role back to PM
    await roleSelect.selectOption('PM');

    // Move DSP-14 again
    await moveNextBtn14.click();
    await expect(acceptanceDialog).toBeVisible();
    await expect(acceptanceDialog.getByText('Quality Gate FAILED / PENDING')).toBeVisible();

    // PM Reject button should be visible
    const rejectBtn = acceptanceDialog.getByRole('button', { name: 'PM Reject (Return to In Progress)' });
    await expect(rejectBtn).toBeVisible();

    // Enter rejection notes and click PM Reject
    const notesInput = acceptanceDialog.locator('#rejection-notes');
    await notesInput.fill('Please resolve failing CI build and request lead reviewer sign-off.');
    await rejectBtn.click();

    // DSP-14 should now be back in In Progress column!
    await expect(acceptanceDialog).toBeHidden();
    const inProgressColumn = page.locator('section[aria-label*="In Progress column"]');
    await expect(inProgressColumn.getByText('DSP-14', { exact: true })).toBeVisible();
  });
});
