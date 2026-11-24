import test from 'node:test';
import assert from 'node:assert/strict';
import {
  KANBAN_COLUMNS,
  normalizeStatus,
  partitionWorkItemsByColumn,
  calculateSprintMetrics,
  getNextColumn,
  getPrevColumn,
  evaluateQualityGate,
  isPmUser,
  formatMinutes,
  formatDateRange,
} from '../src/features/sprints/utils/sprint-utils.js';
import {
  DEMO_SPRINTS,
  DEMO_SPRINT_WORK_ITEMS,
  DEMO_QUALITY_GATES,
} from '../src/features/sprints/demo-data.js';

test('normalizeStatus handles aliases and casing correctly', () => {
  assert.equal(normalizeStatus('TODO'), 'TO_DO');
  assert.equal(normalizeStatus('to_do'), 'TO_DO');
  assert.equal(normalizeStatus('TO_DO'), 'TO_DO');
  assert.equal(normalizeStatus('in_progress'), 'IN_PROGRESS');
  assert.equal(normalizeStatus('IN_PROGRESS'), 'IN_PROGRESS');
  assert.equal(normalizeStatus('in_review'), 'IN_REVIEW');
  assert.equal(normalizeStatus('IN_REVIEW'), 'IN_REVIEW');
  assert.equal(normalizeStatus('done'), 'DONE');
  assert.equal(normalizeStatus('CLOSED'), 'DONE');
  assert.equal(normalizeStatus(null), 'TO_DO');
  assert.equal(normalizeStatus('UNKNOWN_STATUS'), 'TO_DO');
});

test('partitionWorkItemsByColumn groups items into 4 columns', () => {
  const partitioned = partitionWorkItemsByColumn(DEMO_SPRINT_WORK_ITEMS);

  assert.ok(partitioned.TO_DO);
  assert.ok(partitioned.IN_PROGRESS);
  assert.ok(partitioned.IN_REVIEW);
  assert.ok(partitioned.DONE);

  // Check expected count per column from demo data
  // TO_DO: DSP-10
  assert.equal(partitioned.TO_DO.length, 1);
  assert.equal(partitioned.TO_DO[0].itemKey, 'DSP-10');

  // IN_PROGRESS: DSP-9, DSP-11
  assert.equal(partitioned.IN_PROGRESS.length, 2);
  assert.ok(partitioned.IN_PROGRESS.some((i) => i.itemKey === 'DSP-9'));
  assert.ok(partitioned.IN_PROGRESS.some((i) => i.itemKey === 'DSP-11'));

  // IN_REVIEW: DSP-12, DSP-14
  assert.equal(partitioned.IN_REVIEW.length, 2);
  assert.ok(partitioned.IN_REVIEW.some((i) => i.itemKey === 'DSP-12'));
  assert.ok(partitioned.IN_REVIEW.some((i) => i.itemKey === 'DSP-14'));

  // DONE: DSP-7, DSP-8, DSP-13
  assert.equal(partitioned.DONE.length, 3);
  assert.ok(partitioned.DONE.some((i) => i.itemKey === 'DSP-7'));
  assert.ok(partitioned.DONE.some((i) => i.itemKey === 'DSP-8'));
  assert.ok(partitioned.DONE.some((i) => i.itemKey === 'DSP-13'));
});

test('partitionWorkItemsByColumn handles empty or invalid inputs safely', () => {
  const empty = partitionWorkItemsByColumn([]);
  assert.deepEqual(empty, { TO_DO: [], IN_PROGRESS: [], IN_REVIEW: [], DONE: [] });

  const nullInput = partitionWorkItemsByColumn(null);
  assert.deepEqual(nullInput, { TO_DO: [], IN_PROGRESS: [], IN_REVIEW: [], DONE: [] });
});

test('calculateSprintMetrics computes progress, burndown and time metrics', () => {
  const sprint = DEMO_SPRINTS[0]; // committedPoints: 34
  const metrics = calculateSprintMetrics(DEMO_SPRINT_WORK_ITEMS, sprint);

  assert.equal(metrics.totalItems, 8);
  assert.equal(metrics.committedPoints, 34);

  // Done items: DSP-7 (5), DSP-8 (5), DSP-13 (3) = 13 story points
  assert.equal(metrics.completedPoints, 13);
  assert.equal(metrics.remainingPoints, 21); // 34 - 13

  // Completion percentage: (13 / 34) * 100 = 38%
  assert.equal(metrics.completionPercentage, 38);

  // Remaining work items: 8 total - 3 done = 5
  assert.equal(metrics.remainingItems, 5);

  // Spent minutes sum:
  // DSP-10 (0) + DSP-9 (240) + DSP-11 (180) + DSP-12 (420) + DSP-14 (240) + DSP-7 (300) + DSP-8 (300) + DSP-13 (180) = 1860 min
  assert.equal(metrics.totalSpentMinutes, 1860);
  assert.equal(metrics.totalSpentHours, '31.0');

  // Column counts
  assert.equal(metrics.columnCounts.TO_DO, 1);
  assert.equal(metrics.columnCounts.IN_PROGRESS, 2);
  assert.equal(metrics.columnCounts.IN_REVIEW, 2);
  assert.equal(metrics.columnCounts.DONE, 3);
});

