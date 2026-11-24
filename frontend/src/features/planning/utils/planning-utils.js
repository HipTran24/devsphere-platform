/**
 * DevSphere Planning & Gantt Timeline Utilities
 * Provides:
 * - Critical Path Method (CPM) forward/backward pass and slack calculation
 * - Timeline coordinate mapping and date boundary calculations
 * - Proposal schedule before/after diff computation
 * - Deadline gating & milestone impact evaluation
 */

/**
 * Normalizes any date representation (string, timestamp, Date) to a Date instance.
 */
export function toValidDate(val, fallback = new Date()) {
  if (!val) return fallback ? new Date(fallback) : new Date();
  const d = new Date(val);
  return isNaN(d.getTime()) ? (fallback ? new Date(fallback) : new Date()) : d;
}

/**
 * Computes Critical Path Method (CPM) metrics:
 * - Forward pass: Early Start (ES) and Early Finish (EF)
 * - Backward pass: Late Start (LS) and Late Finish (LF)
 * - Total Float (Slack): LF - EF (or LS - ES)
 * - Critical tasks: Total Float <= 0
 */
export function calculateCriticalPath(tasks = [], dependencies = []) {
  if (!Array.isArray(tasks) || tasks.length === 0) {
    return { tasks: [], criticalTaskIds: new Set(), makespanDays: 0 };
  }

  // Build task map
  const taskMap = new Map();
  const successorsMap = new Map();
  const predecessorsMap = new Map();

  tasks.forEach((t) => {
    const id = t.id || t.workItemId || t.itemKey;
    const startDate = toValidDate(t.startAt || t.startDate);
    const finishDate = toValidDate(t.finishAt || t.endDate, new Date(startDate.getTime() + 86400000));
    
    // Duration in days (minimum 1 day or proportional to estimateMinutes)
    const rawDays = Math.max(1, Math.round((finishDate.getTime() - startDate.getTime()) / (1000 * 60 * 60 * 24)));
    const durationDays = t.estimateMinutes ? Math.max(1, Math.ceil(t.estimateMinutes / 480)) : rawDays;

    taskMap.set(id, {
      ...t,
      _id: id,
      _duration: durationDays,
      _startDate: startDate,
      _finishDate: finishDate,
      earlyStart: 0,
      earlyFinish: durationDays,
      lateStart: 0,
      lateFinish: durationDays,
      totalFloat: 0,
      isCritical: false,
    });

    successorsMap.set(id, []);
    predecessorsMap.set(id, []);
  });

  // Populate dependency links
  if (Array.isArray(dependencies)) {
    dependencies.forEach((dep) => {
      const pred = dep.predecessorId || dep.from;
      const succ = dep.successorId || dep.to;
      const lag = Number(dep.lagMinutes || 0) / 480; // convert to days

      if (taskMap.has(pred) && taskMap.has(succ)) {
        successorsMap.get(pred).push({ succId: succ, lag });
        predecessorsMap.get(succ).push({ predId: pred, lag });
      }
    });
  }

  // Topologically sort tasks or find in-degrees
  const inDegree = new Map();
  taskMap.forEach((_, id) => {
    inDegree.set(id, (predecessorsMap.get(id) || []).length);
  });

  const queue = [];
  inDegree.forEach((deg, id) => {
    if (deg === 0) queue.push(id);
  });

  const topoOrder = [];
  const inDegCopy = new Map(inDegree);

  while (queue.length > 0) {
    const currentId = queue.shift();
    topoOrder.push(currentId);

    const succs = successorsMap.get(currentId) || [];
    succs.forEach(({ succId }) => {
      const nextDeg = inDegCopy.get(succId) - 1;
      inDegCopy.set(succId, nextDeg);
      if (nextDeg === 0) {
        queue.push(succId);
      }
    });
  }

  // If there are cycles, fallback to taskMap keys
  const orderedIds = topoOrder.length === taskMap.size ? topoOrder : Array.from(taskMap.keys());

  // 1. FORWARD PASS: calculate ES and EF
  let maxProjectFinish = 0;
  orderedIds.forEach((id) => {
    const node = taskMap.get(id);
    const preds = predecessorsMap.get(id) || [];

    if (preds.length === 0) {
      node.earlyStart = 0;
    } else {
      let maxPredEF = 0;
      preds.forEach(({ predId, lag }) => {
        const predNode = taskMap.get(predId);
        if (predNode) {
          maxPredEF = Math.max(maxPredEF, predNode.earlyFinish + lag);
        }
      });
      node.earlyStart = maxPredEF;
    }

    node.earlyFinish = node.earlyStart + node._duration;
    maxProjectFinish = Math.max(maxProjectFinish, node.earlyFinish);
  });

  // 2. BACKWARD PASS: calculate LF and LS
  const reverseOrder = [...orderedIds].reverse();
  reverseOrder.forEach((id) => {
    const node = taskMap.get(id);
    const succs = successorsMap.get(id) || [];

    if (succs.length === 0) {
      node.lateFinish = maxProjectFinish;
    } else {
      let minSuccLS = Infinity;
      succs.forEach(({ succId, lag }) => {
        const succNode = taskMap.get(succId);
        if (succNode) {
          minSuccLS = Math.min(minSuccLS, succNode.lateStart - lag);
        }
      });
      node.lateFinish = isFinite(minSuccLS) ? minSuccLS : maxProjectFinish;
    }

    node.lateStart = node.lateFinish - node._duration;
    // Slack / Float
    node.totalFloat = Math.max(0, Math.round((node.lateFinish - node.earlyFinish) * 10) / 10);
    node.isCritical = node.totalFloat <= 0;
  });

  const criticalTaskIds = new Set();
  const updatedTasks = tasks.map((t) => {
    const id = t.id || t.workItemId || t.itemKey;
    const cpmNode = taskMap.get(id);
    if (cpmNode) {
      if (cpmNode.isCritical) {
        criticalTaskIds.add(id);
      }
      return {
        ...t,
        isCritical: cpmNode.isCritical,
        totalFloat: cpmNode.totalFloat,
        earlyStart: cpmNode.earlyStart,
        earlyFinish: cpmNode.earlyFinish,
        lateStart: cpmNode.lateStart,
        lateFinish: cpmNode.lateFinish,
      };
    }
    return t;
  });

  return {
    tasks: updatedTasks,
    criticalTaskIds,
    makespanDays: maxProjectFinish,
  };
}

