"use client";

import { useState, useRef, useEffect } from "react";
import { CheckCircle2, AlertCircle, Clock, X, ShieldAlert, GitPullRequest } from "lucide-react";
import { evaluateQualityGate } from "../utils/sprint-utils";
import styles from "../styles/QualityGateBadge.module.css";

export default function QualityGateBadge({ gate, itemKey = "" }) {
  const [isOpen, setIsOpen] = useState(false);
  const wrapperRef = useRef(null);

  const evaluation = evaluateQualityGate(gate);
  const { status, label } = evaluation;

  // Close flyout on Escape or outside click
  useEffect(() => {
    if (!isOpen) return;

    function handleKeyDown(e) {
      if (e.key === "Escape") {
        setIsOpen(false);
      }
    }

    function handleClickOutside(e) {
      if (wrapperRef.current && !wrapperRef.current.contains(e.target)) {
        setIsOpen(false);
      }
    }

    document.addEventListener("keydown", handleKeyDown);
    document.addEventListener("mousedown", handleClickOutside);
    return () => {
      document.removeEventListener("keydown", handleKeyDown);
      document.removeEventListener("mousedown", handleClickOutside);
    };
  }, [isOpen]);

  const getBadgeClass = () => {
    if (status === "PASSED") return styles.badgePassed;
    if (status === "FAILED") return styles.badgeFailed;
    return styles.badgePending;
  };

  const getStatusIcon = () => {
    if (status === "PASSED") return <CheckCircle2 size={12} aria-hidden="true" />;
    if (status === "FAILED") return <AlertCircle size={12} aria-hidden="true" />;
    return <Clock size={12} aria-hidden="true" />;
  };

  const ciStatus = gate?.ciPassed ? "PASSED" : "FAILED";
  const reviewStatus = gate?.reviewPassed ? "APPROVED" : "PENDING";
  const criteriaStatus = gate?.criteriaPassed ? "SATISFIED" : "PENDING";
  const blockers = Array.isArray(gate?.blockers) ? gate.blockers : [];

  return (
    <div className={styles.badgeWrapper} ref={wrapperRef}>
      <button
        type="button"
        className={`${styles.triggerButton} ${getBadgeClass()}`}
        onClick={(e) => {
          e.stopPropagation();
          setIsOpen((prev) => !prev);
        }}
        aria-expanded={isOpen}
        aria-haspopup="dialog"
        aria-label={`Quality Gate status: ${label} for ${itemKey}. Click to view details.`}
      >
        {getStatusIcon()}
        <span>QG: {label}</span>
      </button>

      {isOpen && (
        <div
          className={styles.flyout}
          role="dialog"
          aria-label={`Quality Gate details for ${itemKey}`}
          onClick={(e) => e.stopPropagation()}
        >
          <div className={styles.flyoutHeader}>
            <h4 className={styles.flyoutTitle}>
              <ShieldAlert size={15} aria-hidden="true" />
              <span>Quality Gate Details</span>
            </h4>
            <button
              type="button"
              className={styles.closeButton}
              onClick={() => setIsOpen(false)}
              aria-label="Close quality gate details"
            >
              <X size={14} aria-hidden="true" />
            </button>
          </div>

          <ul className={styles.criteriaList}>
            <li className={styles.criteriaItem}>
              <span className={styles.criteriaName}>
                <GitPullRequest size={13} aria-hidden="true" />
                <span>CI Build</span>
              </span>
              <span
                className={`${styles.statusIndicator} ${
                  gate?.ciPassed ? styles.statusSuccess : styles.statusError
                }`}
              >
                {ciStatus}
              </span>
            </li>

            <li className={styles.criteriaItem}>
              <span className={styles.criteriaName}>
                <CheckCircle2 size={13} aria-hidden="true" />
                <span>Peer Review</span>
              </span>
              <span
                className={`${styles.statusIndicator} ${
                  gate?.reviewPassed ? styles.statusSuccess : styles.statusWarning
                }`}
              >
                {reviewStatus}
              </span>
            </li>

            <li className={styles.criteriaItem}>
              <span className={styles.criteriaName}>
                <Clock size={13} aria-hidden="true" />
                <span>Acceptance Criteria</span>
              </span>
              <span
                className={`${styles.statusIndicator} ${
                  gate?.criteriaPassed ? styles.statusSuccess : styles.statusWarning
                }`}
              >
                {criteriaStatus}
              </span>
            </li>
          </ul>

          {blockers.length > 0 && (
            <div className={styles.blockersBox}>
              <p className={styles.blockersTitle}>Active Blockers ({blockers.length})</p>
              <ul className={styles.blockersList}>
                {blockers.map((b, idx) => (
                  <li key={idx}>{b}</li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
