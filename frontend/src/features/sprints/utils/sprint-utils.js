/**
 * DevSphere Sprint & Kanban Utility Functions
 * Supports WCAG accessible column partitioning, progress metrics calculation,
 * Quality Gate evaluation, and PM acceptance policy enforcement.
 */

export const KANBAN_COLUMNS = [
  { id: 'TO_DO', title: 'To Do', ariaLabel: 'To Do column', color: 'slate' },
  { id: 'IN_PROGRESS', title: 'In Progress', ariaLabel: 'In Progress column', color: 'blue' },
  { id: 'IN_REVIEW', title: 'In Review', ariaLabel: 'In Review column', color: 'purple' },
  { id: 'DONE', title: 'Done', ariaLabel: 'Done column', color: 'emerald' },
];

/**
 * Normalizes status string to one of the 4 canonical Kanban column IDs.
 */
export function normalizeStatus(status) {
  if (!status) return 'TO_DO';
  const upper = String(status).trim().toUpperCase();
  if (upper === 'TODO' || upper === 'TO_DO') return 'TO_DO';
  if (upper === 'IN_PROGRESS') return 'IN_PROGRESS';
  if (upper === 'IN_REVIEW') return 'IN_REVIEW';
  if (upper === 'DONE' || upper === 'CLOSED') return 'DONE';
  return 'TO_DO';
}

/**
 * Partitions a flat list of work items into columns.
 */
export function partitionWorkItemsByColumn(items = []) {
  const partitioned = {
    TO_DO: [],
    IN_PROGRESS: [],
    IN_REVIEW: [],
    DONE: [],
  };

  if (!Array.isArray(items)) {
    return partitioned;
  }

  for (const item of items) {
    const colId = normalizeStatus(item.status);
    if (partitioned[colId]) {
      partitioned[colId].push(item);
    } else {
      partitioned.TO_DO.push(item);
    }
  }

  return partitioned;
}

/**
 * Calculates aggregate progress metrics for a sprint and its work items.
 */
export function calculateSprintMetrics(items = [], sprint = null) {
  const partitioned = partitionWorkItemsByColumn(items);

  const columnCounts = {
    TO_DO: partitioned.TO_DO.length,
    IN_PROGRESS: partitioned.IN_PROGRESS.length,
    IN_REVIEW: partitioned.IN_REVIEW.length,
    DONE: partitioned.DONE.length,
  };

  const getPoints = (list) =>
    list.reduce((sum, item) => sum + (Number(item.storyPoints) || 0), 0);

  const columnPoints = {
    TO_DO: getPoints(partitioned.TO_DO),
    IN_PROGRESS: getPoints(partitioned.IN_PROGRESS),
    IN_REVIEW: getPoints(partitioned.IN_REVIEW),
    DONE: getPoints(partitioned.DONE),
  };

  const totalCalculatedPoints =
    columnPoints.TO_DO +
    columnPoints.IN_PROGRESS +
    columnPoints.IN_REVIEW +
    columnPoints.DONE;

  const committedPoints =
    sprint?.committedPoints != null
      ? Number(sprint.committedPoints)
      : totalCalculatedPoints;

  const completedPoints = columnPoints.DONE;
  const remainingPoints = Math.max(0, committedPoints - completedPoints);

  const completionPercentage =
    committedPoints > 0
      ? Math.min(100, Math.round((completedPoints / committedPoints) * 100))
      : 0;

  const totalItems = Array.isArray(items) ? items.length : 0;
  const remainingItems = totalItems - columnCounts.DONE;

  const totalSpentMinutes = Array.isArray(items)
    ? items.reduce((sum, item) => sum + (Number(item.timeSpentMinutes) || 0), 0)
    : 0;

  const totalSpentHours = (totalSpentMinutes / 60).toFixed(1);

  return {
    totalItems,
    remainingItems,
    committedPoints,
    completedPoints,
    remainingPoints,
    completionPercentage,
    totalSpentMinutes,
    totalSpentHours,
    columnCounts,
    columnPoints,
  };
}

/**
 * Returns the next column in sequence, or null if already in DONE.
 */
export function getNextColumn(currentStatus) {
  const status = normalizeStatus(currentStatus);
  if (status === 'TO_DO') return 'IN_PROGRESS';
  if (status === 'IN_PROGRESS') return 'IN_REVIEW';
  if (status === 'IN_REVIEW') return 'DONE';
  return null;
}

/**
 * Returns the previous column in sequence, or null if already in TO_DO.
 */
export function getPrevColumn(currentStatus) {
  const status = normalizeStatus(currentStatus);
  if (status === 'DONE') return 'IN_REVIEW';
  if (status === 'IN_REVIEW') return 'IN_PROGRESS';
  if (status === 'IN_PROGRESS') return 'TO_DO';
  return null;
}

/**
 * Evaluates Quality Gate data into a simplified status model.
 */
export function evaluateQualityGate(gate) {
  if (!gate) {
    return {
      status: 'PENDING',
      passed: false,
      label: 'Pending Evidence',
      badgeClass: 'pending',
    };
  }

  const rawStatus = (gate.status || '').toUpperCase();

  if (gate.passed === true || rawStatus === 'PASSED') {
    return {
      status: 'PASSED',
      passed: true,
      label: 'Passed',
      badgeClass: 'passed',
    };
  }

  if (rawStatus === 'FAILED' || (gate.blockers && gate.blockers.length > 0 && rawStatus !== 'PENDING_EVIDENCE')) {
    return {
      status: 'FAILED',
      passed: false,
      label: 'Failed',
      badgeClass: 'failed',
    };
  }

  return {
    status: 'PENDING',
    passed: false,
    label: rawStatus === 'STALE_EVIDENCE' ? 'Stale' : 'Pending',
    badgeClass: 'pending',
  };
}

/**
 * Enforces PM business role check.
 */
export function isPmUser(user) {
  if (!user) return false;
  const role = (user.role || user.systemRole || user.projectRole || '').toUpperCase();
  if (role) {
    return role === 'PM' || role === 'ADMIN' || role === 'OWNER';
  }
  if (user.email === 'pm@devsphere.io' || user.email === 'admin@devsphere.io') return true;
  return false;
}

/**
 * Formats minutes to human-readable duration string (e.g. 240 -> "4h", 75 -> "1h 15m")
 */
export function formatMinutes(minutes) {
  if (!minutes || minutes <= 0) return '0h';
  const hrs = Math.floor(minutes / 60);
  const mins = minutes % 60;
  if (hrs > 0 && mins > 0) return `${hrs}h ${mins}m`;
  if (hrs > 0) return `${hrs}h`;
  return `${mins}m`;
}

/**
 * Formats ISO or YYYY-MM-DD date range for sprint display.
 */
export function formatDateRange(startDate, endDate) {
  if (!startDate && !endDate) return 'No dates set';
  try {
    const formatDate = (d) => {
      if (!d) return '';
      const date = new Date(d);
      if (isNaN(date.getTime())) return d;
      return date.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
    };
    const startStr = formatDate(startDate);
    const endStr = formatDate(endDate);
    if (startStr && endStr) return `${startStr} – ${endStr}`;
    return startStr || endStr;
  } catch {
    return `${startDate || ''} – ${endDate || ''}`;
  }
}
