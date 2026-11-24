import { test, expect } from '@playwright/test';

test.describe('Backlog & Requirements UI', () => {
  test('renders ProjectNavBar, breadcrumbs, and hierarchy tree at /p/DSP/backlog', async ({ page }) => {
    await page.goto('/p/DSP/backlog');
    await page.waitForLoadState('networkidle');

    // Verify main content landmark
    const mainContent = page.locator('#main-content');
    await expect(mainContent).toBeVisible();

    // Verify Project Navigation Bar
    const navHeader = page.locator('header[role="banner"]');
    await expect(navHeader).toBeVisible();
    await expect(page.getByText('DevSphere Platform')).toBeVisible();
    await expect(page.getByText('DSP', { exact: true })).toBeVisible();

    // Verify tabs
    const backlogTab = page.getByRole('tab', { name: 'Backlog', exact: true });
    await expect(backlogTab).toBeVisible();
    await expect(backlogTab).toHaveAttribute('aria-selected', 'true');

    const sprintsTab = page.getByRole('tab', { name: 'Sprints', exact: true });
    await expect(sprintsTab).toBeVisible();

    // Verify heading
    await expect(page.getByRole('heading', { name: 'Backlog & Requirements Hierarchy' })).toBeVisible();

    // Verify work item tree rows (DSP-1, DSP-2, etc.)
    await expect(page.getByText('DSP-1', { exact: true })).toBeVisible();
    await expect(page.getByText('Platform Foundations & Security Engine')).toBeVisible();
    await expect(page.getByText('DSP-2', { exact: true })).toBeVisible();
    await expect(page.getByText('Interactive Scheduling & Solver Integration')).toBeVisible();
  });

  test('renders workspace backlog route /workspaces/devsphere-core/backlog', async ({ page }) => {
    await page.goto('/workspaces/devsphere-core/backlog');
    await page.waitForLoadState('networkidle');

    await expect(page.locator('#main-content')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Backlog & Requirements Hierarchy' })).toBeVisible();
    await expect(page.getByText('DSP-1', { exact: true })).toBeVisible();
  });

  test('collapses and expands work item hierarchy via chevron button', async ({ page }) => {
    await page.goto('/p/DSP/backlog');
    await page.waitForLoadState('networkidle');

    // Child story DSP-3 should be initially visible
    await expect(page.getByText('DSP-3', { exact: true })).toBeVisible();

    // Find collapse button for DSP-1 with exact match
    const collapseBtn = page.getByRole('button', { name: 'Collapse DSP-1', exact: true });
    await expect(collapseBtn).toBeVisible();
    await collapseBtn.click();

    // After collapsing DSP-1, child DSP-3 should be hidden
    await expect(page.getByText('DSP-3', { exact: true })).toBeHidden();

    // Now button should be expand
    const expandBtn = page.getByRole('button', { name: 'Expand DSP-1', exact: true });
    await expect(expandBtn).toBeVisible();
    await expandBtn.click();

    // DSP-3 should be visible again
    await expect(page.getByText('DSP-3', { exact: true })).toBeVisible();
  });

  test('filters work items by search query and resets filter', async ({ page }) => {
    await page.goto('/p/DSP/backlog');
    await page.waitForLoadState('networkidle');

    const searchInput = page.getByRole('searchbox', { name: 'Search work items' });
    await expect(searchInput).toBeVisible();

    // Search for "Argon2id"
    await searchInput.fill('Argon2id');

    // Matching story DSP-3 and child DSP-7 should be visible
    await expect(page.getByText('DSP-3', { exact: true })).toBeVisible();
    await expect(page.getByText('DSP-7', { exact: true })).toBeVisible();

    // Unrelated story DSP-5 should be hidden
    await expect(page.getByText('DSP-5', { exact: true })).toBeHidden();

    // Reset filters
    const resetBtn = page.getByRole('button', { name: 'Reset all filters' });
    await expect(resetBtn).toBeVisible();
    await resetBtn.click();

    // DSP-5 should be visible again
    await expect(page.getByText('DSP-5', { exact: true })).toBeVisible();
  });

  test('filters work items by Type dropdown', async ({ page }) => {
    await page.goto('/p/DSP/backlog');
    await page.waitForLoadState('networkidle');

    const typeSelect = page.getByLabel('Type', { exact: true });
    await typeSelect.selectOption('EPIC');

    // Epics should remain
    await expect(page.getByText('DSP-1', { exact: true })).toBeVisible();
    await expect(page.getByText('DSP-2', { exact: true })).toBeVisible();

    // Sub-items should be filtered out
    await expect(page.getByText('DSP-7', { exact: true })).toBeHidden();
  });

  test('opens Create Work Item modal, traps focus, closes on Escape, and creates item', async ({ page }) => {
    await page.goto('/p/DSP/backlog');
    await page.waitForLoadState('networkidle');

    // Click Create Item button in toolbar
    const createBtn = page.getByRole('button', { name: 'Create Work Item', exact: true });
    await createBtn.click();

    // Modal should be visible
    const dialog = page.getByRole('dialog', { name: 'Create Work Item' });
    await expect(dialog).toBeVisible();

    // Test Escape to close
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();

    // Reopen and create an item
    await createBtn.click();
    await expect(dialog).toBeVisible();

    const titleInput = dialog.getByPlaceholder('e.g. Implement refresh token rotation');
    await titleInput.fill('E2E Automated Test Work Item');

    const prioritySelect = dialog.getByLabel('Priority');
    await prioritySelect.selectOption('HIGH');

    // Submit form inside modal
    const submitBtn = dialog.getByRole('button', { name: 'Create Item', exact: true });
    await submitBtn.click();

    // Dialog should close and item should appear in tree
    await expect(dialog).toBeHidden();
    await expect(page.getByText('E2E Automated Test Work Item')).toBeVisible();
  });

  test('opens Edit Work Item modal and edits details', async ({ page }) => {
    await page.goto('/p/DSP/backlog');
    await page.waitForLoadState('networkidle');

    // Click on Edit button for DSP-1 with exact: true
    const editBtn = page.getByRole('button', { name: 'Edit DSP-1', exact: true });
    await editBtn.click();

    const dialog = page.getByRole('dialog', { name: 'Edit Work Item' });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByText('DSP-1', { exact: true })).toBeVisible();

    // Edit title
    const titleInput = dialog.locator('input[type="text"]').first();
    await titleInput.fill('Platform Foundations & Security Engine (Updated)');

    // Save changes
    const saveBtn = dialog.getByRole('button', { name: 'Save Changes', exact: true });
    await saveBtn.click();

    await expect(dialog).toBeHidden();
    await expect(page.getByText('Platform Foundations & Security Engine (Updated)')).toBeVisible();
  });

  test('switches to Dependency DAG view and verifies precedence link visualization', async ({ page }) => {
    await page.goto('/p/DSP/backlog');
    await page.waitForLoadState('networkidle');

    // Switch to Dependency DAG view
    const dagViewBtn = page.getByRole('button', { name: 'Dependency DAG' });
    await dagViewBtn.click();

    // Check DAG view heading
    await expect(page.getByText('Dependency DAG & Precedence Matrix')).toBeVisible();

    // Verify existing dependency links (e.g. DSP-11 -> DSP-12, DSP-12 -> DSP-14)
    await expect(page.getByText('DSP-11')).toBeVisible();
    await expect(page.getByText('DSP-12').first()).toBeVisible();

    const depsList = page.getByRole('list', { name: 'Dependencies list' });
    await expect(depsList).toBeVisible();
    await expect(depsList.getByText('Critical').first()).toBeVisible();

    // Test Add Dependency button
    const addDepBtn = page.getByRole('button', { name: 'Add Dependency' });
    await expect(addDepBtn).toBeVisible();
    await addDepBtn.click();

    // Form should appear
    await expect(page.getByLabel('Predecessor (Blocks)')).toBeVisible();
    await expect(page.getByLabel('Successor (Blocked By)')).toBeVisible();

    // Cancel form
    const cancelBtn = page.getByRole('button', { name: 'Cancel' });
    await cancelBtn.click();
    await expect(page.getByLabel('Predecessor (Blocks)')).toBeHidden();
  });
});
