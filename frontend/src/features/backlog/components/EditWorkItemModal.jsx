"use client";

import { useState, useRef, useId } from "react";
import { X, Edit3, Trash2, AlertTriangle } from "lucide-react";
import useFocusTrap from "../../../hooks/useFocusTrap";
import styles from "../styles/WorkItemModals.module.css";

export default function EditWorkItemModal({
  item,
  isOpen,
  onClose,
  onUpdate,
  onDelete,
  onTransitionStatus,
  onBlockToggle,
  existingItems = [],
  requirements = [],
  assignees = [],
}) {
  const formId = useId();
  const modalRef = useRef(null);
  const titleInputRef = useRef(null);

  const [title, setTitle] = useState(item?.title || "");
  const [description, setDescription] = useState(item?.description || "");
  const [priority, setPriority] = useState(item?.priority || "MEDIUM");
  const [status, setStatus] = useState(item?.status || "TO_DO");
  const [parentId, setParentId] = useState(item?.parentId || "");
  const [requirementId, setRequirementId] = useState(item?.requirementId || "");
  const [assigneeId, setAssigneeId] = useState(item?.assigneeId || "");
  const [storyPoints, setStoryPoints] = useState(item?.storyPoints ?? "");
  const [estimateHours, setEstimateHours] = useState(
    item?.estimateMinutes ? (item.estimateMinutes / 60).toFixed(1).replace(/\.0$/, "") : ""
  );
  const [labels, setLabels] = useState(
    Array.isArray(item?.labels) ? item.labels.join(", ") : ""
  );

  const [isBlocked, setIsBlocked] = useState(Boolean(item?.isBlocked));
  const [blockedReason, setBlockedReason] = useState(item?.blockedReason || "");

  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  useFocusTrap({
    active: isOpen,
    containerRef: modalRef,
    initialFocusRef: titleInputRef,
    onEscape: onClose,
    restoreFocus: true,
  });

  if (!isOpen || !item) return null;

  async function handleSubmit(e) {
    e.preventDefault();
    setError("");

    if (!title.trim()) {
      setError("Title is required.");
      return;
    }

    if (isBlocked && !blockedReason.trim()) {
      setError("A reason is required when flagging an item as blocked.");
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

      // If status changed, transition status
      if (status !== item.status && onTransitionStatus) {
        await onTransitionStatus(item.id, status);
      }

      // If blocked status changed, update blocked flag
      if ((isBlocked !== Boolean(item.isBlocked) || blockedReason !== (item.blockedReason || "")) && onBlockToggle) {
        await onBlockToggle(item.id, isBlocked, blockedReason);
      }

      // Update general fields
      await onUpdate(item.id, {
        title: title.trim(),
        description: description.trim(),
        priority,
        parentId: parentId || null,
        requirementId: requirementId || null,
        assigneeId: assigneeId || null,
        storyPoints: storyPoints ? parseFloat(storyPoints) : null,
        estimateMinutes: parsedEstimate,
        labels: parsedLabels,
        expectedVersion: item.version ?? 0,
      });

      onClose();
    } catch (err) {
      setError(err.message || "Failed to update work item");
    } finally {
      setSubmitting(false);
    }
  }

  async function handleDelete() {
    if (confirm(`Are you sure you want to delete ${item.itemKey}?`)) {
      try {
        setSubmitting(true);
        await onDelete(item.id);
        onClose();
      } catch (err) {
        setError(err.message || "Failed to delete work item");
      } finally {
        setSubmitting(false);
      }
    }
  }

  const eligibleParents = existingItems.filter(
    (other) => other.id !== item.id && (other.type === "EPIC" || other.type === "USER_STORY" || other.type === "TASK")
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
              <Edit3 size={18} aria-hidden="true" />
              <span>Edit Work Item</span>
              <span className={styles.keyBadge}>{item.itemKey}</span>
            </h2>
            <p id={`${formId}-dialog-desc`} className={styles.modalSubtitle}>Update details, state transitions, or blocking reasons.</p>
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
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              disabled={submitting}
            />
          </div>

          {/* Status & Priority Row */}
          <div className={styles.formRow}>
            <div className={styles.formGroup}>
              <label htmlFor={`${formId}-status`} className={styles.label}>Status</label>
              <select
                id={`${formId}-status`}
                className={styles.select}
                value={status}
                onChange={(e) => setStatus(e.target.value)}
                disabled={submitting}
              >
                <option value="TO_DO">To Do</option>
                <option value="IN_PROGRESS">In Progress</option>
                <option value="IN_REVIEW">In Review</option>
                <option value="DONE">Done</option>
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

          {/* Assignee & Story Points Row */}
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
              <label htmlFor={`${formId}-points`} className={styles.label}>Story Points</label>
              <input
                id={`${formId}-points`}
                type="number"
                min="0"
                step="0.5"
                className={styles.input}
                value={storyPoints}
                onChange={(e) => setStoryPoints(e.target.value)}
                disabled={submitting}
              />
            </div>
          </div>

          {/* Estimate & Labels */}
          <div className={styles.formRow}>
            <div className={styles.formGroup}>
              <label htmlFor={`${formId}-hours`} className={styles.label}>Estimate (Hours)</label>
              <input
                id={`${formId}-hours`}
                type="number"
                min="0"
                step="0.5"
                className={styles.input}
                value={estimateHours}
                onChange={(e) => setEstimateHours(e.target.value)}
                disabled={submitting}
              />
            </div>

            <div className={styles.formGroup}>
              <label htmlFor={`${formId}-labels`} className={styles.label}>Labels</label>
              <input
                id={`${formId}-labels`}
                type="text"
                className={styles.input}
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
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              disabled={submitting}
            />
          </div>

          {/* Blocked Flag & Reason Box */}
          <div className={styles.blockedBox}>
            <label className={styles.checkboxLabel}>
              <input
                type="checkbox"
                className={styles.checkbox}
                checked={isBlocked}
                onChange={(e) => setIsBlocked(e.target.checked)}
                disabled={submitting}
              />
              <span>Flag this item as blocked</span>
            </label>

            {isBlocked && (
              <div className={styles.formGroup}>
                <label htmlFor={`${formId}-block-reason`} className={styles.label}>
                  <span>Blocked Reason <span className={styles.required}>*</span></span>
                </label>
                <textarea
                  id={`${formId}-block-reason`}
                  rows={2}
                  required={isBlocked}
                  className={styles.textarea}
                  placeholder="Explain why this item cannot progress..."
                  value={blockedReason}
                  onChange={(e) => setBlockedReason(e.target.value)}
                  disabled={submitting}
                />
              </div>
            )}
          </div>

          {/* Actions */}
          <div className={styles.modalActions}>
            <button
              type="button"
              className={styles.deleteBtn}
              onClick={handleDelete}
              disabled={submitting}
            >
              <Trash2 size={14} aria-hidden="true" />
              <span>Delete</span>
            </button>

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
                {submitting ? "Saving…" : "Save Changes"}
              </button>
            </div>
          </div>
        </form>
      </div>
    </div>
  );
}