/**
 * Calculates start and end boundaries for the timeline grid
 */
export function calculateTimelineBounds(tasks = [], milestones = [], sprints = []) {
  const dates = [];

  tasks.forEach((t) => {
    if (t.startAt || t.startDate) dates.push(toValidDate(t.startAt || t.startDate).getTime());
    if (t.finishAt || t.endDate) dates.push(toValidDate(t.finishAt || t.endDate).getTime());
  });

  milestones.forEach((m) => {
    if (m.targetDate || m.date) dates.push(toValidDate(m.targetDate || m.date).getTime());
  });

  sprints.forEach((s) => {
    if (s.startDate) dates.push(toValidDate(s.startDate).getTime());
    if (s.endDate) dates.push(toValidDate(s.endDate).getTime());
  });

  const now = Date.now();
  dates.push(now);

  const minTime = Math.min(...dates);
  const maxTime = Math.max(...dates);

  // Buffer: 2 days before min, 5 days after max
  const minDate = new Date(minTime - 2 * 86400000);
  minDate.setHours(0, 0, 0, 0);

  const maxDate = new Date(maxTime + 5 * 86400000);
  maxDate.setHours(23, 59, 59, 999);

  const totalDays = Math.max(1, Math.ceil((maxDate.getTime() - minDate.getTime()) / (1000 * 60 * 60 * 24)));

  return {
    minDate,
    maxDate,
    totalDays,
    minTime: minDate.getTime(),
    maxTime: maxDate.getTime(),
  };
}

/**
 * Generates header intervals based on view mode ('DAYS', 'WEEKS', 'SPRINTS')
 */
