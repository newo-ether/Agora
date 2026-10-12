package com.newoether.agora.tool

/** Authoring contract for newly admitted e-ink requests; ordinary tool instructions stay unchanged. */
internal const val INTERACTIVE_ANSWER_EINK_GUIDANCE = """

E-ink adaptation for this request:
The host supplies mode-specific styling. Read document.documentElement.dataset.agoraDisplay
('default' or 'eink') and handle 'agora:displaychange' (detail.eInkMode).
In e-ink mode, favor stable layouts, clear high-contrast distinctions and infrequent,
meaningful visual updates; do not rely on colour alone. Avoid decorative motion.
Adapt animations and simulations to preserve their purpose and make progression understandable;
intermittent automatic updates or user-controlled steps may suit the task.
Use viewport space efficiently: keep primary visualization, controls and feedback together;
reveal secondary detail when useful.
Keep inputs responsive, reduce unnecessary redraws, and preserve current inputs and simulation
state when the display mode changes. Use document scrolling rather than nested vertical scrolling.
Keep e-ink adaptations conditional; retain ordinary appearance and behavior in default mode.
The host does not manage your JavaScript animation or update loops.
"""
