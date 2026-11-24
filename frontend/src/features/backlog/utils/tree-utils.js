/**
 * Backlog Tree, Filter, and DAG Utilities
 */

/**
 * Builds a hierarchical tree structure from a flat array of work items.
 * Hierarchy supported: EPIC -> USER_STORY -> TASK -> SUBTASK
 * Unmatched or top-level items become root nodes.
 */
export function buildWorkItemTree(workItems = []) {
  if (!Array.isArray(workItems) || workItems.length === 0) {
    return [];
  }

  // Clone items to avoid mutating input
  const itemMap = new Map();
  const roots = [];

  // Index items by both id and itemKey for flexible parent mapping
  for (const item of workItems) {
    itemMap.set(item.id, { ...item, children: [] });
    if (item.itemKey) {
      itemMap.set(item.itemKey, itemMap.get(item.id));
    }
  }

  for (const item of workItems) {
    const node = itemMap.get(item.id);
    const parentId = item.parentId;

    if (parentId && itemMap.has(parentId)) {
      const parentNode = itemMap.get(parentId);
      // Avoid duplicate child attachments if parent was retrieved by key or id
      if (!parentNode.children.some((c) => c.id === node.id)) {
        parentNode.children.push(node);
      }
    } else {
      roots.push(node);
    }
  }

  // Recursively calculate depth and descendant counts
  function annotateNode(node, depth = 0) {
    node.depth = depth;
    let totalDescendants = node.children.length;
    for (const child of node.children) {
      annotateNode(child, depth + 1);
      totalDescendants += child.childCount || 0;
    }
    node.childCount = totalDescendants;
    node.directChildCount = node.children.length;
    return node;
  }

  for (const root of roots) {
    annotateNode(root, 0);
  }

  return roots;
}

/**
 * Filter work item tree preserving parent context if any descendant matches.
 */
export function filterWorkItemTree(treeNodes = [], filters = {}) {
  const {
    search = '',
    type = 'ALL',
    status = 'ALL',
    priority = 'ALL',
    assigneeId = 'ALL',
  } = filters;

  const searchNormalized = (search || '').trim().toLowerCase();

  function itemMatches(item) {
    if (searchNormalized) {
      const titleMatch = item.title?.toLowerCase().includes(searchNormalized);
      const keyMatch = item.itemKey?.toLowerCase().includes(searchNormalized);
      if (!titleMatch && !keyMatch) return false;
    }

    if (type && type !== 'ALL') {
      const normalizedType = item.type === 'STORY' ? 'USER_STORY' : item.type;
      const targetType = type === 'STORY' ? 'USER_STORY' : type;
      if (normalizedType !== targetType) return false;
    }

    if (status && status !== 'ALL' && item.status !== status) {
      return false;
    }

    if (priority && priority !== 'ALL' && item.priority !== priority) {
      return false;
    }

    if (assigneeId && assigneeId !== 'ALL' && item.assigneeId !== assigneeId) {
      return false;
    }

    return true;
  }

  function filterNode(node) {
    const isSelfMatch = itemMatches(node);
    const filteredChildren = [];

    for (const child of node.children || []) {
      const filteredChild = filterNode(child);
      if (filteredChild) {
        filteredChildren.push(filteredChild);
      }
    }

    // Keep node if it matches itself or any child matches
    if (isSelfMatch || filteredChildren.length > 0) {
      return {
        ...node,
        children: filteredChildren,
        childCount: filteredChildren.length,
      };
    }

    return null;
  }

  const results = [];
  for (const root of treeNodes) {
    const filtered = filterNode(root);
    if (filtered) results.push(filtered);
  }
  return results;
}

/**
 * Flatten tree into display rows with collapse tracking.
 */
export function flattenTree(treeNodes = [], collapsedIds = new Set()) {
  const rows = [];

  function traverse(node, depth = 0) {
    const isCollapsed = collapsedIds.has(node.id) || collapsedIds.has(node.itemKey);
    const hasChildren = (node.children || []).length > 0;

    rows.push({
      ...node,
      depth,
      isCollapsed,
      hasChildren,
    });

    if (!isCollapsed && hasChildren) {
      for (const child of node.children) {
        traverse(child, depth + 1);
      }
    }
  }

  for (const root of treeNodes) {
    traverse(root, 0);
  }

  return rows;
}

/**
 * Cycle detection for adding a dependency:
 * Checks if adding edge predecessorId -> successorId creates a directed cycle.
 * A cycle occurs if there is already a reachable path from successorId to predecessorId.
 */
export function detectCycle(predecessorId, successorId, dependencies = []) {
  if (!predecessorId || !successorId) return false;
  if (predecessorId === successorId) return true; // Self-dependency is an immediate cycle

  // Build adjacency list: node -> outgoing successors
  const adj = new Map();
  for (const dep of dependencies) {
    const from = dep.predecessorId;
    const to = dep.successorId;
    if (!adj.has(from)) adj.set(from, []);
    adj.get(from).push(to);
  }

  // Traverse from successorId to check if predecessorId is reachable
  const visited = new Set();
  const queue = [successorId];

  while (queue.length > 0) {
    const current = queue.shift();
    if (current === predecessorId) {
      return true; // Path exists from successor to predecessor!
    }
    if (!visited.has(current)) {
      visited.add(current);
      const nextNodes = adj.get(current) || [];
      for (const next of nextNodes) {
        if (!visited.has(next)) {
          queue.push(next);
        }
      }
    }
  }

  return false;
}

/**
 * Topological sort of items given dependencies.
 * Returns { sortedIds: string[], hasCycle: boolean }
 */
export function topologicalSort(itemIds = [], dependencies = []) {
  const inDegree = new Map();
  const adj = new Map();

  for (const id of itemIds) {
    inDegree.set(id, 0);
    adj.set(id, []);
  }

  for (const dep of dependencies) {
    const u = dep.predecessorId;
    const v = dep.successorId;
    if (inDegree.has(v) && inDegree.has(u)) {
      adj.get(u).push(v);
      inDegree.set(v, inDegree.get(v) + 1);
    }
  }

  const queue = [];
  for (const [id, deg] of inDegree.entries()) {
    if (deg === 0) queue.push(id);
  }

  const sortedIds = [];
  while (queue.length > 0) {
    const u = queue.shift();
    sortedIds.push(u);
    for (const v of adj.get(u) || []) {
      const nextDeg = inDegree.get(v) - 1;
      inDegree.set(v, nextDeg);
      if (nextDeg === 0) queue.push(v);
    }
  }

  const hasCycle = sortedIds.length < itemIds.length;
  return { sortedIds, hasCycle };
}

/**
 * Formats estimate in minutes to a human-readable string.
 * e.g., 60 -> "1h", 90 -> "1h 30m", 480 -> "8h (1d)"
 */
export function formatEstimate(minutes) {
  if (minutes == null || isNaN(minutes) || minutes <= 0) return null;
  const mins = Number(minutes);
  const hours = Math.floor(mins / 60);
  const remainingMins = mins % 60;

  if (hours === 0) {
    return `${remainingMins}m`;
  }
  if (remainingMins === 0) {
    const days = (hours / 8).toFixed(1).replace(/\.0$/, '');
    return hours >= 8 ? `${hours}h (${days}d)` : `${hours}h`;
  }
  return `${hours}h ${remainingMins}m`;
}

/**
 * Format story points.
 */
export function formatStoryPoints(points) {
  if (points == null || isNaN(points)) return null;
  const p = Number(points);
  return `${p} pt${p === 1 ? '' : 's'}`;
}