test('calculateSprintMetrics handles zero committed points without division by zero', () => {
  const metrics = calculateSprintMetrics([], { committedPoints: 0 });
  assert.equal(metrics.completionPercentage, 0);
  assert.equal(metrics.completedPoints, 0);
  assert.equal(metrics.remainingPoints, 0);
  assert.equal(metrics.totalSpentHours, '0.0');
});

test('getNextColumn and getPrevColumn enforce proper workflow sequence', () => {
  assert.equal(getNextColumn('TO_DO'), 'IN_PROGRESS');
  assert.equal(getNextColumn('IN_PROGRESS'), 'IN_REVIEW');
  assert.equal(getNextColumn('IN_REVIEW'), 'DONE');
  assert.equal(getNextColumn('DONE'), null);

  assert.equal(getPrevColumn('DONE'), 'IN_REVIEW');
  assert.equal(getPrevColumn('IN_REVIEW'), 'IN_PROGRESS');
  assert.equal(getPrevColumn('IN_PROGRESS'), 'TO_DO');
  assert.equal(getPrevColumn('TO_DO'), null);
});

test('evaluateQualityGate maps gate statuses and blockers correctly', () => {
  // Gate passed (DSP-12)
  const passedGate = DEMO_QUALITY_GATES['t1212121-1212-1212-1212-121212121212'];
  const passedEval = evaluateQualityGate(passedGate);
  assert.equal(passedEval.status, 'PASSED');
  assert.equal(passedEval.passed, true);
  assert.equal(passedEval.label, 'Passed');

  // Gate failed (DSP-14)
  const failedGate = DEMO_QUALITY_GATES['t1414141-1414-1414-1414-141414141414'];
  const failedEval = evaluateQualityGate(failedGate);
  assert.equal(failedEval.status, 'FAILED');
  assert.equal(failedEval.passed, false);
  assert.equal(failedEval.label, 'Failed');

  // Gate pending (DSP-11)
  const pendingGate = DEMO_QUALITY_GATES['t1111111-1111-1111-1111-111111111111'];
  const pendingEval = evaluateQualityGate(pendingGate);
  assert.equal(pendingEval.status, 'PENDING');
  assert.equal(pendingEval.passed, false);

  // Missing or null gate
  const nullEval = evaluateQualityGate(null);
  assert.equal(nullEval.status, 'PENDING');
  assert.equal(nullEval.passed, false);
});

test('isPmUser accurately identifies PM and Admin roles vs Developers/QA', () => {
  assert.equal(isPmUser({ role: 'PM' }), true);
  assert.equal(isPmUser({ systemRole: 'PM' }), true);
  assert.equal(isPmUser({ role: 'ADMIN' }), true);
  assert.equal(isPmUser({ role: 'OWNER' }), true);
  assert.equal(isPmUser({ email: 'pm@devsphere.io' }), true);
  assert.equal(isPmUser({ email: 'admin@devsphere.io' }), true);

  assert.equal(isPmUser({ role: 'DEV' }), false);
  assert.equal(isPmUser({ role: 'DEVELOPER' }), false);
  assert.equal(isPmUser({ role: 'QA' }), false);
  assert.equal(isPmUser({ role: 'USER' }), false);
  assert.equal(isPmUser({ email: 'dev@devsphere.io' }), false);
  assert.equal(isPmUser(null), false);
});

test('formatMinutes formats minutes to human readable durations', () => {
  assert.equal(formatMinutes(0), '0h');
  assert.equal(formatMinutes(30), '30m');
  assert.equal(formatMinutes(60), '1h');
  assert.equal(formatMinutes(90), '1h 30m');
  assert.equal(formatMinutes(240), '4h');
  assert.equal(formatMinutes(null), '0h');
});

test('formatDateRange formats dates gracefully', () => {
  const range = formatDateRange('2026-11-17', '2026-11-30');
  assert.ok(range.includes('Nov 17, 2026'));
  assert.ok(range.includes('Nov 30, 2026'));

  assert.equal(formatDateRange(null, null), 'No dates set');
});

test('KANBAN_COLUMNS definition conforms to standard 4 columns', () => {
  assert.equal(KANBAN_COLUMNS.length, 4);
  assert.equal(KANBAN_COLUMNS[0].id, 'TO_DO');
  assert.equal(KANBAN_COLUMNS[1].id, 'IN_PROGRESS');
  assert.equal(KANBAN_COLUMNS[2].id, 'IN_REVIEW');
  assert.equal(KANBAN_COLUMNS[3].id, 'DONE');
});
