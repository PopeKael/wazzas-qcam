# Development history

Wazza's QCam is a practical, owner-directed derivative of CueCam by lustsazeus-lab. It was not presented as a new project written from scratch. The source, copyright attribution, and Apache-2.0 license are retained; the application interface and workflows were substantially changed for Wazza's filming needs.

Development was iterative and AI-assisted. Codex helped implement code changes; Wazza set priorities, reviewed the visible app, ran emulator checks, and used the physical OPPO for camera and recording checks. The AI assistant is a tool in that process, not a claimed team member or external tester.

## Milestones

- **2026-04-27 — CueCam source baseline:** the local project began from the upstream CueCam Android project. Its AndroidX/CameraX recorder and Apache-2.0 attribution informed the derivative.
- **2026-10-27 — 0.1 emulator checkpoint:** replaced the floating teleprompter experience with a single local script-and-camera screen, manual script scrolling, and portrait/landscape framing.
- **2026-10-27 — 0.2 physical-camera checkpoint:** verified camera controls and device-reported recording quality on an OPPO Reno15 Pro 5G. Rear-camera 4K was verified; CameraX did not report front UHD on that device.
- **2026-10-27 — 0.3 usability pass:** added the standalone script actions, expanded landscape editor space, CameraX-statistics microphone graph, MediaStore video review, and safer unsaved-draft handling.
- **2026-10-27 — 0.3.x presentation pass:** added orientation-specific approved splash artwork, the Wazza's QCam title, and the final microphone and portrait-control layout adjustments.
- **2026-10-27 — 1.0.0 preparation:** prepared the public documentation, attribution, optional browser support links, and direct GitHub distribution.

The public release scope and measured device results are summarized in the README. Emulator results and physical-device results are reported separately.
