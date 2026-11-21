'use client';

'use client';

import React, { useEffect, useRef, useState, useCallback } from 'react';
import styles from '../styles/WorkspaceSprintMotion.module.css';

const TIMELINE_DURATION_MS = 12000;

const LOWER_STARFIELD_STARS = [
  { id: 's1', left: '4%', top: '44%', size: 1.5, delay: '0.1s', duration: '2.8s', tone: 'white' },
  { id: 's2', left: '7.5%', top: '62%', size: 2.2, delay: '1.1s', duration: '3.4s', tone: 'teal', glint: true },
  { id: 's3', left: '11%', top: '79%', size: 1.4, delay: '0.6s', duration: '2.5s', tone: 'white' },
  { id: 's4', left: '14.5%', top: '52%', size: 1.8, delay: '1.9s', duration: '3.1s', tone: 'mint' },
  { id: 's5', left: '17%', top: '88%', size: 2.0, delay: '0.3s', duration: '2.9s', tone: 'white' },
  { id: 's6', left: '20.5%', top: '68%', size: 1.3, delay: '2.2s', duration: '3.6s', tone: 'teal' },
  { id: 's7', left: '23%', top: '41%', size: 1.6, delay: '1.4s', duration: '2.7s', tone: 'white' },
  { id: 's8', left: '26.5%', top: '92%', size: 2.3, delay: '0.8s', duration: '3.3s', tone: 'mint', glint: true },
  { id: 's9', left: '29%', top: '57%', size: 1.4, delay: '2.5s', duration: '2.6s', tone: 'white' },
  { id: 's10', left: '32.5%', top: '75%', size: 1.9, delay: '0.4s', duration: '3.2s', tone: 'teal' },
  { id: 's11', left: '35%', top: '48%', size: 1.3, delay: '1.7s', duration: '2.8s', tone: 'white' },
  { id: 's12', left: '38.5%', top: '84%', size: 2.1, delay: '0.9s', duration: '3.5s', tone: 'white', glint: true },
  { id: 's13', left: '41.5%', top: '63%', size: 1.5, delay: '2.1s', duration: '2.4s', tone: 'mint' },
  { id: 's14', left: '44.5%', top: '91%', size: 1.7, delay: '0.2s', duration: '3.0s', tone: 'teal' },
  { id: 's15', left: '47%', top: '54%', size: 1.4, delay: '1.5s', duration: '2.9s', tone: 'white' },
  { id: 's16', left: '50.5%', top: '72%', size: 2.2, delay: '0.7s', duration: '3.4s', tone: 'mint', glint: true },
  { id: 's17', left: '53.5%', top: '43%', size: 1.3, delay: '2.3s', duration: '2.7s', tone: 'white' },
  { id: 's18', left: '56.5%', top: '86%', size: 1.8, delay: '1.2s', duration: '3.1s', tone: 'teal' },
  { id: 's19', left: '59.5%', top: '61%', size: 1.5, delay: '0.5s', duration: '2.6s', tone: 'white' },
  { id: 's20', left: '63%', top: '78%', size: 2.0, delay: '1.8s', duration: '3.3s', tone: 'mint' },
  { id: 's21', left: '66.5%', top: '49%', size: 1.4, delay: '2.6s', duration: '2.8s', tone: 'white' },
  { id: 's22', left: '69%', top: '93%', size: 2.3, delay: '0.3s', duration: '3.6s', tone: 'teal', glint: true },
  { id: 's23', left: '72.5%', top: '66%', size: 1.5, delay: '1.6s', duration: '2.5s', tone: 'white' },
  { id: 's24', left: '75.5%', top: '83%', size: 1.9, delay: '0.9s', duration: '3.2s', tone: 'mint' },
  { id: 's25', left: '78.5%', top: '55%', size: 1.4, delay: '2.0s', duration: '2.9s', tone: 'white' },
  { id: 's26', left: '82%', top: '74%', size: 2.1, delay: '0.6s', duration: '3.4s', tone: 'teal', glint: true },
  { id: 's27', left: '85.5%', top: '89%', size: 1.6, delay: '1.3s', duration: '2.7s', tone: 'white' },
  { id: 's28', left: '88.5%', top: '59%', size: 1.8, delay: '2.4s', duration: '3.1s', tone: 'mint' },
  { id: 's29', left: '92%', top: '77%', size: 1.5, delay: '0.8s', duration: '2.8s', tone: 'white' },
  { id: 's30', left: '95.5%', top: '64%', size: 2.0, delay: '1.7s', duration: '3.3s', tone: 'teal' },
  { id: 's31', left: '13%', top: '94%', size: 1.6, delay: '2.1s', duration: '2.9s', tone: 'mint' },
  { id: 's32', left: '36.5%', top: '69%', size: 1.4, delay: '0.4s', duration: '2.6s', tone: 'white' },
  { id: 's33', left: '61.5%', top: '89%', size: 1.7, delay: '1.1s', duration: '3.0s', tone: 'white' },
  { id: 's34', left: '79.5%', top: '94%', size: 1.9, delay: '2.7s', duration: '3.2s', tone: 'mint' },
  { id: 's35', left: '90.5%', top: '88%', size: 2.1, delay: '0.2s', duration: '3.5s', tone: 'white', glint: true },
];

const LOWER_SHOOTING_STARS = [
  { id: 'm1', left: '14%', top: '48%', delay: '0.3s', duration: '4.6s', length: 68 },
  { id: 'm2', left: '42%', top: '54%', delay: '2.1s', duration: '5.2s', length: 78 },
  { id: 'm3', left: '68%', top: '46%', delay: '1.2s', duration: '4.8s', length: 64 },
  { id: 'm4', left: '28%', top: '66%', delay: '3.4s', duration: '5.4s', length: 72 },
  { id: 'm5', left: '56%', top: '62%', delay: '4.2s', duration: '4.9s', length: 74 },
  { id: 'm6', left: '78%', top: '58%', delay: '2.8s', duration: '5.6s', length: 66 },
];

const starToneStyles = {
  teal: styles.twinkleStarTeal,
  mint: styles.twinkleStarMint,
  white: styles.twinkleStarWhite,
};

function clamp01(val) {
  return Math.max(0, Math.min(1, val));
}

function easeOutCubic(t) {
  const p = clamp01(t);
  return 1 - Math.pow(1 - p, 3);
}

function easeInOutCubic(t) {
  const p = clamp01(t);
  return p < 0.5 ? 4 * p * p * p : 1 - Math.pow(-2 * p + 2, 3) / 2;
}

function lerp(a, b, t) {
  return a + (b - a) * t;
}

function cubicBezierPoint(p0, p1, p2, p3, t) {
  const u = 1 - t;
  const tt = t * t;
  const uu = u * u;
  const uuu = uu * u;
  const ttt = tt * t;

  return {
    x: uuu * p0.x + 3 * uu * t * p1.x + 3 * u * tt * p2.x + ttt * p3.x,
    y: uuu * p0.y + 3 * uu * t * p1.y + 3 * u * tt * p2.y + ttt * p3.y,
  };
}

/**
 * 0.0–1.4s: Contained multi-depth 3D entrance so outer cards never clip out of the viewport
 */
function getCardDepthStyle(seconds, reducedMotion, config) {
  if (reducedMotion || seconds >= 1.5) {
    return {
      opacity: 1,
      transform: 'translate3d(0px, 0px, 0px) rotateX(0deg) rotateY(0deg)',
    };
  }

  const localT = (seconds - config.delay) / (config.duration * 0.62);
  if (localT <= 0) {
    return {
      opacity: 0,
      transform: `translate3d(${config.startX}px, ${config.startY}px, ${config.startZ}px) rotateX(${config.rotX}deg) rotateY(${config.rotY}deg)`,
    };
  }

  const e = easeOutCubic(localT);
  const opacity = clamp01(localT * 2.6);
  const x = lerp(config.startX, 0, e);
  const y = lerp(config.startY, 0, e);
  const z = lerp(config.startZ, 0, e);
  const rx = lerp(config.rotX, 0, e);
  const ry = lerp(config.rotY, 0, e);

  return {
    opacity,
    transform: `translate3d(${x.toFixed(1)}px, ${y.toFixed(1)}px, ${z.toFixed(1)}px) rotateX(${rx.toFixed(2)}deg) rotateY(${ry.toFixed(2)}deg)`,
  };
}

/**
 * Contained camera trajectory across the 12-second loop so no card ever goes out of bounds
 */
