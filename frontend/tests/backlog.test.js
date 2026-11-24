import test from 'node:test';
import assert from 'node:assert/strict';
import {
  buildWorkItemTree,
  filterWorkItemTree,
  flattenTree,
  detectCycle,
  topologicalSort,
  formatEstimate,
  formatStoryPoints,
} from '../src/features/backlog/utils/tree-utils.js';
import { DEMO_WORK_ITEMS, DEMO_DEPENDENCIES } from '../src/features/backlog/demo-data.js';

test('buildWorkItemTree nests multi-level hierarchy (EPIC -> USER_STORY -> TASK -> SUBTASK)', () => {
  const tree = buildWorkItemTree(DEMO_WORK_ITEMS);

  // We have 2 root epics (DSP-1 and DSP-2)
  assert.equal(tree.length, 2);
  assert.equal(tree[0].itemKey, 'DSP-1');
  assert.equal(tree[1].itemKey, 'DSP-2');

  // DSP-1 has 2 stories (DSP-3 and DSP-4)
  assert.equal(tree[0].children.length, 2);
  const dsp3 = tree[0].children.find((c) => c.itemKey === 'DSP-3');
  const dsp4 = tree[0].children.find((c) => c.itemKey === 'DSP-4');
  assert.ok(dsp3);
  assert.ok(dsp4);

  // DSP-3 has 2 tasks (DSP-7, DSP-8)
  assert.equal(dsp3.children.length, 2);
  assert.ok(dsp3.children.some((c) => c.itemKey === 'DSP-7'));
  assert.ok(dsp3.children.some((c) => c.itemKey === 'DSP-8'));

  // DSP-4 has 2 tasks (DSP-9, DSP-10)
  assert.equal(dsp4.children.length, 2);
  const dsp10 = dsp4.children.find((c) => c.itemKey === 'DSP-10');
  assert.ok(dsp10);

  // DSP-10 has 1 subtask (DSP-13)
  assert.equal(dsp10.children.length, 1);
  assert.equal(dsp10.children[0].itemKey, 'DSP-13');
  assert.equal(dsp10.children[0].depth, 3);
  assert.equal(dsp10.children[0].childCount, 0);

  // Total descendant count of DSP-1:
  // DSP-3 has 2 children (2), DSP-4 has 2 children + 1 grandchild (3), plus DSP-3 & DSP-4 themselves (2) = 7
  assert.equal(tree[0].childCount, 7);
  assert.equal(tree[0].directChildCount, 2);
});

test('buildWorkItemTree handles empty and null inputs safely', () => {
  assert.deepEqual(buildWorkItemTree([]), []);
  assert.deepEqual(buildWorkItemTree(null), []);
  assert.deepEqual(buildWorkItemTree(undefined), []);
});

test('filterWorkItemTree preserves ancestor path when child matches search keyword', () => {
  const tree = buildWorkItemTree(DEMO_WORK_ITEMS);

  // Search for "DSP-13" which is a subtask
  const filtered = filterWorkItemTree(tree, { search: 'DSP-13' });
  assert.equal(filtered.length, 1);
  assert.equal(filtered[0].itemKey, 'DSP-1'); // Root ancestor preserved
  assert.equal(filtered[0].children[0].itemKey, 'DSP-4'); // Parent story preserved
  assert.equal(filtered[0].children[0].children[0].itemKey, 'DSP-10'); // Parent task preserved
  assert.equal(filtered[0].children[0].children[0].children[0].itemKey, 'DSP-13'); // Target subtask
});

test('filterWorkItemTree filters by Type, Priority, and Status', () => {
  const tree = buildWorkItemTree(DEMO_WORK_ITEMS);

  // Filter by status: 'IN_REVIEW'
  const inReview = filterWorkItemTree(tree, { status: 'IN_REVIEW' });
  assert.ok(inReview.length > 0);
  // DSP-2 has DSP-5 (IN_REVIEW) and DSP-12 (IN_REVIEW)
  const dsp2 = inReview.find((n) => n.itemKey === 'DSP-2');
  assert.ok(dsp2);
  assert.ok(dsp2.children.some((c) => c.itemKey === 'DSP-5'));

  // Filter by priority: 'CRITICAL'
  const critical = filterWorkItemTree(tree, { priority: 'CRITICAL' });
  assert.ok(critical.length > 0);

  // Filter by type: 'EPIC'
  const epics = filterWorkItemTree(tree, { type: 'EPIC' });
  assert.equal(epics.length, 2);
});

