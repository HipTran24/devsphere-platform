import test from 'node:test';
import assert from 'node:assert/strict';
import {
  toValidDate,
  calculateCriticalPath,
  calculateTimelineBounds,
  generateTimelineIntervals,
  getWeekNumber,
  getDatePositionPercent,
  getBarWidthPercent,
  calculateScheduleDiff,
  evaluateDeadlineGating,
  formatShift,
  formatDate,
  formatDuration,
} from '../src/features/planning/utils/planning-utils.js';
import {
  DEMO_PLANNING_TASKS,
  DEMO_DEPENDENCIES,
  DEMO_MILESTONES,
  DEMO_PROPOSALS,
} from '../src/features/planning/demo-data.js';

test('toValidDate normalizes date formats and handles invalid values with fallback', () => {
  const d1 = toValidDate('2026-11-24T12:00:00Z');
  assert.equal(d1.toISOString(), '2026-11-24T12:00:00.000Z');

  const fallback = new Date('2026-01-01T00:00:00Z');
  const d2 = toValidDate(null, fallback);
  assert.equal(d2.toISOString(), fallback.toISOString());

  const d3 = toValidDate('invalid-date', fallback);
  assert.equal(d3.toISOString(), fallback.toISOString());
});

test('calculateCriticalPath performs CPM forward/backward passes and identifies bottleneck items', () => {
  const result = calculateCriticalPath(DEMO_PLANNING_TASKS, DEMO_DEPENDENCIES);

  assert.ok(result.tasks);
  assert.ok(result.criticalTaskIds);
  assert.ok(result.makespanDays > 0);

  // Critical tasks must have totalFloat === 0
  const criticalList = result.tasks.filter((t) => t.isCritical);
  assert.ok(criticalList.length > 0, 'There should be at least one critical path task');
  criticalList.forEach((t) => {
    assert.equal(t.totalFloat, 0, `Critical task ${t.itemKey} should have zero slack`);
  });

  // Verify non-critical tasks have non-zero or positive slack
  const nonCritical = result.tasks.filter((t) => !t.isCritical);
  if (nonCritical.length > 0) {
    assert.ok(nonCritical.every((t) => t.totalFloat >= 0));
  }
});

test('calculateCriticalPath handles empty or null task lists gracefully', () => {
  const emptyRes = calculateCriticalPath([], []);
  assert.deepEqual(emptyRes, { tasks: [], criticalTaskIds: new Set(), makespanDays: 0 });

  const nullRes = calculateCriticalPath(null, null);
  assert.deepEqual(nullRes, { tasks: [], criticalTaskIds: new Set(), makespanDays: 0 });
});

test('calculateCriticalPath calculates exact makespan on sequential chain', () => {
  const chainTasks = [
    { id: 't1', itemKey: 'T1', estimateMinutes: 480 }, // 1 day
    { id: 't2', itemKey: 'T2', estimateMinutes: 960 }, // 2 days
    { id: 't3', itemKey: 'T3', estimateMinutes: 480 }, // 1 day
  ];
  const chainDeps = [
    { predecessorId: 't1', successorId: 't2', lagMinutes: 0 },
    { predecessorId: 't2', successorId: 't3', lagMinutes: 0 },
  ];

  const res = calculateCriticalPath(chainTasks, chainDeps);
  assert.equal(res.makespanDays, 4); // 1 + 2 + 1 = 4 days
  assert.equal(res.criticalTaskIds.size, 3);
  assert.ok(res.criticalTaskIds.has('t1'));
  assert.ok(res.criticalTaskIds.has('t2'));
  assert.ok(res.criticalTaskIds.has('t3'));
});

test('calculateTimelineBounds computes valid min/max date envelope with padding', () => {
  const bounds = calculateTimelineBounds(DEMO_PLANNING_TASKS, DEMO_MILESTONES, []);

  assert.ok(bounds.minDate);
  assert.ok(bounds.maxDate);
  assert.ok(bounds.maxTime > bounds.minTime);
  assert.ok(bounds.totalDays >= 10);

  // All task start dates must be within or equal to minTime/maxTime
  DEMO_PLANNING_TASKS.forEach((t) => {
    const start = new Date(t.startAt).getTime();
    const finish = new Date(t.finishAt).getTime();
    assert.ok(start >= bounds.minTime, `Task ${t.itemKey} start must be >= minTime`);
    assert.ok(finish <= bounds.maxTime, `Task ${t.itemKey} finish must be <= maxTime`);
  });
});

test('generateTimelineIntervals generates headers for Days, Weeks, and Sprints', () => {
  const minDate = new Date('2026-11-15T00:00:00Z');
  const maxDate = new Date('2026-11-28T00:00:00Z');

  // Days
  const dayIntervals = generateTimelineIntervals(minDate, maxDate, 'DAYS');
  assert.equal(dayIntervals.length, 14);
  assert.ok(dayIntervals[0].label.includes('Nov'));
  assert.ok(dayIntervals.some((i) => i.isWeekend));

  // Weeks
  const weekIntervals = generateTimelineIntervals(minDate, maxDate, 'WEEKS');
  assert.ok(weekIntervals.length >= 2);
  assert.ok(weekIntervals[0].label.startsWith('W'));

  // Sprints
  const sprintIntervals = generateTimelineIntervals(minDate, maxDate, 'SPRINTS');
  assert.ok(sprintIntervals.length >= 1);
  assert.ok(sprintIntervals[0].label.startsWith('Sprint'));
});