export function generateTimelineIntervals(minDate, maxDate, viewMode = 'DAYS') {
  const intervals = [];
  const start = new Date(minDate);
  start.setHours(0, 0, 0, 0);

  const end = new Date(maxDate);
  end.setHours(23, 59, 59, 999);

  if (viewMode === 'DAYS') {
    const cur = new Date(start);
    while (cur <= end) {
      intervals.push({
        id: cur.toISOString().split('T')[0],
        date: new Date(cur),
        label: cur.toLocaleDateString('en-US', { month: 'short', day: 'numeric' }),
        subLabel: cur.toLocaleDateString('en-US', { weekday: 'narrow' }),
        isWeekend: cur.getDay() === 0 || cur.getDay() === 6,
      });
      cur.setDate(cur.getDate() + 1);
    }
  } else if (viewMode === 'WEEKS') {
    const cur = new Date(start);
    // Align to Sunday/Monday
    while (cur <= end) {
      const weekEnd = new Date(cur);
      weekEnd.setDate(weekEnd.getDate() + 6);
      intervals.push({
        id: `week-${cur.toISOString().split('T')[0]}`,
        date: new Date(cur),
        label: `W${getWeekNumber(cur)} (${cur.toLocaleDateString('en-US', { month: 'short', day: 'numeric' })})`,
        subLabel: `${cur.toLocaleDateString('en-US', { month: 'short', day: 'numeric' })} - ${weekEnd.toLocaleDateString('en-US', { month: 'short', day: 'numeric' })}`,
        isWeekend: false,
      });
      cur.setDate(cur.getDate() + 7);
    }
  } else if (viewMode === 'SPRINTS') {
    const cur = new Date(start);
    let sprintIndex = 1;
    while (cur <= end) {
      const sprintEnd = new Date(cur);
      sprintEnd.setDate(sprintEnd.getDate() + 13); // 2-week sprints
      intervals.push({
        id: `sprint-window-${sprintIndex}`,
        date: new Date(cur),
        label: `Sprint ${sprintIndex}`,
        subLabel: `${cur.toLocaleDateString('en-US', { month: 'short', day: 'numeric' })} - ${sprintEnd.toLocaleDateString('en-US', { month: 'short', day: 'numeric' })}`,
        isWeekend: false,
      });
      cur.setDate(cur.getDate() + 14);
      sprintIndex += 1;
    }
  }

  return intervals;
}

/**
 * Returns ISO week number for a given date
 */
export function getWeekNumber(date) {
  const d = new Date(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()));
  const dayNum = d.getUTCDay() || 7;
  d.setUTCDate(d.getUTCDate() + 4 - dayNum);
  const yearStart = new Date(Date.UTC(d.getUTCFullYear(), 0, 1));
  return Math.ceil(((d - yearStart) / 86400000 + 1) / 7);
}

/**
 * Computes horizontal percentage offset (0% - 100%) for a given timestamp
 */
export function getDatePositionPercent(dateVal, minTime, maxTime) {
  if (!dateVal || !minTime || !maxTime || maxTime <= minTime) return 0;
  const time = toValidDate(dateVal).getTime();
  const clamped = Math.max(minTime, Math.min(maxTime, time));
  const pct = ((clamped - minTime) / (maxTime - minTime)) * 100;
  return Math.round(pct * 100) / 100;
}

/**
 * Computes bar width percentage (0% - 100%) between start and finish dates
 */
export function getBarWidthPercent(startVal, finishVal, minTime, maxTime) {
  const left = getDatePositionPercent(startVal, minTime, maxTime);
  const right = getDatePositionPercent(finishVal, minTime, maxTime);
  return Math.max(1.5, Math.round((right - left) * 100) / 100);
}

/**
 * Computes Before/After Schedule Diff between baseline and proposed schedule
 */
