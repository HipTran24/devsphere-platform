import { test, expect } from '@playwright/test';

test.describe('Gantt Timeline & Plan Approval UI', () => {
  test('renders ProjectNavBar, Gantt tab active, GanttChart, and work item bars at /p/DSP/gantt', async ({ page }) => {
    await page.goto('/p/DSP/gantt');
    await page.waitForLoadState('networkidle');

    // Main content landmark
    const mainContent = page.locator('#main-content');
    await expect(mainContent).toBeVisible();

    // Gantt tab active
    const ganttTab = page.getByRole('tab', { name: 'Gantt', exact: true });
    await expect(ganttTab).toBeVisible();
    await expect(ganttTab).toHaveAttribute('aria-selected', 'true');

    // Page heading
    await expect(
      page.getByRole('heading', { name: 'Gantt Timeline & Critical Path Schedule' })
    ).toBeVisible();

    // Gantt chart container
    const chart = page.locator('div[data-testid="gantt-chart-container"]');
    await expect(chart).toBeVisible();

    // Verify key work items rendered in chart
    await expect(page.getByText('DSP-7', { exact: true })).toBeVisible();
    await expect(page.getByText('DSP-8', { exact: true })).toBeVisible();
    await expect(page.getByText('DSP-9', { exact: true })).toBeVisible();
    await expect(page.getByText('DSP-10', { exact: true })).toBeVisible();
    await expect(page.getByText('DSP-12', { exact: true })).toBeVisible();

    // Verify CPM critical path badge present
    const cpmBadges = page.locator('span[aria-label="Critical Path"]');
    await expect(cpmBadges.first()).toBeVisible();

    // Verify milestones row and markers
    await expect(page.getByText('Milestones', { exact: true })).toBeVisible();
    await expect(page.getByText('M1: Security & Proxy Baseline')).toBeVisible();

    // Verify Sprints track
    await expect(page.locator('div[aria-label="Sprint Boundaries"]')).toBeVisible();
    await expect(page.getByText('Sprint 1: Foundations & Core Engine')).toBeVisible();
  });

  test('toggles timeline view scale between Days, Weeks, and Sprints', async ({ page }) => {
    await page.goto('/p/DSP/gantt');
    await page.waitForLoadState('networkidle');

    const daysBtn = page.locator('button[data-testid="view-mode-days"]');
    const weeksBtn = page.locator('button[data-testid="view-mode-weeks"]');
    const sprintsBtn = page.locator('button[data-testid="view-mode-sprints"]');

    // Initially Days is active
    await expect(daysBtn).toHaveAttribute('aria-selected', 'true');

    // Click Weeks
    await weeksBtn.click();
    await expect(weeksBtn).toHaveAttribute('aria-selected', 'true');
    await expect(daysBtn).toHaveAttribute('aria-selected', 'false');
    await expect(page.getByText(/W4[0-9]/).first()).toBeVisible();

    // Click Sprints
    await sprintsBtn.click();
    await expect(sprintsBtn).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByText(/Sprint 1/).first()).toBeVisible();

    // Return to Days
    await daysBtn.click();
    await expect(daysBtn).toHaveAttribute('aria-selected', 'true');
  });

  test('filters timeline to Critical Path (CPM) bottleneck items only', async ({ page }) => {
    await page.goto('/p/DSP/gantt');
    await page.waitForLoadState('networkidle');

    const criticalToggle = page.locator('button[data-testid="critical-path-toggle"]');
    await expect(criticalToggle).toBeVisible();
    await expect(criticalToggle).toHaveAttribute('aria-pressed', 'false');

    // Count initial visible items
    const initialItemsCount = await page.locator('div[role="row"][data-testid^="gantt-row-"]').count();
    expect(initialItemsCount).toBeGreaterThanOrEqual(6);

    // Toggle critical path only
    await criticalToggle.click();
    await expect(criticalToggle).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByText('Showing Critical Path Only')).toBeVisible();

    // Filtered count should be less than or equal to total items
    const filteredCount = await page.locator('div[role="row"][data-testid^="gantt-row-"]').count();
    expect(filteredCount).toBeGreaterThan(0);
    expect(filteredCount).toBeLessThanOrEqual(initialItemsCount);

    // Toggle back to all
    await criticalToggle.click();
    await expect(criticalToggle).toHaveAttribute('aria-pressed', 'false');
    const restoredCount = await page.locator('div[role="row"][data-testid^="gantt-row-"]').count();
    expect(restoredCount).toEqual(initialItemsCount);
  });

  test('switches zoom levels between Compact, Normal, and Detailed', async ({ page }) => {
    await page.goto('/p/DSP/gantt');
    await page.waitForLoadState('networkidle');

    const compactBtn = page.locator('button[aria-label="Compact Zoom"]');
    const detailedBtn = page.locator('button[aria-label="Detailed Zoom"]');
    const normalBtn = page.locator('button[aria-label="Normal Zoom"]');

    // Click Compact Zoom
    await compactBtn.click();
    const firstRow = page.locator('div[role="row"][data-testid^="gantt-row-"]').first();
    await expect(firstRow).toBeVisible();

    // Click Detailed Zoom
    await detailedBtn.click();
    await expect(firstRow).toBeVisible();

    // Reset to Normal Zoom
    await normalBtn.click();
    await expect(firstRow).toBeVisible();
  });

  test('renders Plan Approval UI with solver overview and diff tables at /p/DSP/plan-approval', async ({ page }) => {
    await page.goto('/p/DSP/plan-approval');
    await page.waitForLoadState('networkidle');

    // Landmark & Navigation
    await expect(page.locator('#main-content')).toBeVisible();
    const planTab = page.getByRole('tab', { name: 'Plan Approval', exact: true });
    await expect(planTab).toBeVisible();
    await expect(planTab).toHaveAttribute('aria-selected', 'true');

    // Heading
    await expect(
      page.getByRole('heading', { name: 'Plan Approval & Schedule Proposal Diff' })
    ).toBeVisible();

    // Executive overview card & solver status
    const solverPill = page.locator('span[data-testid="solver-status-pill"]');
    await expect(solverPill).toBeVisible();
    await expect(solverPill).toHaveText(/OPTIMAL/);

    // Metrics cards
    await expect(page.getByText('Proposed Tasks')).toBeVisible();
    await expect(page.getByText('Total Duration')).toBeVisible();
    await expect(page.getByText('Forecast Finish')).toBeVisible();
    await expect(page.getByText('Committed Deadline', { exact: true })).toBeVisible();

    // Schedule Diff Table
    await expect(page.getByRole('heading', { name: /Work Item Schedule Comparison/ })).toBeVisible();
    await expect(page.getByRole('table', { name: 'Schedule diff before and after' })).toBeVisible();
    await expect(page.locator('tr[data-testid="diff-row-DSP-7"]')).toBeVisible();
    await expect(page.locator('tr[data-testid="diff-row-DSP-9"]')).toBeVisible();

    // Milestone Buffer table
    await expect(page.getByRole('heading', { name: 'Milestone Buffer & Impact Analysis' })).toBeVisible();
    await expect(page.getByRole('table', { name: 'Milestone impacts table' })).toBeVisible();
  });

  test('filters schedule diff items by Shifted, Delayed, and Earlier', async ({ page }) => {
    await page.goto('/p/DSP/plan-approval');
    await page.waitForLoadState('networkidle');

    const filterAll = page.locator('button[data-testid="diff-filter-all"]');
    const filterShifted = page.locator('button[data-testid="diff-filter-shifted"]');
    const filterDelayed = page.locator('button[data-testid="diff-filter-delayed"]');

    await expect(filterAll).toBeVisible();
    await expect(filterShifted).toBeVisible();

    // Click Shifted filter
    await filterShifted.click();
    await expect(filterShifted).toHaveClass(/filterBtnActive/);

    // Click Delayed filter
    await filterDelayed.click();
    await expect(filterDelayed).toHaveClass(/filterBtnActive/);

    // Return to All
    await filterAll.click();
    await expect(filterAll).toHaveClass(/filterBtnActive/);
  });

  test('handles slipping deadline proposal with mandatory confirmation gating', async ({ page }) => {
    await page.goto('/p/DSP/plan-approval');
    await page.waitForLoadState('networkidle');

    // Switch to Proposal 2 (Slipping proposal)
    const proposalSelect = page.locator('select[data-testid="proposal-select"]');
    if (await proposalSelect.isVisible()) {
      await proposalSelect.selectOption({ index: 1 });
    }

    // Slipping alert banner should appear
    const alertBanner = page.locator('div[data-testid="slipping-alert-banner"]');
    await expect(alertBanner).toBeVisible();
    await expect(
      page.getByText('Mandatory Client / PM Deadline Confirmation Required')
    ).toBeVisible();

    // Click "Review & Approve Plan" button
    const reviewBtn = page.locator('button[data-testid="review-approve-plan-btn"]');
    await expect(reviewBtn).toBeVisible();
    await reviewBtn.click();

    // Confirmation dialog opens
    const dialog = page.locator('div[data-testid="deadline-confirmation-dialog"]');
    await expect(dialog).toBeVisible();
    await expect(
      page.getByRole('heading', { name: 'Mandatory Committed Deadline Confirmation' })
    ).toBeVisible();

    // "Confirm Deadline & Apply" button is disabled until checkbox is checked
    const confirmBtn = page.locator('button[data-testid="confirm-apply-btn"]');
    await expect(confirmBtn).toBeDisabled();

    // Check acknowledgment checkbox
    const consentCheckbox = page.locator('input[data-testid="deadline-consent-checkbox"]');
    await expect(consentCheckbox).toBeVisible();
    await consentCheckbox.check();

    // Confirm button is now enabled
    await expect(confirmBtn).toBeEnabled();

    // Optional rationale input
    const rationaleInput = page.locator('textarea[data-testid="deadline-rationale-input"]');
    await rationaleInput.fill('Client agreed to 7-day scope extension.');

    // Submit confirmation
    await confirmBtn.click();

    // Dialog should close and success banner should appear
    await expect(dialog).toBeHidden();
    const successToast = page.locator('div[role="status"]');
    await expect(successToast).toBeVisible();
  });

  test('rejects a plan proposal with user feedback', async ({ page }) => {
    await page.goto('/p/DSP/plan-approval');
    await page.waitForLoadState('networkidle');

    const rejectBtn = page.locator('button[data-testid="reject-proposal-btn"]');
    await expect(rejectBtn).toBeVisible();
    await rejectBtn.click();

    // Success banner indicating rejection
    const successToast = page.locator('div[role="status"]');
    await expect(successToast).toBeVisible();
    await expect(successToast).toHaveText(/rejected/i);
  });

  test('supports cross-navigation between all project tabs in ProjectNavBar', async ({ page }) => {
    await page.goto('/p/DSP/gantt');
    await page.waitForLoadState('networkidle');

    // Click Plan Approval tab
    await page.getByRole('tab', { name: 'Plan Approval' }).click();
    await expect(page).toHaveURL(/\/p\/DSP\/plan-approval/);
    await expect(page.getByRole('heading', { name: 'Plan Approval & Schedule Proposal Diff' })).toBeVisible();

    // Click Sprints tab
    await page.getByRole('tab', { name: 'Sprints' }).click();
    await expect(page).toHaveURL(/\/p\/DSP\/sprints/);
    await expect(page.getByRole('heading', { name: 'Sprint Board & Flow Control' })).toBeVisible();

    // Click Backlog tab
    await page.getByRole('tab', { name: 'Backlog' }).click();
    await expect(page).toHaveURL(/\/p\/DSP\/backlog/);
    await expect(page.getByRole('heading', { name: 'Backlog & Requirements Hierarchy' })).toBeVisible();

    // Return to Gantt tab
    await page.getByRole('tab', { name: 'Gantt' }).click();
    await expect(page).toHaveURL(/\/p\/DSP\/gantt/);
    await expect(page.getByRole('heading', { name: 'Gantt Timeline & Critical Path Schedule' })).toBeVisible();
  });

  test('renders workspace gantt and plan-approval routes', async ({ page }) => {
    // Workspace Gantt
    await page.goto('/workspaces/devsphere-core/gantt');
    await page.waitForLoadState('networkidle');
    await expect(page.locator('#main-content')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Gantt Timeline & Critical Path Schedule' })).toBeVisible();

    // Workspace Plan Approval
    await page.goto('/workspaces/devsphere-core/plan-approval');
    await page.waitForLoadState('networkidle');
    await expect(page.locator('#main-content')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Plan Approval & Schedule Proposal Diff' })).toBeVisible();
  });
});