test('getWeekNumber calculates ISO week number correctly', () => {
  const dateNov = new Date('2026-11-24T00:00:00Z');
  const weekNo = getWeekNumber(dateNov);
  assert.ok(weekNo >= 47 && weekNo <= 49);
});

test('getDatePositionPercent and getBarWidthPercent calculate accurate percentages within 0-100%', () => {
  const minTime = 1000;
  const maxTime = 2000;

  assert.equal(getDatePositionPercent(1000, minTime, maxTime), 0);
  assert.equal(getDatePositionPercent(1500, minTime, maxTime), 50);
  assert.equal(getDatePositionPercent(2000, minTime, maxTime), 100);

  // Clamping
  assert.equal(getDatePositionPercent(500, minTime, maxTime), 0);
  assert.equal(getDatePositionPercent(3000, minTime, maxTime), 100);

  // Bar width
  assert.equal(getBarWidthPercent(1200, 1500, minTime, maxTime), 30);
  // Minimum width is 1.5%
  assert.equal(getBarWidthPercent(1000, 1005, minTime, maxTime), 1.5);
});

test('calculateScheduleDiff computes item deltas, shift types, and assignee changes', () => {
  const nonSlippingProp = DEMO_PROPOSALS[0];
  const diffNonSlipping = calculateScheduleDiff(DEMO_PLANNING_TASKS, nonSlippingProp.tasks);

  assert.equal(diffNonSlipping.summary.totalTasks, 8);
  assert.ok(diffNonSlipping.diffItems.length === 8);

  const slippingProp = DEMO_PROPOSALS[1];
  const diffSlipping = calculateScheduleDiff(DEMO_PLANNING_TASKS, slippingProp.tasks);

  assert.ok(diffSlipping.summary.delayedCount > 0, 'Slipping proposal should have delayed tasks');
  assert.ok(diffSlipping.summary.hasSlippingTasks === true);

  // Verify DSP-10 reassigned to Quinn QA in Proposal 2
  const dsp10 = diffSlipping.diffItems.find((i) => i.itemKey === 'DSP-10');
  assert.ok(dsp10);
  assert.equal(dsp10.proposedAssignee, 'Quinn QA');
  assert.equal(dsp10.assigneeChanged, true);
  assert.equal(dsp10.shiftType, 'SLIP');
});

test('evaluateDeadlineGating enforces mandatory client/PM confirmation for slipping deadlines', () => {
  // 1. Non-slipping proposal (Proposal 1)
  const prop1 = DEMO_PROPOSALS[0];
  const gating1 = evaluateDeadlineGating(prop1, null);

  assert.equal(gating1.requiresConfirmation, false);
  assert.equal(gating1.canApprove, true);

  // 2. Slipping proposal without consent (Proposal 2)
  const prop2 = DEMO_PROPOSALS[1];
  const gating2Unconfirmed = evaluateDeadlineGating(prop2, null);

  assert.equal(gating2Unconfirmed.requiresConfirmation, true);
  assert.equal(gating2Unconfirmed.isConfirmed, false);
  assert.equal(gating2Unconfirmed.canApprove, false);
  assert.ok(gating2Unconfirmed.slippingMilestones.length > 0);
  assert.ok(gating2Unconfirmed.delayDays > 0);

  // 3. Slipping proposal WITH consent
  const consent = { confirmed: true, confirmedBy: 'pm-id' };
  const gating2Confirmed = evaluateDeadlineGating(prop2, consent);

  assert.equal(gating2Confirmed.requiresConfirmation, true);
  assert.equal(gating2Confirmed.isConfirmed, true);
  assert.equal(gating2Confirmed.canApprove, true);
});

test('formatShift formats day counts into user-friendly labels', () => {
  assert.equal(formatShift(3), '+3d delay');
  assert.equal(formatShift(-2), '-2d advanced');
  assert.equal(formatShift(0), 'On time');
});

test('formatDate formats valid dates and returns em-dash on null', () => {
  assert.equal(formatDate(null), '—');
  assert.equal(formatDate(undefined), '—');
  assert.ok(formatDate('2026-11-24').includes('Nov'));
  assert.ok(formatDate('2026-11-24').includes('2026'));
});

test('formatDuration formats minutes into hours and minutes', () => {
  assert.equal(formatDuration(0), '0h');
  assert.equal(formatDuration(null), '0h');
  assert.equal(formatDuration(60), '1h');
  assert.equal(formatDuration(300), '5h');
  assert.equal(formatDuration(90), '1h 30m');
  assert.equal(formatDuration(45), '45m');
});