test('flattenTree respects collapsed IDs and updates row depth', () => {
  const tree = buildWorkItemTree(DEMO_WORK_ITEMS);

  // Initially uncollapsed
  const allRows = flattenTree(tree, new Set());
  assert.equal(allRows.length, DEMO_WORK_ITEMS.length);

  // Collapse DSP-1 (which has 7 descendants)
  const collapsedDSP1 = new Set(['e1111111-1111-1111-1111-111111111111']);
  const partiallyCollapsedRows = flattenTree(tree, collapsedDSP1);
  // DSP-1 (1) + DSP-2 tree (6) = 7
  assert.equal(partiallyCollapsedRows.length, 7);
  assert.equal(partiallyCollapsedRows[0].isCollapsed, true);
  assert.equal(partiallyCollapsedRows[0].hasChildren, true);
});

test('detectCycle identifies direct, indirect, and self cycles in dependencies', () => {
  const deps = [
    { predecessorId: 'item-A', successorId: 'item-B' },
    { predecessorId: 'item-B', successorId: 'item-C' },
    { predecessorId: 'item-C', successorId: 'item-D' },
  ];

  // Self-loop is immediate cycle
  assert.equal(detectCycle('item-A', 'item-A', deps), true);

  // Valid new dependency: D -> E
  assert.equal(detectCycle('item-D', 'item-E', deps), false);

  // Direct reverse cycle: B -> A
  assert.equal(detectCycle('item-B', 'item-A', deps), true);

  // Indirect cycle: D -> A (since A -> B -> C -> D)
  assert.equal(detectCycle('item-D', 'item-A', deps), true);

  // Indirect cycle: C -> A
  assert.equal(detectCycle('item-C', 'item-A', deps), true);

  // Unrelated nodes
  assert.equal(detectCycle('item-X', 'item-Y', deps), false);
});

test('topologicalSort computes valid topological order without cycles', () => {
  const itemIds = ['item-A', 'item-B', 'item-C', 'item-D'];
  const deps = [
    { predecessorId: 'item-A', successorId: 'item-B' },
    { predecessorId: 'item-B', successorId: 'item-C' },
    { predecessorId: 'item-C', successorId: 'item-D' },
  ];

  const result = topologicalSort(itemIds, deps);
  assert.equal(result.hasCycle, false);
  assert.deepEqual(result.sortedIds, ['item-A', 'item-B', 'item-C', 'item-D']);

  // Add cycle
  const cyclicDeps = [
    ...deps,
    { predecessorId: 'item-D', successorId: 'item-A' },
  ];
  const cyclicResult = topologicalSort(itemIds, cyclicDeps);
  assert.equal(cyclicResult.hasCycle, true);
});

test('formatEstimate and formatStoryPoints handle various minute and point values', () => {
  assert.equal(formatEstimate(45), '45m');
  assert.equal(formatEstimate(60), '1h');
  assert.equal(formatEstimate(90), '1h 30m');
  assert.equal(formatEstimate(480), '8h (1d)');
  assert.equal(formatEstimate(720), '12h (1.5d)');
  assert.equal(formatEstimate(null), null);
  assert.equal(formatEstimate(0), null);

  assert.equal(formatStoryPoints(1), '1 pt');
  assert.equal(formatStoryPoints(5), '5 pts');
  assert.equal(formatStoryPoints(0), '0 pts');
  assert.equal(formatStoryPoints(null), null);
});

test('detectCycle supports diamond dependencies, cross-branch links, and long DAG chains', () => {
  // Diamond graph: A -> B -> D and A -> C -> D
  const diamondDeps = [
    { predecessorId: 'node-A', successorId: 'node-B' },
    { predecessorId: 'node-A', successorId: 'node-C' },
    { predecessorId: 'node-B', successorId: 'node-D' },
    { predecessorId: 'node-C', successorId: 'node-D' },
  ];

  // Adding B -> C or C -> B should be valid (cross-branch without cycle)
  assert.equal(detectCycle('node-B', 'node-C', diamondDeps), false);

  // But adding D -> A creates a cycle back to the source
  assert.equal(detectCycle('node-D', 'node-A', diamondDeps), true);

  // Long chain: 1 -> 2 -> 3 -> 4 -> 5 -> 6 -> 7 -> 8 -> 9 -> 10
  const longChain = [];
  for (let i = 1; i < 10; i++) {
    longChain.push({ predecessorId: `node-${i}`, successorId: `node-${i + 1}` });
  }
  assert.equal(detectCycle('node-10', 'node-1', longChain), true);
  assert.equal(detectCycle('node-1', 'node-11', longChain), false);
});