function getCameraStyle(seconds, reducedMotion) {
  if (reducedMotion) {
    return {
      opacity: 1,
      transform: 'translate3d(0%, 0%, 0px) scale(1)',
    };
  }

  let tx = 0;
  let ty = 0;
  let scale = 1;
  let rotY = 0;
  let rotX = 0;

  if (seconds < 1.4) {
    const e = easeOutCubic(seconds / 1.4);
    tx = lerp(0.8, 0, e);
    ty = lerp(-0.6, 0, e);
    scale = lerp(1.015, 1.0, e);
    rotY = lerp(-3.5, 0, e);
    rotX = lerp(2.0, 0, e);
  } else if (seconds >= 4.8 && seconds < 10.2) {
    const pushIn = easeInOutCubic((seconds - 4.8) / 0.85);
    tx = lerp(0, -0.7, pushIn);
    ty = lerp(0, 0.4, pushIn);
    scale = lerp(1.0, 1.018, pushIn);
  } else if (seconds >= 10.2) {
    const pullBack = easeInOutCubic((seconds - 10.2) / 0.75);
    tx = lerp(-0.7, 0, pullBack);
    ty = lerp(0.4, 0, pullBack);
    scale = lerp(1.018, 1.0, pullBack);
  }

  let opacity = 1;
  if (seconds >= 11.72) {
    opacity = lerp(1, 0.2, (seconds - 11.72) / 0.28);
  } else if (seconds < 0.18) {
    opacity = lerp(0.2, 1, seconds / 0.18);
  }

  return {
    opacity,
    transform: `translate3d(${tx.toFixed(2)}%, ${ty.toFixed(2)}%, 0px) scale(${scale.toFixed(4)}) rotateX(${rotX.toFixed(2)}deg) rotateY(${rotY.toFixed(2)}deg)`,
  };
}

function computeTimelineState(seconds, reducedMotion) {
  if (reducedMotion) {
    return {
      phase: 5,
      col1Draw: 1,
      col2Draw: 1,
      depWireDraw: 1,
      sec07ToRel04Draw: 1,
      dev18ToRel04Draw: 1,
      completedCount: 6,
      git09Status: 'Done',
      git09Tone: 'teal',
      git09Segments: 3,
      dev21Status: 'Done',
      dev21Tone: 'teal',
      dev21Segments: 3,
      dev12Status: 'Done',
      dev12Tone: 'teal',
      dev12Initials: 'AN',
      dev12Reassigned: true,
      dev12Segments: 4,
      sec07Status: 'Done',
      sec07Tone: 'teal',
      dev18Status: 'Ready',
      dev18Tone: 'teal',
      dev18Segments: 4,
      rel04Status: 'Ready',
      rel04Tone: 'teal',
      redPulseVisible: false,
      redPulseT: 1,
      suggestionVisible: true,
      pmApprovedVisible: true,
      tealUnlockDraw: 1,
      tealPulseVisible: false,
      tealPulseT: 1,
    };
  }

  let phase = 1;
  if (seconds >= 8.1) phase = 5;
  else if (seconds >= 6.2) phase = 4;
  else if (seconds >= 3.65) phase = 3;
  else if (seconds >= 1.4) phase = 2;

  // Wave 1 (1.4s -> 1.85s): Sprint wires draw to Task 1 & Task 2
  const col1Draw = easeOutCubic((seconds - 1.4) / 0.45);

  // Task 1 (git09) starts ONLY after col1Draw arrives at 1.85s
  let git09Status = 'Wait';
  let git09Tone = 'neutral';
  let git09Segments = 0;
  if (seconds >= 3.2) {
    git09Status = 'Done';
    git09Tone = 'teal';
    git09Segments = 3;
  } else if (seconds >= 2.45) {
    git09Status = 'Review';
    git09Tone = 'review';
    git09Segments = 2;
  } else if (seconds >= 1.85) {
    git09Status = 'Active';
    git09Tone = 'blue';
    git09Segments = 1;
  }

  // Task 2 (dev21) starts ONLY after col1Draw arrives at 1.85s
  let dev21Status = 'Wait';
  let dev21Tone = 'neutral';
  let dev21Segments = 0;
  if (seconds >= 3.55) {
    dev21Status = 'Done';
    dev21Tone = 'teal';
    dev21Segments = 3;
  } else if (seconds >= 2.6) {
    dev21Status = 'Review';
    dev21Tone = 'review';
    dev21Segments = 2;
  } else if (seconds >= 1.85) {
    dev21Status = 'Active';
    dev21Tone = 'blue';
    dev21Segments = 1;
  }

  // Wave 2 (3.65s -> 4.15s): ONLY after BOTH Task 1 & Task 2 are Done (3.55s), wires draw to Task 3 & Task 4!
  const col2Draw = easeOutCubic((seconds - 3.65) / 0.5);

  // Task 4 (sec07) starts ONLY after col2Draw arrives at 4.15s
  let sec07Status = 'Wait';
  let sec07Tone = 'neutral';
  if (seconds >= 5.55) {
    sec07Status = 'Done';
    sec07Tone = 'teal';
  } else if (seconds >= 4.85) {
    sec07Status = 'Review';
    sec07Tone = 'review';
  } else if (seconds >= 4.15) {
    sec07Status = 'Active';
    sec07Tone = 'blue';
  }

  // Wire from Task 4 -> Task 6 draws ONLY after Task 4 is Done (5.65s -> 6.15s)
  const sec07ToRel04Draw = easeOutCubic((seconds - 5.65) / 0.5);

  // Task 3 (dev12) starts ONLY after col2Draw arrives at 4.15s, then gets Blocked at 4.95s
  const dev12Reassigned = seconds >= 6.85;
  const dev12Initials = dev12Reassigned ? 'AN' : 'MI';

  let dev12Status = 'Wait';
  let dev12Tone = 'neutral';
  let dev12Segments = 0;
  if (seconds >= 8.0) {
    dev12Status = 'Done';
    dev12Tone = 'teal';
    dev12Segments = 4;
  } else if (seconds >= 7.1) {
    dev12Status = 'Review';
    dev12Tone = 'review';
    dev12Segments = 3;
  } else if (seconds >= 4.95) {
    dev12Status = 'Blocked';
    dev12Tone = 'red';
    dev12Segments = 2;
  } else if (seconds >= 4.15) {
    dev12Status = 'Active';
    dev12Tone = 'blue';
    dev12Segments = 2;
  }

  // When Task 3 gets Blocked (4.95s), the red dashed wire runs from Task 3 to Task 5 (5.0s -> 5.55s) and disappears at 7.1s when approved!
  const depWireDraw = easeOutCubic((seconds - 5.0) / 0.55);
  const redPulseVisible = seconds >= 5.0 && seconds < 7.1;
  const redPulseT = easeOutCubic((seconds - 5.0) / 0.55);

  // Reassign Popover appears above Task 3 to resolve the bottleneck (6.15s)
  const suggestionVisible = seconds >= 6.15 && seconds < 11.6;
  const pmApprovedVisible = seconds >= 6.75 && seconds < 11.6;

  // Once Reassign is approved (7.1s), the red dashed wire disappears and the solid white wire runs into Task 5!
  const tealUnlockDraw = easeOutCubic((seconds - 7.1) / 0.45);
  const tealPulseVisible = seconds >= 7.1 && seconds <= 7.65;
  const tealPulseT = easeOutCubic((seconds - 7.1) / 0.45);

  // Task 5 (dev18) is Blocked (with red border) while the red dashed wire is active (5.5s -> 7.1s),
  // loses its red border and transitions to Active once the white wire arrives at 7.55s!
  let dev18Status = 'Wait';
  let dev18Tone = 'neutral';
  let dev18Segments = 0;
  if (seconds >= 9.7) {
    dev18Status = 'Ready';
    dev18Tone = 'teal';
    dev18Segments = 4;
  } else if (seconds >= 8.85) {
    dev18Status = 'Review';
    dev18Tone = 'review';
    dev18Segments = 3;
  } else if (seconds >= 7.55) {
    dev18Status = 'Active';
    dev18Tone = 'blue';
    dev18Segments = 2;
  } else if (seconds >= 5.5 && seconds < 7.1) {
    dev18Status = 'Blocked';
    dev18Tone = 'red';
    dev18Segments = 1;
  }

  // Wave 4 (9.8s -> 10.25s): ONLY after Task 5 is Ready/Done (9.7s), the wire from Task 5 -> Task 6 draws!
  const dev18ToRel04Draw = easeOutCubic((seconds - 9.8) / 0.45);

  // Task 6 (rel04) starts ONLY after BOTH Task 4 -> Task 6 AND Task 5 -> Task 6 wires have arrived (10.25s)!
  let rel04Status = 'Wait';
  let rel04Tone = 'neutral';
  if (seconds >= 11.05) {
    rel04Status = 'Ready';
    rel04Tone = 'teal';
  } else if (seconds >= 10.65) {
    rel04Status = 'Review';
    rel04Tone = 'review';
  } else if (seconds >= 10.25) {
    rel04Status = 'Active';
    rel04Tone = 'blue';
  }

  let completedCount = 0;
  if (seconds >= 3.2) completedCount = 1;
  if (seconds >= 3.55) completedCount = 2;
  if (seconds >= 5.55) completedCount = 3;
  if (seconds >= 8.0) completedCount = 4;
  if (seconds >= 9.7) completedCount = 5;
  if (seconds >= 11.05) completedCount = 6;

  return {
    phase,
    col1Draw,
    col2Draw,
    depWireDraw,
    sec07ToRel04Draw,
    dev18ToRel04Draw,
    completedCount,
    git09Status,
    git09Tone,
    git09Segments,
    dev21Status,
    dev21Tone,
    dev21Segments,
    dev12Status,
    dev12Tone,
    dev12Initials,
    dev12Reassigned,
    dev12Segments,
    sec07Status,
    sec07Tone,
    dev18Status,
    dev18Tone,
    dev18Segments,
    rel04Status,
    rel04Tone,
    redPulseVisible,
    redPulseT,
    suggestionVisible,
    pmApprovedVisible,
    tealUnlockDraw,
    tealPulseVisible,
    tealPulseT,
  };
}

