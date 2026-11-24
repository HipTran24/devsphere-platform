"use client";

import { useState, useRef, useId } from "react";
import { X, PlusCircle } from "lucide-react";
import useFocusTrap from "../../../hooks/useFocusTrap";
import styles from "../styles/WorkItemModals.module.css";

export default function CreateWorkItemModal({
  isOpen,
  onClose,
  onCreate,
  existingItems = [],
  requirements = [],
  assignees = [],
}) {
  const formId = useId();
  const modalRef = useRef(null);
  const titleInputRef = useRef(null);

  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [type, setType] = useState("USER_STORY");
  const [priority, setPriority] = useState("MEDIUM");
  const [parentId, setParentId] = useState("");
  const [requirementId, setRequirementId] = useState("");
  const [assigneeId, setAssigneeId] = useState("");
  const [storyPoints, setStoryPoints] = useState("");
  const [estimateHours, setEstimateHours] = useState("");
  const [labels, setLabels] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  useFocusTrap({
    active: isOpen,
    containerRef: modalRef,
    initialFocusRef: titleInputRef,
    onEscape: onClose,
    restoreFocus: true,
  });

  if (!isOpen) return null;

  async function handleSubmit(e) {
    e.preventDefault();
    setError("");

    if (!title.trim()) {
      setError("Title is required.");
      return;
    }

    try {
      setSubmitting(true);
      const parsedEstimate = estimateHours
        ? Math.round(parseFloat(estimateHours) * 60)
        : null;

      const parsedLabels = labels
        ? labels
            .split(",")
            .map((l) => l.trim())
            .filter(Boolean)
        : [];

      await onCreate({
        title: title.trim(),
        description: description.trim(),
        type,
        priority,
        parentId: parentId || null,
        requirementId: requirementId || null,
        assigneeId: assigneeId || null,
        storyPoints: storyPoints ? parseFloat(storyPoints) : null,
        estimateMinutes: parsedEstimate,
        labels: parsedLabels,
      });

      onClose();
    } catch (err) {
      setError(err.message || "Failed to create work item");
    } finally {
      setSubmitting(false);
    }
  }

  // Filter possible parents: Subtasks can't be parents; exclude current if editing
  const eligibleParents = existingItems.filter(
    (item) => item.type === "EPIC" || item.type === "USER_STORY" || item.type === "TASK"
  );

  return (
    <div
      className={styles.modalOverlay}
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
      role="presentation"
    >
      <div
        className={styles.modalCard}
        ref={modalRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={`${formId}-title`}
        aria-describedby={`${formId}-dialog-desc`}
      >
        <div className={styles.modalHeader}>
          <div className={styles.modalTitleWrap}>
            <h2 id={`${formId}-title`} className={styles.modalTitle}>
              <PlusCircle size={20} aria-hidden="true" />
              <span>Create Work Item</span>
            </h2>
            <p id={`${formId}-dialog-desc`} className={styles.modalSubtitle}>Add a new item to the project backlog hierarchy.</p>
          </div>
          <button
            type="button"
            className={styles.closeBtn}
            onClick={onClose}
            aria-label="Close dialog"
          >
            <X size={18} />
          </button>
        </div>

        {error && (
          <div className={styles.errorMessage} role="alert">
            {error}
          </div>
        )}

        <form className={styles.form} onSubmit={handleSubmit}>
          {/* Title */}
          <div className={styles.formGroup}>
            <label htmlFor={`${formId}-title-input`} className={styles.label}>
              <span>Title <span className={styles.required}>*</span></span>
            </label>
            <input
              id={`${formId}-title-input`}
              ref={titleInputRef}
              type="text"
              required
              className={styles.input}
              placeholder="e.g. Implement refresh token rotation"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              disabled={submitting}
            />
          </div>

          {/* Type & Priority Row */}
          <div className={styles.formRow}>
            <div className={styles.formGroup}>
              <label htmlFor={`${formId}-type`} className={styles.label}>Type</label>
              <select
                id={`${formId}-type`}
                className={styles.select}
                value={type}
                onChange={(e) => setType(e.target.value)}
                disabled={submitting}
              >
                <option value="EPIC">Epic</option>
                <option value="USER_STORY">User Story</option>
                <option value="TASK">Task</option>
                <option value="SUBTASK">Subtask</option>
                <option value="BUG">Bug</option>
              </select>
            </div>

            <div className={styles.formGroup}>
              <label htmlFor={`${formId}-priority`} className={styles.label}>Priority</label>
              <select
                id={`${formId}-priority`}
                className={styles.select}
                value={priority}
                onChange={(e) => setPriority(e.target.value)}
                disabled={submitting}
              >
                <option value="LOW">Low</option>
                <option value="MEDIUM">Medium</option>
                <option value="HIGH">High</option>
                <option value="CRITICAL">Critical</option>
              </select>
            </div>
          </div>

          {/* Parent Item & Requirement Row */}
          <div className={styles.formRow}>
            <div className={styles.formGroup}>
              <label htmlFor={`${formId}-parent`} className={styles.label}>
                <span>Parent Item</span>
                <span className={styles.hint}>(Optional hierarchy)</span>
              </label>
              <select
                id={`${formId}-parent`}
                className={styles.select}
                value={parentId}
                onChange={(e) => setParentId(e.target.value)}
                disabled={submitting}
              >
                <option value="">No parent (Top level)</option>
                {eligibleParents.map((p) => (
                  <option key={p.id} value={p.id}>
                    [{p.itemKey}] {p.title} ({p.type})
                  </option>
                ))}
              </select>
            </div>

            <div className={styles.formGroup}>
              <label htmlFor={`${formId}-req`} className={styles.label}>
                <span>Linked Requirement</span>
              </label>
              <select
                id={`${formId}-req`}
                className={styles.select}
                value={requirementId}
                onChange={(e) => setRequirementId(e.target.value)}
                disabled={submitting}
              >
                <option value="">None</option>
                {requirements.map((req) => (
                  <option key={req.id} value={req.id}>
                    [{req.requirementKey || "REQ"}] {req.title}
                  </option>
                ))}
              </select>
            </div>
          </div>

          {/* Assignee & Estimates Row */}
          <div className={styles.formRow}>
            <div className={styles.formGroup}>
              <label htmlFor={`${formId}-assignee`} className={styles.label}>Assignee</label>
              <select
                id={`${formId}-assignee`}
                className={styles.select}
                value={assigneeId}
                onChange={(e) => setAssigneeId(e.target.value)}
                disabled={submitting}
              >
                <option value="">Unassigned</option>
                {assignees.map((user) => (
                  <option key={user.id} value={user.id}>
                    {user.displayName || user.email}
                  </option>
                ))}
              </select>
            </div>

            <div className={styles.formGroup}>
              <label htmlFor={`${formId}-points`} className={styles.label}>
                <span>Story Points</span>
              </label>
              <input
                id={`${formId}-points`}
                type="number"
                min="0"
                step="0.5"
                className={styles.input}
                placeholder="e.g. 5"
                value={storyPoints}
                onChange={(e) => setStoryPoints(e.target.value)}
                disabled={submitting}
              />
            </div>
          </div>

          {/* Estimate Hours & Labels */}
          <div className={styles.formRow}>
            <div className={styles.formGroup}>
              <label htmlFor={`${formId}-hours`} className={styles.label}>
                <span>Estimate (Hours)</span>
              </label>
              <input
                id={`${formId}-hours`}
                type="number"
                min="0"
                step="0.5"
                className={styles.input}
                placeholder="e.g. 8"
                value={estimateHours}
                onChange={(e) => setEstimateHours(e.target.value)}
                disabled={submitting}
              />
            </div>

            <div className={styles.formGroup}>
              <label htmlFor={`${formId}-labels`} className={styles.label}>
                <span>Labels</span>
                <span className={styles.hint}>Comma-separated</span>
              </label>
              <input
                id={`${formId}-labels`}
                type="text"
                className={styles.input}
                placeholder="security, auth, frontend"
                value={labels}
                onChange={(e) => setLabels(e.target.value)}
                disabled={submitting}
              />
            </div>
          </div>

          {/* Description */}
          <div className={styles.formGroup}>
            <label htmlFor={`${formId}-desc`} className={styles.label}>Description</label>
            <textarea
              id={`${formId}-desc`}
              rows={3}
              className={styles.textarea}
              placeholder="Provide context, scope, or acceptance criteria..."
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              disabled={submitting}
            />
          </div>

          {/* Actions */}
          <div className={styles.modalActions}>
            <div className={styles.primaryActions}>
              <button
                type="button"
                className={styles.cancelBtn}
                onClick={onClose}
                disabled={submitting}
              >
                Cancel
              </button>
              <button
                type="submit"
                className={styles.submitBtn}
                disabled={submitting}
              >
                {submitting ? "Creating…" : "Create Item"}
              </button>
            </div>
          </div>
        </form>
      </div>
    </div>
  );
}
