"use client";

import { useState, useId } from "react";
import {
  ArrowRight,
  Plus,
  Trash2,
  AlertTriangle,
  Flame,
  Network,
} from "lucide-react";
import { detectCycle } from "../utils/tree-utils";
import EmptyState from "../../../components/ui/EmptyState";
import styles from "../styles/DependencyDag.module.css";

export default function DependencyDag({
  workItems = [],
  dependencies = [],
  onAddDependency,
  onDeleteDependency,
}) {
  const formId = useId();
  const [showAddForm, setShowAddForm] = useState(false);
  const [predecessorId, setPredecessorId] = useState("");
  const [successorId, setSuccessorId] = useState("");
  const [linkType, setLinkType] = useState("FINISH_TO_START");
  const [lagMinutes, setLagMinutes] = useState(0);
  const [validationError, setValidationError] = useState("");

  const itemsMap = new Map();
  for (const item of workItems) {
    itemsMap.set(item.id, item);
  }

  function handleOpenForm() {
    setShowAddForm(true);
    setValidationError("");
    if (workItems.length >= 2) {
      setPredecessorId(workItems[0].id);
      setSuccessorId(workItems[1].id);
    }
  }

  function handleCancelForm() {
    setShowAddForm(false);
    setValidationError("");
  }

  function handleSubmitForm(e) {
    e.preventDefault();
    setValidationError("");

    if (!predecessorId || !successorId) {
      setValidationError("Please select both a predecessor and a successor.");
      return;
    }

    if (predecessorId === successorId) {
      setValidationError("Cannot create self-dependency: predecessor and successor must be different items.");
      return;
    }

    // Check duplicate
    const exists = dependencies.some(
      (d) => d.predecessorId === predecessorId && d.successorId === successorId
    );
    if (exists) {
      setValidationError("This dependency link already exists.");
      return;
    }

    // Check cycle
    const wouldCycle = detectCycle(predecessorId, successorId, dependencies);
    if (wouldCycle) {
      setValidationError("Cycle detected: adding this dependency would create a circular loop in the project DAG.");
      return;
    }

    onAddDependency({
      predecessorId,
      successorId,
      linkType,
      lagMinutes: Number(lagMinutes) || 0,
    });

    setShowAddForm(false);
  }

  return (
    <div className={styles.dagContainer} aria-label="Dependency Directed Acyclic Graph Visualizer">
      <div className={styles.headerCard}>
        <div className={styles.titleArea}>
          <h2>
            <Network size={20} className={styles.headerIcon} aria-hidden="true" />
            <span>Dependency DAG & Precedence Matrix</span>
          </h2>
          <p className={styles.subtitle}>
            Manage temporal linkages and critical path predecessor constraints across work items.
          </p>
        </div>

        {!showAddForm && (
          <button
            type="button"
            className={styles.addDepBtn}
            onClick={handleOpenForm}
            aria-label="Add Dependency"
          >
            <Plus size={16} aria-hidden="true" />
            <span>Add Dependency</span>
          </button>
        )}
      </div>

      {showAddForm && (
        <form
          className={styles.addDepFormCard}
          onSubmit={handleSubmitForm}
          onKeyDown={(e) => {
            if (e.key === "Escape") {
              e.preventDefault();
              handleCancelForm();
            }
          }}
          aria-label="Create dependency link"
        >
          <div className={styles.formGrid}>
            <div className={styles.formField}>
              <label htmlFor={`${formId}-pred`} className={styles.formLabel}>
                Predecessor (Blocks)
              </label>
              <select
                id={`${formId}-pred`}
                className={styles.formSelect}
                value={predecessorId}
                onChange={(e) => setPredecessorId(e.target.value)}
                required
              >
                <option value="" disabled>Select predecessor...</option>
                {workItems.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.itemKey} - {item.title}
                  </option>
                ))}
              </select>
            </div>

            <div className={styles.formField}>
              <label htmlFor={`${formId}-succ`} className={styles.formLabel}>
                Successor (Blocked By)
              </label>
              <select
                id={`${formId}-succ`}
                className={styles.formSelect}
                value={successorId}
                onChange={(e) => setSuccessorId(e.target.value)}
                required
              >
                <option value="" disabled>Select successor...</option>
                {workItems.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.itemKey} - {item.title}
                  </option>
                ))}
              </select>
            </div>

            <div className={styles.formField}>
              <label htmlFor={`${formId}-type`} className={styles.formLabel}>
                Link Type
              </label>
              <select
                id={`${formId}-type`}
                className={styles.formSelect}
                value={linkType}
                onChange={(e) => setLinkType(e.target.value)}
              >
                <option value="FINISH_TO_START">Finish to Start (FS)</option>
                <option value="START_TO_START">Start to Start (SS)</option>
                <option value="FINISH_TO_FINISH">Finish to Finish (FF)</option>
              </select>
            </div>

            <div className={styles.formField}>
              <label htmlFor={`${formId}-lag`} className={styles.formLabel}>
                Lag (Minutes)
              </label>
              <input
                id={`${formId}-lag`}
                type="number"
                min="0"
                step="15"
                className={styles.formInput}
                value={lagMinutes}
                onChange={(e) => setLagMinutes(Math.max(0, parseInt(e.target.value, 10) || 0))}
              />
            </div>
          </div>

          {validationError && (
            <div className={styles.formError} role="alert">
              <AlertTriangle size={15} style={{ display: "inline-block", marginRight: "6px", verticalAlign: "middle" }} />
              <span>{validationError}</span>
            </div>
          )}

          <div className={styles.formActions}>
            <button
              type="button"
              className={styles.cancelFormBtn}
              onClick={handleCancelForm}
            >
              Cancel
            </button>
            <button type="submit" className={styles.submitFormBtn}>
              Create Link
            </button>
          </div>
        </form>
      )}

      {dependencies.length === 0 ? (
        <div className={styles.emptyDeps}>
          <EmptyState
            icon={Network}
            title="No dependencies defined"
            description="Link predecessor and successor work items to enforce scheduling constraints and calculate critical paths."
            actionLabel="+ Add First Dependency"
            onAction={handleOpenForm}
          />
        </div>
      ) : (
        <div className={styles.depGrid} role="list" aria-label="Dependencies list">
          {dependencies.map((dep) => {
            const pred = itemsMap.get(dep.predecessorId) || {
              itemKey: dep.predecessorKey || "PRED",
              title: dep.predecessorTitle || "Predecessor Item",
            };
            const succ = itemsMap.get(dep.successorId) || {
              itemKey: dep.successorKey || "SUCC",
              title: dep.successorTitle || "Successor Item",
            };

            return (
              <div
                key={dep.id}
                className={`${styles.depCard} ${dep.isCritical ? styles.depCardCritical : ""}`}
                role="listitem"
              >
                <div className={styles.flowRow}>
                  {/* Predecessor Node */}
                  <div className={styles.nodeBox}>
                    <div className={styles.nodeHeader}>
                      <span className={styles.nodeKey}>{pred.itemKey}</span>
                      <span className={styles.nodeRole}>Predecessor</span>
                    </div>
                    <span className={styles.nodeTitle} title={pred.title}>
                      {pred.title}
                    </span>
                  </div>

                  {/* Connector Arrow & Type */}
                  <div className={styles.connector} aria-label={`Linked via ${dep.linkType} with ${dep.lagMinutes || 0} minutes lag`}>
                    <span className={styles.linkTypeBadge}>{dep.linkType || "FINISH_TO_START"}</span>
                    <ArrowRight size={18} aria-hidden="true" />
                    {dep.lagMinutes > 0 && (
                      <span className={styles.lagBadge}>+{dep.lagMinutes}m lag</span>
                    )}
                  </div>

                  {/* Successor Node */}
                  <div className={styles.nodeBox}>
                    <div className={styles.nodeHeader}>
                      <span className={styles.nodeKey}>{succ.itemKey}</span>
                      <span className={styles.nodeRole}>Successor</span>
                    </div>
                    <span className={styles.nodeTitle} title={succ.title}>
                      {succ.title}
                    </span>
                  </div>
                </div>

                <div className={styles.depMeta}>
                  {dep.isCritical && (
                    <span className={styles.criticalBadge} title="Critical Path Dependency">
                      <Flame size={13} aria-hidden="true" />
                      <span>Critical</span>
                    </span>
                  )}

                  <button
                    type="button"
                    className={styles.deleteDepBtn}
                    onClick={() => onDeleteDependency(dep.id)}
                    aria-label={`Remove dependency between ${pred.itemKey} and ${succ.itemKey}`}
                    title="Remove dependency"
                  >
                    <Trash2 size={15} />
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