export default function WorkspaceSprintMotion() {
  const panelRef = useRef(null);
  const rigRef = useRef(null);
  const sprintSlotRef = useRef(null);
  const git09SlotRef = useRef(null);
  const dev21SlotRef = useRef(null);
  const dev12SlotRef = useRef(null);
  const suggestionSlotRef = useRef(null);
  const sec07SlotRef = useRef(null);
  const dev18SlotRef = useRef(null);
  const rel04SlotRef = useRef(null);

  const [elapsedSec, setElapsedSec] = useState(0);
  const [inView, setInView] = useState(true);
  const [reducedMotion, setReducedMotion] = useState(false);

  const [anchors, setAnchors] = useState({
    width: 1240,
    height: 396,
    sprintOutTop: { x: 251, y: 194 },
    sprintOutBot: { x: 251, y: 218 },
    git09In: { x: 372, y: 132 },
    git09Out: { x: 536, y: 132 },
    dev21In: { x: 372, y: 280 },
    dev21Out: { x: 536, y: 280 },
    dev12In: { x: 663, y: 132 },
    dev12Out: { x: 831, y: 132 },
    dev12Top: { x: 747, y: 88 },
    suggestionBottom: { x: 747, y: 64 },
    sec07In: { x: 663, y: 280 },
    sec07Out: { x: 831, y: 280 },
    dev18In: { x: 955, y: 132 },
    dev18Bottom: { x: 1039, y: 176 },
    rel04In: { x: 955, y: 280 },
    rel04Top: { x: 1039, y: 236 },
  });

  const measureAnchors = useCallback(() => {
    const rig = rigRef.current;
    const sprintEl = sprintSlotRef.current;
    const git09El = git09SlotRef.current;
    const dev21El = dev21SlotRef.current;
    const dev12El = dev12SlotRef.current;
    const suggEl = suggestionSlotRef.current;
    const sec07El = sec07SlotRef.current;
    const dev18El = dev18SlotRef.current;
    const rel04El = rel04SlotRef.current;

    if (
      !rig ||
      !sprintEl ||
      !git09El ||
      !dev21El ||
      !dev12El ||
      !sec07El ||
      !dev18El ||
      !rel04El
    ) {
      return;
    }

    const w = rig.clientWidth || 1240;
    const h = rig.clientHeight || 396;

    setAnchors({
      width: w,
      height: h,
      sprintOutTop: {
        x: sprintEl.offsetLeft + sprintEl.offsetWidth,
        y: sprintEl.offsetTop + sprintEl.offsetHeight * 0.36,
      },
      sprintOutBot: {
        x: sprintEl.offsetLeft + sprintEl.offsetWidth,
        y: sprintEl.offsetTop + sprintEl.offsetHeight * 0.66,
      },
      git09In: {
        x: git09El.offsetLeft,
        y: git09El.offsetTop + git09El.offsetHeight * 0.5,
      },
      git09Out: {
        x: git09El.offsetLeft + git09El.offsetWidth,
        y: git09El.offsetTop + git09El.offsetHeight * 0.5,
      },
      dev21In: {
        x: dev21El.offsetLeft,
        y: dev21El.offsetTop + dev21El.offsetHeight * 0.5,
      },
      dev21Out: {
        x: dev21El.offsetLeft + dev21El.offsetWidth,
        y: dev21El.offsetTop + dev21El.offsetHeight * 0.5,
      },
      dev12In: {
        x: dev12El.offsetLeft,
        y: dev12El.offsetTop + dev12El.offsetHeight * 0.5,
      },
      dev12Out: {
        x: dev12El.offsetLeft + dev12El.offsetWidth,
        y: dev12El.offsetTop + dev12El.offsetHeight * 0.5,
      },
      dev12Top: {
        x: dev12El.offsetLeft + dev12El.offsetWidth * 0.5,
        y: dev12El.offsetTop,
      },
      suggestionBottom: {
        x: dev12El.offsetLeft + dev12El.offsetWidth * 0.5,
        y: suggEl ? suggEl.offsetTop + suggEl.offsetHeight : 64,
      },
      sec07In: {
        x: sec07El.offsetLeft,
        y: sec07El.offsetTop + sec07El.offsetHeight * 0.5,
      },
      sec07Out: {
        x: sec07El.offsetLeft + sec07El.offsetWidth,
        y: sec07El.offsetTop + sec07El.offsetHeight * 0.5,
      },
      dev18In: {
        x: dev18El.offsetLeft,
        y: dev18El.offsetTop + dev18El.offsetHeight * 0.5,
      },
      dev18Bottom: {
        x: dev18El.offsetLeft + dev18El.offsetWidth * 0.5,
        y: dev18El.offsetTop + dev18El.offsetHeight,
      },
      rel04In: {
        x: rel04El.offsetLeft,
        y: rel04El.offsetTop + rel04El.offsetHeight * 0.5,
      },
      rel04Top: {
        x: rel04El.offsetLeft + rel04El.offsetWidth * 0.5,
        y: rel04El.offsetTop,
      },
    });
  }, []);

  useEffect(() => {
    measureAnchors();
    const rig = rigRef.current;
    if (!rig || typeof ResizeObserver === 'undefined') {
      window.addEventListener('resize', measureAnchors);
      return () => window.removeEventListener('resize', measureAnchors);
    }

    const ro = new ResizeObserver(() => {
      measureAnchors();
    });
    ro.observe(rig);
    return () => ro.disconnect();
  }, [measureAnchors]);

  useEffect(() => {
    const el = panelRef.current;
    if (!el || typeof IntersectionObserver === 'undefined') return undefined;

    const io = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          setInView(entry.isIntersecting);
        });
      },
      { threshold: 0.15 }
    );
    io.observe(el);
    return () => io.disconnect();
  }, []);

  useEffect(() => {
    const mq = window.matchMedia('(prefers-reduced-motion: reduce)');
    setReducedMotion(mq.matches);
    const onChange = (e) => setReducedMotion(e.matches);
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  }, []);

  const elapsedRef = useRef(0);
  useEffect(() => {
    if (reducedMotion || !inView) return undefined;

    let rafId = null;
    let lastTs = null;

    const step = (ts) => {
      if (lastTs === null) lastTs = ts;
      const delta = ts - lastTs;
      lastTs = ts;

      elapsedRef.current = (elapsedRef.current + delta) % TIMELINE_DURATION_MS;
      setElapsedSec(elapsedRef.current / 1000);
      rafId = window.requestAnimationFrame(step);
    };

    rafId = window.requestAnimationFrame(step);
    return () => {
      if (rafId !== null) window.cancelAnimationFrame(rafId);
    };
  }, [reducedMotion, inView]);

  const state = computeTimelineState(elapsedSec, reducedMotion);
  const cameraStyle = getCameraStyle(elapsedSec, reducedMotion);

  // Contained entrance offsets so no card ever clips out of bounds
  const sprintDepthStyle = getCardDepthStyle(elapsedSec, reducedMotion, {
    delay: 0.0,
    duration: 1.6,
    startX: -28,
    startY: -12,
    startZ: -45,
    rotX: 6,
    rotY: 8,
  });

  const git09DepthStyle = getCardDepthStyle(elapsedSec, reducedMotion, {
    delay: 0.1,
    duration: 1.65,
    startX: -18,
    startY: -22,
    startZ: 35,
    rotX: -6,
    rotY: 6,
  });

  const dev21DepthStyle = getCardDepthStyle(elapsedSec, reducedMotion, {
    delay: 0.18,
    duration: 1.68,
    startX: -18,
    startY: 22,
    startZ: -35,
    rotX: 6,
    rotY: -6,
  });

  const dev12DepthStyle = getCardDepthStyle(elapsedSec, reducedMotion, {
    delay: 0.26,
    duration: 1.72,
    startX: 10,
    startY: -20,
    startZ: 45,
    rotX: -5,
    rotY: -7,
  });

  const sec07DepthStyle = getCardDepthStyle(elapsedSec, reducedMotion, {
    delay: 0.34,
    duration: 1.75,
    startX: 10,
    startY: 22,
    startZ: 35,
    rotX: 6,
    rotY: -7,
  });

  const dev18DepthStyle = getCardDepthStyle(elapsedSec, reducedMotion, {
    delay: 0.42,
    duration: 1.78,
    startX: 28,
    startY: -16,
    startZ: 45,
    rotX: 5,
    rotY: -8,
  });

  const rel04DepthStyle = getCardDepthStyle(elapsedSec, reducedMotion, {
    delay: 0.5,
    duration: 1.82,
    startX: 28,
    startY: 18,
    startZ: -40,
    rotX: -5,
    rotY: -8,
  });

  const {
    sprintOutTop,
    sprintOutBot,
    git09In,
    git09Out,
    dev21In,
    dev21Out,
    dev12In,
    dev12Out,
    dev12Top,
    suggestionBottom,
    sec07In,
    sec07Out,
    dev18In,
    dev18Bottom,
    rel04In,
    rel04Top,
  } = anchors;

  const makeCurve = (pA, pB) => {
    const c1 = { x: lerp(pA.x, pB.x, 0.5), y: pA.y };
    const c2 = { x: lerp(pA.x, pB.x, 0.5), y: pB.y };
    return `M ${pA.x} ${pA.y} C ${c1.x} ${c1.y}, ${c2.x} ${c2.y}, ${pB.x} ${pB.y}`;
  };

  const makeVerticalCurve = (pA, pB) => {
    const c1 = { x: pA.x, y: lerp(pA.y, pB.y, 0.5) };
    const c2 = { x: pB.x, y: lerp(pA.y, pB.y, 0.5) };
    return `M ${pA.x} ${pA.y} C ${c1.x} ${c1.y}, ${c2.x} ${c2.y}, ${pB.x} ${pB.y}`;
  };

  // Organic Fork from Sprint 04 -> GIT-09 & DEV-21
  const pathSprintToGit = makeCurve(sprintOutTop, git09In);
  const pathSprintToDev21 = makeCurve(sprintOutBot, dev21In);

  // Flowing Bridges from GIT-09 -> DEV-12 & DEV-21 -> SEC-07
  const pathGitToDev12 = makeCurve(git09Out, dev12In);
  const pathDev21ToSec07 = makeCurve(dev21Out, sec07In);

  // Flowing Bridges from SEC-07 -> REL-04 & Staggered Vertical S-Curve from DEV-18 -> REL-04
  const pathSec07ToRel04 = makeCurve(sec07Out, rel04In);
  const pathDev18ToRel04 = makeVerticalCurve(dev18Bottom, rel04Top);

  // Main Directed Dependency Bridge: Column 3 (DEV-12) -> Column 4 (DEV-18)
  const depStopEnd = { x: dev18In.x - 12, y: dev18In.y };
  const depStopC1 = { x: lerp(dev12Out.x, depStopEnd.x, 0.5), y: dev12Out.y };
  const depStopC2 = { x: lerp(dev12Out.x, depStopEnd.x, 0.5), y: depStopEnd.y };
  const pathDepStop = `M ${dev12Out.x} ${dev12Out.y} C ${depStopC1.x} ${depStopC1.y}, ${depStopC2.x} ${depStopC2.y}, ${depStopEnd.x} ${depStopEnd.y}`;

  const depFullC1 = { x: lerp(dev12Out.x, dev18In.x, 0.5), y: dev12Out.y };
  const depFullC2 = { x: lerp(dev12Out.x, dev18In.x, 0.5), y: dev18In.y };
  const pathDepFull = `M ${dev12Out.x} ${dev12Out.y} C ${depFullC1.x} ${depFullC1.y}, ${depFullC2.x} ${depFullC2.y}, ${dev18In.x} ${dev18In.y}`;

  const redOrbPos = cubicBezierPoint(dev12Out, depFullC1, depFullC2, dev18In, state.redPulseT);
  const tealOrbPos = cubicBezierPoint(dev12Out, depFullC1, depFullC2, dev18In, state.tealPulseT);
  const depMidPos = cubicBezierPoint(dev12Out, depFullC1, depFullC2, dev18In, 0.5);

  const col1TopC1 = { x: lerp(sprintOutTop.x, git09In.x, 0.5), y: sprintOutTop.y };
  const col1TopC2 = { x: lerp(sprintOutTop.x, git09In.x, 0.5), y: git09In.y };
  const col1OrbTopPos = cubicBezierPoint(sprintOutTop, col1TopC1, col1TopC2, git09In, state.col1Draw);

  const col1BotC1 = { x: lerp(sprintOutBot.x, dev21In.x, 0.5), y: sprintOutBot.y };
  const col1BotC2 = { x: lerp(sprintOutBot.x, dev21In.x, 0.5), y: dev21In.y };
  const col1OrbBotPos = cubicBezierPoint(sprintOutBot, col1BotC1, col1BotC2, dev21In, state.col1Draw);

  const col2TopC1 = { x: lerp(git09Out.x, dev12In.x, 0.5), y: git09Out.y };
  const col2TopC2 = { x: lerp(git09Out.x, dev12In.x, 0.5), y: dev12In.y };
  const col2OrbTopPos = cubicBezierPoint(git09Out, col2TopC1, col2TopC2, dev12In, state.col2Draw);

  const col2BotC1 = { x: lerp(dev21Out.x, sec07In.x, 0.5), y: dev21Out.y };
  const col2BotC2 = { x: lerp(dev21Out.x, sec07In.x, 0.5), y: sec07In.y };
  const col2OrbBotPos = cubicBezierPoint(dev21Out, col2BotC1, col2BotC2, sec07In, state.col2Draw);

  const secToRelC1 = { x: lerp(sec07Out.x, rel04In.x, 0.5), y: sec07Out.y };
  const secToRelC2 = { x: lerp(sec07Out.x, rel04In.x, 0.5), y: rel04In.y };
  const secToRelOrbPos = cubicBezierPoint(sec07Out, secToRelC1, secToRelC2, rel04In, state.sec07ToRel04Draw);

  const dev18ToRelC1 = { x: dev18Bottom.x, y: lerp(dev18Bottom.y, rel04Top.y, 0.5) };
  const dev18ToRelC2 = { x: rel04Top.x, y: lerp(dev18Bottom.y, rel04Top.y, 0.5) };
  const dev18ToRelOrbPos = cubicBezierPoint(dev18Bottom, dev18ToRelC1, dev18ToRelC2, rel04Top, state.dev18ToRel04Draw);

  const ringCircumference = 76;
  const ringOffset = ringCircumference * (1 - state.completedCount / 6);

  return (
    <section
      ref={panelRef}
      className={styles.panel}
      aria-label="DevSphere Sprint Flow, Bottleneck Detection, and Smart Auto-Reassignment"
    >
      <div className={styles.starfieldLayer} aria-hidden="true">
        {LOWER_STARFIELD_STARS.map((star) => (
          <span
            key={star.id}
            className={`${styles.twinkleStar} ${starToneStyles[star.tone]} ${star.glint ? styles.twinkleStarGlint : ''}`}
            style={{
              left: star.left,
              top: star.top,
              width: star.size,
              height: star.size,
              animationDelay: star.delay,
              animationDuration: star.duration,
            }}
          />
        ))}
        {LOWER_SHOOTING_STARS.map((meteor) => (
          <span
            key={meteor.id}
            className={styles.shootingStar}
            style={{
              left: meteor.left,
              top: meteor.top,
              width: meteor.length,
              animationDelay: meteor.delay,
              animationDuration: meteor.duration,
            }}
          />
        ))}
      </div>
      {/* Full-width 3D Perspective Viewport */}
      <div className={styles.viewport} role="group" aria-label="Sprint dependency diagram" tabIndex={0}>
        <div ref={rigRef} className={styles.cameraRig} style={cameraStyle}>
          {/* Symmetrical 4-Column Inline SVG Connectors */}
          <svg
            viewBox={`0 0 ${anchors.width} ${anchors.height}`}
            className={styles.svgLayer}
            aria-hidden="true"
          >
            <defs>
              <marker
                id="arrowMuted"
                viewBox="0 0 10 10"
                refX="7"
                refY="5"
                markerWidth="5"
                markerHeight="5"
                orient="auto-start-reverse"
              >
                <path d="M 0 1.5 L 8 5 L 0 8.5 z" fill="rgba(255, 255, 255, 0.72)" />
              </marker>
              <marker
                id="arrowRed"
                viewBox="0 0 10 10"
                refX="7"
                refY="5"
                markerWidth="5.5"
                markerHeight="5.5"
                orient="auto-start-reverse"
              >
                <path d="M 0 1.5 L 8 5 L 0 8.5 z" fill="var(--color-error)" />
              </marker>
              <marker
                id="arrowAmber"
                viewBox="0 0 10 10"
                refX="7"
                refY="5"
                markerWidth="5.5"
                markerHeight="5.5"
                orient="auto-start-reverse"
              >
                <path d="M 0 1.5 L 8 5 L 0 8.5 z" fill="rgba(255, 255, 255, 0.88)" />
              </marker>
              <marker
                id="arrowTeal"
                viewBox="0 0 10 10"
                refX="7"
                refY="5"
                markerWidth="5.5"
                markerHeight="5.5"
                orient="auto-start-reverse"
              >
                <path d="M 0 1.5 L 8 5 L 0 8.5 z" fill="rgba(255, 255, 255, 0.88)" />
              </marker>
              <mask id="redDashRevealMask">
                <path
                  d={pathDepFull}
                  pathLength="100"
                  strokeDasharray="100"
                  strokeDashoffset={
                    state.redPulseVisible ? 100 - state.redPulseT * 100 : 100
                  }
                  stroke="var(--color-text)"
                  strokeWidth="8"
                  strokeLinecap="round"
                  fill="none"
                />
              </mask>
            </defs>

            {/* Wave 1: Sprint -> Task 1 & Task 2 */}
            <path
              d={pathSprintToGit}
              pathLength="100"
              strokeDasharray="100"
              strokeDashoffset={100 - state.col1Draw * 100}
              className={styles.wireStateTeal}
              style={{ opacity: state.col1Draw > 0 ? 0.92 : 0 }}
            />

            <path
              d={pathSprintToDev21}
              pathLength="100"
              strokeDasharray="100"
              strokeDashoffset={100 - state.col1Draw * 100}
              className={styles.wireStateBlue}
              style={{ opacity: state.col1Draw > 0 ? 0.92 : 0 }}
            />

            {/* Wave 2 (Runs ONLY after Task 1 & Task 2 are Done): Task 1 -> Task 3 & Task 2 -> Task 4 */}
            <path
              d={pathGitToDev12}
              pathLength="100"
              strokeDasharray="100"
              strokeDashoffset={100 - state.col2Draw * 100}
              className={styles.wireStateBlue}
              style={{ opacity: state.col2Draw > 0 ? 0.92 : 0 }}
            />

            <path
              d={pathDev21ToSec07}
              pathLength="100"
              strokeDasharray="100"
              strokeDashoffset={100 - state.col2Draw * 100}
              className={styles.wireStateBlue}
              style={{ opacity: state.col2Draw > 0 ? 0.92 : 0 }}
            />

            {/* Task 4 -> Task 6 (Runs ONLY after Task 4 is Done) */}
            <path
              d={pathSec07ToRel04}
              pathLength="100"
              strokeDasharray="100"
              strokeDashoffset={100 - state.sec07ToRel04Draw * 100}
              className={styles.wireStateTeal}
              style={{ opacity: state.sec07ToRel04Draw > 0 ? 0.92 : 0 }}
            />

            {/* Task 5 -> Task 6 (Runs ONLY after Task 5 is Ready/Done) */}
            <path
              d={pathDev18ToRel04}
              pathLength="100"
              strokeDasharray="100"
              strokeDashoffset={100 - state.dev18ToRel04Draw * 100}
              className={styles.wireStateTeal}
              style={{ opacity: state.dev18ToRel04Draw > 0 ? 0.92 : 0 }}
            />

            {/* Red Dashed Line from Task 3 to Task 5 when Blocked (disappears when proposal is approved) */}
            <path
              d={pathDepFull}
              mask="url(#redDashRevealMask)"
              markerEnd={
                state.redPulseVisible && state.redPulseT > 0.9
                  ? 'url(#arrowRed)'
                  : undefined
              }
              className={styles.wireRed}
              style={{ opacity: state.redPulseVisible ? 0.95 : 0 }}
            />

            {/* Solid White Line from Task 3 to Task 5 once Reassign is approved / unlocked */}
            <path
              d={pathDepFull}
              pathLength="100"
              strokeDasharray="100"
              strokeDashoffset={100 - state.tealUnlockDraw * 100}
              markerEnd={state.tealUnlockDraw >= 0.9 ? 'url(#arrowTeal)' : undefined}
              className={styles.wireStateTeal}
              style={{ opacity: state.tealUnlockDraw > 0 ? 0.92 : 0 }}
            />

            {/* Vertical dashed link from Reassign Popover to Task 3 */}
            <line
              x1={dev12Top.x}
              y1={suggestionBottom.y}
              x2={dev12Top.x}
              y2={dev12Top.y}
              className={styles.wireSuggestion}
              style={{ opacity: state.suggestionVisible ? 1 : 0 }}
            />

            {/* Compact Mid-Wire Dependency Pill on Task 3 -> Task 5 */}
            {state.depWireDraw > 0.85 && (
              <g
                transform={`translate(${depMidPos.x}, ${depMidPos.y - 13})`}
                style={{ opacity: state.depWireDraw }}
              >
                <rect
                  x="-37"
                  y="-8.5"
                  width="74"
                  height="16"
                  className={styles.wireLabelBg}
                />
                {state.dev18Status === 'Ready' || state.tealUnlockDraw > 0.8 ? (
                  <g transform="translate(-29, -4.5)" stroke="var(--color-primary)" fill="none" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                    <rect x="0.5" y="4" width="7" height="5" rx="1.2" />
                    <path d="M2 4V2.5a2 2 0 0 1 3.8-.7" />
                  </g>
                ) : state.dev18Tone === 'red' || state.redPulseVisible ? (
                  <g transform="translate(-27, -4.5)" stroke="var(--color-error)" fill="none" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                    <rect x="0.5" y="4" width="7" height="5" rx="1.2" />
                    <path d="M2 4V2.5a2 2 0 0 1 4 0V4" />
                  </g>
                ) : null}
                <text
                  x={
                    state.dev18Status === 'Ready' ||
                    state.tealUnlockDraw > 0.8 ||
                    state.dev18Tone === 'red' ||
                    state.redPulseVisible
                      ? '5'
                      : '0'
                  }
                  y="2.5"
                  textAnchor="middle"
                  className={`${styles.wireLabel} ${
                    state.dev18Status === 'Ready' || state.tealUnlockDraw > 0.8
                      ? styles.wireLabelTeal
                      : state.dev18Status === 'Review'
                      ? styles.wireLabelAmber
                      : state.dev18Tone === 'red' || state.redPulseVisible
                      ? styles.wireLabelRed
                      : ''
                  }`}
                >
                  {state.dev18Status === 'Ready' || state.tealUnlockDraw > 0.8
                    ? 'Unblocked'
                    : state.dev18Status === 'Review'
                    ? '◎ Review'
                    : state.dev18Tone === 'red' || state.redPulseVisible
                    ? 'Blocked'
                    : '... Wait'}
                </text>
              </g>
            )}

            {/* Connection Ports on Cards (Appear only as the main white wire connects) */}
            {state.col1Draw > 0 && (
              <>
                <circle
                  cx={sprintOutTop.x}
                  cy={sprintOutTop.y}
                  r="3"
                  className={styles.portOuter}
                />
                <circle
                  cx={sprintOutBot.x}
                  cy={sprintOutBot.y}
                  r="3"
                  className={styles.portOuter}
                />
              </>
            )}
            {state.col1Draw > 0.9 && (
              <>
                <circle
                  cx={git09In.x}
                  cy={git09In.y}
                  r="3"
                  className={styles.portOuter}
                />
                <circle
                  cx={dev21In.x}
                  cy={dev21In.y}
                  r="3"
                  className={styles.portOuter}
                />
              </>
            )}
            {state.col2Draw > 0 && (
              <>
                <circle
                  cx={git09Out.x}
                  cy={git09Out.y}
                  r="3"
                  className={styles.portOuter}
                />
                <circle
                  cx={dev21Out.x}
                  cy={dev21Out.y}
                  r="3"
                  className={styles.portOuter}
                />
              </>
            )}
            {state.col2Draw > 0.9 && (
              <>
                <circle
                  cx={dev12In.x}
                  cy={dev12In.y}
                  r="3"
                  className={styles.portOuter}
                />
                <circle
                  cx={sec07In.x}
                  cy={sec07In.y}
                  r="3"
                  className={styles.portOuter}
                />
              </>
            )}
            {state.depWireDraw > 0 && (
              <circle
                cx={dev12Out.x}
                cy={dev12Out.y}
                r="3"
                className={`${styles.portOuter} ${
                  state.dev12Tone === 'red' ? styles.portOuterRed : ''
                }`}
              />
            )}
            {state.sec07ToRel04Draw > 0 && (
              <circle
                cx={sec07Out.x}
                cy={sec07Out.y}
                r="3"
                className={styles.portOuter}
              />
            )}
            {state.depWireDraw > 0.9 && (
              <circle
                cx={dev18In.x}
                cy={dev18In.y}
                r="3"
                className={`${styles.portOuter} ${
                  state.dev18Tone === 'red' ? styles.portOuterRed : ''
                }`}
              />
            )}
            {state.sec07ToRel04Draw > 0.9 && (
              <circle
                cx={rel04In.x}
                cy={rel04In.y}
                r="3"
                className={styles.portOuter}
              />
            )}

            {/* Traveling White Pulses on Sequential Wire Waves */}
            <circle
              cx={col1OrbTopPos.x}
              cy={col1OrbTopPos.y}
              r="3.8"
              className={styles.pulseTeal}
              style={{
                opacity: state.col1Draw > 0.02 && state.col1Draw < 0.98 ? 1 : 0,
              }}
            />
            <circle
              cx={col1OrbBotPos.x}
              cy={col1OrbBotPos.y}
              r="3.8"
              className={styles.pulseTeal}
              style={{
                opacity: state.col1Draw > 0.02 && state.col1Draw < 0.98 ? 1 : 0,
              }}
            />
            <circle
              cx={col2OrbTopPos.x}
              cy={col2OrbTopPos.y}
              r="3.8"
              className={styles.pulseTeal}
              style={{
                opacity: state.col2Draw > 0.02 && state.col2Draw < 0.98 ? 1 : 0,
              }}
            />
            <circle
              cx={col2OrbBotPos.x}
              cy={col2OrbBotPos.y}
              r="3.8"
              className={styles.pulseTeal}
              style={{
                opacity: state.col2Draw > 0.02 && state.col2Draw < 0.98 ? 1 : 0,
              }}
            />
            <circle
              cx={secToRelOrbPos.x}
              cy={secToRelOrbPos.y}
              r="3.8"
              className={styles.pulseTeal}
              style={{
                opacity:
                  state.sec07ToRel04Draw > 0.02 && state.sec07ToRel04Draw < 0.98
                    ? 1
                    : 0,
              }}
            />
            <circle
              cx={dev18ToRelOrbPos.x}
              cy={dev18ToRelOrbPos.y}
              r="3.8"
              className={styles.pulseTeal}
              style={{
                opacity:
                  state.dev18ToRel04Draw > 0.02 && state.dev18ToRel04Draw < 0.98
                    ? 1
                    : 0,
              }}
            />

            {/* Moving Bottleneck Pulse */}
            <circle
              cx={redOrbPos.x}
              cy={redOrbPos.y}
              r="3.8"
              className={styles.pulseRed}
              style={{
                opacity:
                  state.redPulseVisible &&
                  state.redPulseT > 0.02 &&
                  state.redPulseT < 0.98
                    ? 1
                    : 0,
              }}
            />

            {/* Moving Unlock Pulse */}
            <circle
              cx={tealOrbPos.x}
              cy={tealOrbPos.y}
              r="4.2"
              className={styles.pulseTeal}
              style={{ opacity: state.tealPulseVisible ? 1 : 0 }}
            />
          </svg>

          {/* 1. Left Hub: Sprint (Active Blue matching other Active cards) */}
          <div
            ref={sprintSlotRef}
            className={styles.slotSprint}
            style={sprintDepthStyle}
          >
            <article
              className={`${styles.card} ${styles.cardActiveBlue} ${
                !reducedMotion && elapsedSec >= 0.9 && elapsedSec < 1.8
                  ? styles.cardLifted
                  : ''
              }`}
            >
              <div className={styles.cardTopRow}>
                <div className={styles.iconIdentity}>
                  <span className={`${styles.iconBox} ${styles.iconBoxBlue}`}>
                    <svg
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      className={styles.nodeSvgIcon}
                    >
                      <polygon points="12 2 2 7 12 12 22 7 12 2" />
                      <polyline points="2 17 12 22 22 17" />
                      <polyline points="2 12 12 17 22 12" />
                    </svg>
                  </span>
                  <span className={styles.codeLabel}>Sprint</span>
                </div>

                <span className={`${styles.statusIconBadge} ${styles.badgeBlue}`}>
                  • Active
                </span>
              </div>

              <div className={styles.sprintRingWrap}>
                <div className={styles.ringLeft}>
                  <svg viewBox="0 0 32 32" className={styles.ringSvg} aria-hidden="true">
                    <circle cx="16" cy="16" r="12" className={styles.ringTrack} />
                    <circle
                      cx="16"
                      cy="16"
                      r="12"
                      strokeDasharray={ringCircumference}
                      strokeDashoffset={ringOffset}
                      className={styles.ringFill}
                    />
                  </svg>
                  <span className={styles.ringFraction}>
                    {state.completedCount}/6
                  </span>
                </div>

                <div className={styles.nodeDotsCluster}>
                  <span className={`${styles.miniDot} ${styles.miniDotTeal}`} />
                  <span
                    className={`${styles.miniDot} ${
                      state.dev12Tone === 'red'
                        ? styles.miniDotRed
                        : state.dev12Status === 'Review'
                        ? styles.miniDotAmber
                        : state.dev12Status === 'Done'
                        ? styles.miniDotTeal
                        : styles.miniDotBlue
                    }`}
                  />
                  <span className={`${styles.miniDot} ${styles.miniDotAmber}`} />
                </div>
              </div>
            </article>
          </div>

          {/* 2. Upper-Left Staggered Node: Task 1 (Wait -> Active -> Review -> Done) */}
          <div
            ref={git09SlotRef}
            className={styles.slotGit09}
            style={git09DepthStyle}
          >
            <article
              className={`${styles.card} ${
                !reducedMotion && elapsedSec >= 1.8 && elapsedSec < 3.85
                  ? styles.cardLifted
                  : ''
              }`}
            >
              <div className={styles.cardTopRow}>
                <div className={styles.iconIdentity}>
                  <span
                    className={`${styles.iconBox} ${
                      state.git09Status === 'Done'
                        ? styles.iconBoxTeal
                        : state.git09Status === 'Review'
                        ? styles.iconBoxAmber
                        : state.git09Status === 'Active'
                        ? styles.iconBoxBlue
                        : ''
                    }`}
                  >
                    <svg
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      className={styles.nodeSvgIcon}
                    >
                      <line x1="6" y1="3" x2="6" y2="15" />
                      <circle cx="18" cy="6" r="3" />
                      <circle cx="6" cy="18" r="3" />
                      <path d="M18 9a9 9 0 0 1-9 9" />
                    </svg>
                  </span>
                  <span className={styles.codeLabel}>Task 1</span>
                </div>

                <span
                  className={`${styles.statusIconBadge} ${
                    state.git09Status === 'Done'
                      ? styles.badgeTeal
                      : state.git09Status === 'Review'
                      ? styles.badgeReview
                      : state.git09Status === 'Active'
                      ? styles.badgeBlue
                      : styles.badgeNeutral
                  }`}
                >
                  {state.git09Status === 'Done'
                    ? '✓ Done'
                    : state.git09Status === 'Review'
                    ? '◎ Review'
                    : state.git09Status === 'Active'
                    ? '• Active'
                    : '... Wait'}
                </span>
              </div>

              <div className={styles.visualBarRow}>
                <div className={styles.segmentGroup}>
                  {[1, 2, 3].map((step) => (
                    <span
                      key={step}
                      className={`${styles.segmentBar} ${
                        step <= state.git09Segments
                          ? state.git09Status === 'Done'
                            ? styles.segmentBarTeal
                            : state.git09Status === 'Review'
                            ? styles.segmentBarAmber
                            : styles.segmentBarBlue
                          : ''
                      }`}
                    />
                  ))}
                </div>
                <span className={styles.avatarBadge}>
                  <span className={styles.avatarCircle}>
                    <svg
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      className={styles.userGlyphSvg}
                    >
                      <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                      <circle cx="12" cy="7" r="4" />
                    </svg>
                  </span>
                </span>
              </div>
            </article>
          </div>

          {/* 3. Lower-Left Staggered Node: Task 2 (Wait -> Active -> Review -> Done) */}
          <div
            ref={dev21SlotRef}
            className={styles.slotDev21}
            style={dev21DepthStyle}
          >
            <article
              className={`${styles.card} ${
                !reducedMotion && elapsedSec >= 1.8 && elapsedSec < 3.85
                  ? styles.cardLifted
                  : ''
              }`}
            >
              <div className={styles.cardTopRow}>
                <div className={styles.iconIdentity}>
                  <span
                    className={`${styles.iconBox} ${
                      state.dev21Status === 'Review'
                        ? styles.iconBoxAmber
                        : state.dev21Status === 'Done'
                        ? styles.iconBoxTeal
                        : state.dev21Status === 'Active'
                        ? styles.iconBoxBlue
                        : ''
                    }`}
                  >
                    <svg
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      className={styles.nodeSvgIcon}
                    >
                      <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
                      <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" />
                    </svg>
                  </span>
                  <span className={styles.codeLabel}>Task 2</span>
                </div>

                <span
                  className={`${styles.statusIconBadge} ${
                    state.dev21Status === 'Review'
                      ? styles.badgeReview
                      : state.dev21Status === 'Done'
                      ? styles.badgeTeal
                      : state.dev21Status === 'Active'
                      ? styles.badgeBlue
                      : styles.badgeNeutral
                  }`}
                >
                  {state.dev21Status === 'Done'
                    ? '✓ Done'
                    : state.dev21Status === 'Review'
                    ? '◎ Review'
                    : state.dev21Status === 'Active'
                    ? '• Active'
                    : '... Wait'}
                </span>
              </div>

              <div className={styles.visualBarRow}>
                <div className={styles.segmentGroup}>
                  {[1, 2, 3].map((step) => (
                    <span
                      key={step}
                      className={`${styles.segmentBar} ${
                        step <= state.dev21Segments
                          ? state.dev21Status === 'Review'
                            ? styles.segmentBarAmber
                            : state.dev21Status === 'Done'
                            ? styles.segmentBarTeal
                            : styles.segmentBarBlue
                          : ''
                      }`}
                    />
                  ))}
                </div>
                <span className={styles.avatarBadge}>
                  <span className={styles.avatarCircle}>
                    <svg
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      className={styles.userGlyphSvg}
                    >
                      <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                      <circle cx="12" cy="7" r="4" />
                    </svg>
                  </span>
                </span>
              </div>
            </article>
          </div>

          {/* 4. Center Focal Node: Task 3 (Wait -> Active -> Blocked [Red Border] -> Review -> Done) */}
          <div
            ref={dev12SlotRef}
            className={styles.slotDev12}
            style={dev12DepthStyle}
          >
            <article
              className={`${styles.card} ${
                state.dev12Tone === 'red' ? styles.cardBlockedRed : ''
              } ${
                !reducedMotion && elapsedSec >= 4.1 && elapsedSec < 7.45
                  ? styles.cardLifted
                  : ''
              }`}
            >
              <div className={styles.cardTopRow}>
                <div className={styles.iconIdentity}>
                  <span
                    className={`${styles.iconBox} ${
                      state.dev12Tone === 'red'
                        ? styles.iconBoxRed
                        : state.dev12Status === 'Review'
                        ? styles.iconBoxAmber
                        : state.dev12Status === 'Done'
                        ? styles.iconBoxTeal
                        : state.dev12Status === 'Active'
                        ? styles.iconBoxBlue
                        : ''
                    }`}
                  >
                    <svg
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      className={styles.nodeSvgIcon}
                    >
                      <path d="M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.778 7.778 5.5 5.5 0 0 1 7.777-7.777zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4" />
                    </svg>
                  </span>
                  <span className={styles.codeLabel}>Task 3</span>
                </div>

                <span
                  className={`${styles.statusIconBadge} ${
                    state.dev12Tone === 'red'
                      ? styles.badgeRed
                      : state.dev12Status === 'Review'
                      ? styles.badgeReview
                      : state.dev12Status === 'Done'
                      ? styles.badgeTeal
                      : state.dev12Status === 'Active'
                      ? styles.badgeBlue
                      : styles.badgeNeutral
                  }`}
                >
                  {state.dev12Tone === 'red' ? (
                    <>
                      <svg
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        className={styles.statusGlyphSvg}
                      >
                        <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
                        <path d="M7 11V7a5 5 0 0 1 10 0v4" />
                      </svg>
                      <span>Blocked</span>
                    </>
                  ) : state.dev12Status === 'Done' ? (
                    '✓ Done'
                  ) : state.dev12Status === 'Review' ? (
                    '◎ Review'
                  ) : state.dev12Status === 'Active' ? (
                    '• Active'
                  ) : (
                    '... Wait'
                  )}
                </span>
              </div>

              <div className={styles.visualBarRow}>
                <div className={styles.segmentGroup}>
                  {[1, 2, 3, 4].map((step) => (
                    <span
                      key={step}
                      className={`${styles.segmentBar} ${
                        step <= state.dev12Segments
                          ? state.dev12Tone === 'red'
                            ? styles.segmentBarRed
                            : state.dev12Status === 'Review'
                            ? styles.segmentBarAmber
                            : state.dev12Status === 'Done'
                            ? styles.segmentBarTeal
                            : styles.segmentBarBlue
                          : ''
                      }`}
                    />
                  ))}
                </div>

                <span
                  className={`${styles.avatarBadge} ${
                    state.dev12Reassigned ? styles.avatarBadgeActive : ''
                  }`}
                >
                  <span className={styles.avatarCircle}>
                    <svg
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      className={styles.userGlyphSvg}
                    >
                      <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                      <circle cx="12" cy="7" r="4" />
                    </svg>
                  </span>
                  {state.dev12Reassigned && <span>✓</span>}
                </span>
              </div>
            </article>
          </div>

          {/* Floating Reassign Badge above Task 3 */}
          <div
            ref={suggestionSlotRef}
            className={styles.slotSuggestion}
            style={{
              opacity: state.suggestionVisible ? 1 : 0,
              transform: state.suggestionVisible
                ? 'translate3d(0px, 0px, 0px) scale(1)'
                : 'translate3d(0px, 5px, 0px) scale(0.96)',
              transition: reducedMotion
                ? 'none'
                : 'opacity 220ms ease, transform 220ms ease',
            }}
          >
            <div className={styles.suggestionPopover}>
              <div className={styles.suggestionHeader}>
                <span className={styles.suggestionTitleWrap}>
                  <svg
                    width="9.5"
                    height="9.5"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2.4"
                  >
                    <path d="M12 3l1.9 5.8a2 2 0 0 0 1.3 1.3L21 12l-5.8 1.9a2 2 0 0 0-1.3 1.3L12 21l-1.9-5.8a2 2 0 0 0-1.3-1.3L3 12l5.8-1.9a2 2 0 0 0 1.3-1.3L12 3z" />
                  </svg>
                  Reassign
                </span>
                <span
                  className={styles.pmApprovedBadge}
                  style={{
                    opacity: state.pmApprovedVisible ? 1 : 0,
                    transform: state.pmApprovedVisible ? 'scale(1)' : 'scale(0.9)',
                  }}
                >
                  <svg
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    className={styles.pmCheckIcon}
                  >
                    <polyline points="20 6 9 17 4 12" />
                  </svg>
                  <span>PM</span>
                </span>
              </div>

              <div className={styles.suggestionVisualSwap}>
                <span className={styles.swapUser}>
                  <span className={`${styles.swapNodeBadge} ${styles.swapNodeLocked}`}>
                    <svg
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      className={styles.userGlyphSvg}
                    >
                      <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
                      <path d="M7 11V7a5 5 0 0 1 10 0v4" />
                    </svg>
                  </span>
                </span>
                <span className={styles.swapArrowIcon}>⇢</span>
                <span className={styles.swapUser}>
                  <span className={`${styles.swapNodeBadge} ${styles.swapNodeUnlocked}`}>
                    <svg
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      className={styles.userGlyphSvg}
                    >
                      <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
                      <path d="M7 11V7a5 5 0 0 1 9.9-1" />
                    </svg>
                  </span>
                </span>
              </div>
            </div>
          </div>

          {/* 5. Lower-Center Staggered Node: Task 4 (Wait -> Active -> Review -> Done) */}
          <div
            ref={sec07SlotRef}
            className={styles.slotSec07}
            style={sec07DepthStyle}
          >
            <article
              className={`${styles.card} ${
                !reducedMotion && elapsedSec >= 4.1 && elapsedSec < 5.85
                  ? styles.cardLifted
                  : ''
              }`}
            >
              <div className={styles.cardTopRow}>
                <div className={styles.iconIdentity}>
                  <span
                    className={`${styles.iconBox} ${
                      state.sec07Status === 'Review'
                        ? styles.iconBoxAmber
                        : state.sec07Status === 'Done'
                        ? styles.iconBoxTeal
                        : state.sec07Status === 'Active'
                        ? styles.iconBoxBlue
                        : ''
                    }`}
                  >
                    <svg
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      className={styles.nodeSvgIcon}
                    >
                      <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
                      <polyline points="9 12 11 14 15 10" />
                    </svg>
                  </span>
                  <span className={styles.codeLabel}>Task 4</span>
                </div>

                <span
                  className={`${styles.statusIconBadge} ${
                    state.sec07Status === 'Review'
                      ? styles.badgeReview
                      : state.sec07Status === 'Done'
                      ? styles.badgeTeal
                      : state.sec07Status === 'Active'
                      ? styles.badgeBlue
                      : styles.badgeNeutral
                  }`}
                >
                  {state.sec07Status === 'Done'
                    ? '✓ Done'
                    : state.sec07Status === 'Review'
                    ? '◎ Review'
                    : state.sec07Status === 'Active'
                    ? '• Active'
                    : '... Wait'}
                </span>
              </div>

              <div className={styles.visualBarRow}>
                <div className={styles.waveBars}>
                  {[5, 8, 10, 7, 10, 9].map((h, idx) => (
                    <span
                      key={idx}
                      style={{ height: `${h}px` }}
                      className={`${styles.waveBar} ${
                        state.sec07Status === 'Review'
                          ? styles.waveBarAmber
                          : state.sec07Status === 'Done'
                          ? styles.waveBarTeal
                          : state.sec07Status === 'Active'
                          ? styles.waveBarBlue
                          : ''
                      }`}
                    />
                  ))}
                </div>
                <span className={styles.avatarBadge}>
                  <span className={styles.avatarCircle}>
                    <svg
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      className={styles.userGlyphSvg}
                    >
                      <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                      <circle cx="12" cy="7" r="4" />
                    </svg>
                  </span>
                </span>
              </div>
            </article>
          </div>

          {/* 6. Upper-Right Staggered Node: Task 5 (Wait -> Blocked [Red Border] -> Active -> Review -> Ready) */}
          <div
            ref={dev18SlotRef}
            className={styles.slotDev18}
            style={dev18DepthStyle}
          >
            <article
              className={`${styles.card} ${
                state.dev18Tone === 'red' ? styles.cardBlockedRed : ''
              } ${
                !reducedMotion &&
                ((elapsedSec >= 5.45 && elapsedSec < 7.1) ||
                  (elapsedSec >= 7.45 && elapsedSec < 10.0))
                  ? styles.cardLifted
                  : ''
              }`}
            >
              <div className={styles.cardTopRow}>
                <div className={styles.iconIdentity}>
                  <span
                    className={`${styles.iconBox} ${
                      state.dev18Tone === 'red'
                        ? styles.iconBoxRed
                        : state.dev18Status === 'Review'
                        ? styles.iconBoxAmber
                        : state.dev18Status === 'Ready'
                        ? styles.iconBoxTeal
                        : state.dev18Status === 'Active'
                        ? styles.iconBoxBlue
                        : ''
                    }`}
                  >
                    <svg
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      className={styles.nodeSvgIcon}
                    >
                      <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" />
                      <polyline points="22 4 12 14.01 9 11.01" />
                    </svg>
                  </span>
                  <span className={styles.codeLabel}>Task 5</span>
                </div>

                <span
                  className={`${styles.statusIconBadge} ${
                    state.dev18Tone === 'red'
                      ? styles.badgeRed
                      : state.dev18Status === 'Review'
                      ? styles.badgeReview
                      : state.dev18Status === 'Ready'
                      ? styles.badgeTeal
                      : state.dev18Status === 'Active'
                      ? styles.badgeBlue
                      : styles.badgeNeutral
                  }`}
                >
                  {state.dev18Status === 'Ready' ? (
                    '✓ Ready'
                  ) : state.dev18Status === 'Review' ? (
                    '◎ Review'
                  ) : state.dev18Status === 'Active' ? (
                    '• Active'
                  ) : state.dev18Tone === 'red' ? (
                    <>
                      <svg
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        className={styles.statusGlyphSvg}
                      >
                        <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
                        <path d="M7 11V7a5 5 0 0 1 10 0v4" />
                      </svg>
                      <span>Blocked</span>
                    </>
                  ) : (
                    '... Wait'
                  )}
                </span>
              </div>

              <div className={styles.visualBarRow}>
                <div className={styles.segmentGroup}>
                  {[1, 2, 3, 4].map((step) => (
                    <span
                      key={step}
                      className={`${styles.segmentBar} ${
                        step <= state.dev18Segments
                          ? state.dev18Tone === 'red'
                            ? styles.segmentBarRed
                            : state.dev18Status === 'Review'
                            ? styles.segmentBarAmber
                            : state.dev18Status === 'Ready'
                            ? styles.segmentBarTeal
                            : styles.segmentBarBlue
                          : ''
                      }`}
                    />
                  ))}
                </div>
                <span className={styles.avatarBadge}>
                  <span className={styles.avatarCircle}>
                    <svg
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      className={styles.userGlyphSvg}
                    >
                      <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                      <circle cx="12" cy="7" r="4" />
                    </svg>
                  </span>
                </span>
              </div>
            </article>
          </div>

          {/* 7. Lower-Right Staggered Node: Task 6 (Wait -> Active -> Review -> Ready) */}
          <div
            ref={rel04SlotRef}
            className={styles.slotRel04}
            style={rel04DepthStyle}
          >
            <article
              className={`${styles.card} ${
                !reducedMotion &&
                ((elapsedSec >= 6.1 && elapsedSec < 6.85) ||
                  (elapsedSec >= 10.2 && elapsedSec < 11.6))
                  ? styles.cardLifted
                  : ''
              }`}
            >
              <div className={styles.cardTopRow}>
                <div className={styles.iconIdentity}>
                  <span
                    className={`${styles.iconBox} ${
                      state.rel04Status === 'Ready'
                        ? styles.iconBoxTeal
                        : state.rel04Status === 'Review'
                        ? styles.iconBoxAmber
                        : state.rel04Status === 'Active'
                        ? styles.iconBoxBlue
                        : ''
                    }`}
                  >
                    <svg
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      className={styles.nodeSvgIcon}
                    >
                      <path d="M4.5 16.5c-1.5 1.26-2 5-2 5s3.74-.5 5-2c.71-.84.7-2.13-.09-2.91a2.18 2.18 0 0 0-2.91-.09z" />
                      <path d="m12 15-3-3a22 22 0 0 1 2-3.95A12.88 12.88 0 0 1 22 2c0 2.72-.78 7.5-6 11a22.35 22.35 0 0 1-4 2z" />
                    </svg>
                  </span>
                  <span className={styles.codeLabel}>Task 6</span>
                </div>

                <span
                  className={`${styles.statusIconBadge} ${
                    state.rel04Status === 'Ready'
                      ? styles.badgeTeal
                      : state.rel04Status === 'Review'
                      ? styles.badgeReview
                      : state.rel04Status === 'Active'
                      ? styles.badgeBlue
                      : styles.badgeNeutral
                  }`}
                >
                  {state.rel04Status === 'Ready'
                    ? '✓ Ready'
                    : state.rel04Status === 'Review'
                    ? '◎ Review'
                    : state.rel04Status === 'Active'
                    ? '• Active'
                    : '... Wait'}
                </span>
              </div>

              <div className={styles.visualBarRow}>
                <div className={styles.waveBars}>
                  {[4, 7, 10, 6, 10, 8].map((h, idx) => (
                    <span
                      key={idx}
                      style={{ height: `${h}px` }}
                      className={`${styles.waveBar} ${
                        state.rel04Status === 'Ready'
                          ? styles.waveBarTeal
                          : state.rel04Status === 'Review'
                          ? styles.waveBarAmber
                          : state.rel04Status === 'Active'
                          ? styles.waveBarBlue
                          : ''
                      }`}
                    />
                  ))}
                </div>
                <span className={styles.avatarBadge}>
                  <span className={styles.avatarCircle}>
                    <svg
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      className={styles.userGlyphSvg}
                    >
                      <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                      <circle cx="12" cy="7" r="4" />
                    </svg>
                  </span>
                </span>
              </div>
            </article>
          </div>
        </div>
      </div>

      {/* Reduced Motion Static Summary */}
      {reducedMotion && (
        <div className={styles.reducedMotionSummary}>
          <div>
            <div className={styles.reducedMotionHeading}>
              1. Bottleneck Detected
            </div>
            <div>
              <strong>Task 3</strong> gets blocked, holding up dependent <strong>Task 5</strong>. Parallel tasks (<strong>Task 1</strong>, <strong>Task 2</strong>, <strong>Task 4</strong>) continue smoothly.
            </div>
          </div>
          <div>
            <div className={styles.reducedMotionHeading}>
              2. Auto-Reassign &amp; PM Approval
            </div>
            <div>
              DevSphere suggests reassigning <strong>Task 3</strong> (<em>User 3 ⇢ User 4</em>). Once <strong>PM OK</strong> is confirmed, ownership transfers immediately.
            </div>
          </div>
          <div>
            <div className={styles.reducedMotionHeading}>
              3. Flow Unlocked
            </div>
            <div>
              <strong>Task 3</strong> completes, sending a teal unlock pulse into <strong>Task 5</strong> and advancing <strong>Task 6</strong> to <em>Ready</em>.
            </div>
          </div>
        </div>
      )}
    </section>
  );
}