export function calculateScheduleDiff(baselineTasks = [], proposedTasks = []) {
  const baselineMap = new Map();
  baselineTasks.forEach((t) => {
    const key = t.itemKey || t.tempKey || t.id;
    baselineMap.set(key, t);
  });

  const diffItems = [];
  let delayedCount = 0;
  let advancedCount = 0;
  let unchangedCount = 0;
  let maxDelayDays = 0;

  proposedTasks.forEach((proposed) => {
    const key = proposed.itemKey || proposed.tempKey || proposed.workItemId;
    const baseline = baselineMap.get(key) || {};

    const bStart = baseline.startAt || baseline.startDate;
    const bFinish = baseline.finishAt || baseline.endDate;
    const pStart = proposed.startAt || proposed.start;
    const pFinish = proposed.finishAt || proposed.finish;

    let delayDays = 0;
    if (bFinish && pFinish) {
      const bTime = toValidDate(bFinish).getTime();
      const pTime = toValidDate(pFinish).getTime();
      delayDays = Math.round((pTime - bTime) / (1000 * 60 * 60 * 24));
    }

    if (delayDays > 0) {
      delayedCount += 1;
      maxDelayDays = Math.max(maxDelayDays, delayDays);
    } else if (delayDays < 0) {
      advancedCount += 1;
    } else {
      unchangedCount += 1;
    }

    const assigneeChanged = Boolean(
      proposed.developerId &&
      baseline.assigneeId &&
      proposed.developerId !== baseline.assigneeId
    );

    diffItems.push({
      itemKey: key,
      title: proposed.title || baseline.title || key,
      baselineStart: bStart,
      baselineFinish: bFinish,
      proposedStart: pStart,
      proposedFinish: pFinish,
      delayDays,
      shiftLabel: formatShift(delayDays),
      shiftType: delayDays > 0 ? 'SLIP' : delayDays < 0 ? 'ADVANCED' : 'UNCHANGED',
      currentAssignee: baseline.assigneeName || 'Alex Dev',
      proposedAssignee: proposed.developerName || (assigneeChanged ? 'Quinn QA' : (baseline.assigneeName || 'Alex Dev')),
      assigneeChanged,
      estimateMinutes: proposed.estimateMinutes || baseline.estimateMinutes || 300,
    });
  });

  return {
    diffItems,
    summary: {
      totalTasks: proposedTasks.length,
      delayedCount,
      advancedCount,
      unchangedCount,
      maxDelayDays,
      hasSlippingTasks: delayedCount > 0,
    },
  };
}

/**
 * Evaluates whether a proposal slips committed client deadlines
 */
export function evaluateDeadlineGating(preview = {}, consent = null) {
  const changesCommittedDeadline = Boolean(preview.changesCommittedDeadline);
  
  let currentDeadline = preview.currentCommittedDeadline;
  let proposedDeadline = preview.proposedCommittedDeadline;

  let delayDays = 0;
  if (currentDeadline && proposedDeadline) {
    const cTime = toValidDate(currentDeadline).getTime();
    const pTime = toValidDate(proposedDeadline).getTime();
    delayDays = Math.max(0, Math.round((pTime - cTime) / (1000 * 60 * 60 * 24)));
  }

  // Check milestone impacts
  const milestoneImpacts = Array.isArray(preview.milestoneImpacts) ? preview.milestoneImpacts : [];
  const slippingMilestones = milestoneImpacts.filter((m) => Number(m.delayDays || 0) > 0);

  const isSlipping = changesCommittedDeadline || delayDays > 0 || slippingMilestones.length > 0;
  const isConfirmed = Boolean(consent?.confirmed || preview.deadlineConfirmedAt);

  return {
    requiresConfirmation: isSlipping,
    isConfirmed,
    canApprove: !isSlipping || isConfirmed,
    delayDays,
    slippingMilestones,
    currentDeadline,
    proposedDeadline,
  };
}

/**
 * Formats shift in days (+2d slip / -1d advanced / On time)
 */
export function formatShift(delayDays) {
  if (delayDays > 0) return `+${delayDays}d delay`;
  if (delayDays < 0) return `${delayDays}d advanced`;
  return 'On time';
}

/**
 * Formats date into readable string (e.g. "Nov 25, 2026")
 */
export function formatDate(val, options = { month: 'short', day: 'numeric', year: 'numeric' }) {
  if (!val) return '—';
  try {
    const d = toValidDate(val);
    return d.toLocaleDateString('en-US', options);
  } catch {
    return '—';
  }
}

/**
 * Formats estimate or duration minutes into readable format (e.g. "5h" or "8h 30m")
 */
export function formatDuration(minutes) {
  if (!minutes || minutes <= 0) return '0h';
  const hours = Math.floor(minutes / 60);
  const mins = minutes % 60;
  if (hours > 0 && mins > 0) return `${hours}h ${mins}m`;
  if (hours > 0) return `${hours}h`;
  return `${mins}m`;
}