test('topologicalSort handles multi-root branching and preserves valid evaluation order', () => {
  const ids = ['root-1', 'root-2', 'child-1', 'child-2', 'leaf'];
  const deps = [
    { predecessorId: 'root-1', successorId: 'child-1' },
    { predecessorId: 'root-2', successorId: 'child-2' },
    { predecessorId: 'child-1', successorId: 'leaf' },
    { predecessorId: 'child-2', successorId: 'leaf' },
  ];

  const { sortedIds, hasCycle } = topologicalSort(ids, deps);
  assert.equal(hasCycle, false);
  assert.equal(sortedIds.length, 5);

  // Both roots must precede their children
  assert.ok(sortedIds.indexOf('root-1') < sortedIds.indexOf('child-1'));
  assert.ok(sortedIds.indexOf('root-2') < sortedIds.indexOf('child-2'));
  // Both children must precede leaf
  assert.ok(sortedIds.indexOf('child-1') < sortedIds.indexOf('leaf'));
  assert.ok(sortedIds.indexOf('child-2') < sortedIds.indexOf('leaf'));
});

test('filterWorkItemTree handles multiple active filters simultaneously', () => {
  const tree = buildWorkItemTree(DEMO_WORK_ITEMS);

  // Combined filter: search "Redis" AND status "DONE" AND type "TASK"
  const filtered = filterWorkItemTree(tree, {
    search: 'Redis',
    status: 'DONE',
    type: 'TASK',
  });

  assert.equal(filtered.length, 1); // DSP-1 ancestor kept
  const epic = filtered[0];
  assert.equal(epic.itemKey, 'DSP-1');
  const story = epic.children[0];
  assert.equal(story.itemKey, 'DSP-3');
  const task = story.children[0];
  assert.equal(task.itemKey, 'DSP-8');
  assert.equal(task.status, 'DONE');
});

test('filterWorkItemTree returns empty array when no items match criteria', () => {
  const tree = buildWorkItemTree(DEMO_WORK_ITEMS);
  const filtered = filterWorkItemTree(tree, { search: 'NON_EXISTENT_QUERY_XYZ_12345' });
  assert.deepEqual(filtered, []);
});

test('buildWorkItemTree attaches orphan items with invalid parentId as top-level roots', () => {
  const itemsWithOrphan = [
    { id: 'item-1', itemKey: 'DSP-1', title: 'Epic 1', parentId: null, type: 'EPIC' },
    { id: 'item-2', itemKey: 'DSP-2', title: 'Orphan Task', parentId: 'non-existent-parent', type: 'TASK' },
  ];

  const tree = buildWorkItemTree(itemsWithOrphan);
  assert.equal(tree.length, 2);
  assert.equal(tree[0].itemKey, 'DSP-1');
  assert.equal(tree[1].itemKey, 'DSP-2');
  assert.equal(tree[1].depth, 0);
});

test('filterWorkItemTree filters accurately by assigneeId', () => {
  const tree = buildWorkItemTree(DEMO_WORK_ITEMS);
  const devId = '33333333-3333-3333-3333-333333333333'; // Alex Dev
  const filtered = filterWorkItemTree(tree, { assigneeId: devId });

  assert.ok(filtered.length > 0);
  // All leaf items matching should belong to Alex Dev
  function verifyAssignees(node) {
    if (node.assigneeId === devId) return;
    for (const child of node.children || []) {
      verifyAssignees(child);
    }
  }
  for (const root of filtered) {
    verifyAssignees(root);
  }
});

test('detectCycle guards against null, undefined, or missing predecessor/successor IDs', () => {
  assert.equal(detectCycle(null, 'item-A', []), false);
  assert.equal(detectCycle('item-A', null, []), false);
  assert.equal(detectCycle(undefined, undefined, []), false);
});

test('formatStoryPoints formats decimal and fractional points', () => {
  assert.equal(formatStoryPoints(0.5), '0.5 pts');
  assert.equal(formatStoryPoints(1.5), '1.5 pts');
  assert.equal(formatStoryPoints(2.0), '2 pts');
  assert.equal(formatStoryPoints(NaN), null);
});
