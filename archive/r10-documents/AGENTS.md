# Development notes

Read README.md, REQUIREMENTS.md, ROADMAP.md, and VALIDATION.md before changing the app.

- Preserve applicationId and signing identity for upgrade compatibility; increment versionCode for new installable releases.
- Keep the signing directory private and out of git. The owner's handoff archive includes it deliberately.
- Do not imply Samsung proprietary processing or 50MP access is guaranteed. Query per-camera capabilities and show actual fallback behavior.
- Preserve the principal requirement: while this app is active, closed -> fully open initiates rear-camera selfie display using public WindowManager APIs.
- Do not repeatedly request Rear Display after a declined request in the same open/close cycle.
- Never unbind the camera during an active recording or an image save to satisfy an automatic display request.
- Keep permission denial, unsupported combinations, lifecycle transitions, and storage failures recoverable.
- No photo upload, analytics, location recording, or cloud processing unless separately requested.
- Validate with `./gradlew assembleRelease testDebugUnitTest lintDebug`. Physical Fold5 checks remain required; never equate compilation with device validation.

- Prioritize CAM-001 (one-tap recording immediately after launch) and CAM-002 (photos during uninterrupted recording). They are pending requirements, not features already implemented in 0.1.0.

- Also preserve the action-first interaction principle, future tactile case controller, and preset photo-sharing requirements CAM-004 through CAM-006. App-targeted sharing is distinct from guaranteed targeting of an arbitrary LINE conversation. Consult HARDWARE_CANDIDATES.md for researched references, not verified device compatibility.

- R3 requirements CAM-007 through CAM-011 add destination folders, encrypted private capture, receiving-app integration, document scanning, and voice-note transcription/tagging. These are unimplemented; 0.1.0 still writes ordinary media to DCIM. Keep private captures out of public MediaStore from the outset, and distinguish encryption from hiding. Do not treat a request for transcription as permission for automatic cloud upload.

R4 adds CAM-012 through CAM-016: standalone voice recorder, pen/voice/text notes, physical-action orchestration, wena integration, and PC continuity. All remain unimplemented. The concept film illustrates a proposed experience, not verified device behavior. Read ROADMAP.md before implementing these ideas.

R5 / CAM-017: owner already has DIMPLE.IO. Map its four NFC buttons to camera, audio, text, and handwriting entry points; capture first and choose destination/use afterward. Do not model DIMPLE as a BLE keyboard or promise locked-screen launch. Integration remains unimplemented.

R6 adds CAM-018/019: event albums such as YYYYMMDD + event name, selectable during capture and manageable later in an integrated library. Album selection must remain optional; retain capture-first behavior. Retroactive inclusion has an explicit scope. Album removal must not delete originals. All are unimplemented.

R7 supersedes the closed-to-open-only interpretation of CAM-003: the owner also wants launch-while-open with rear-camera preview and shutter on BOTH displays, plus accidental-touch protection. Query PRESENT_ON_AREA separately from TRANSFER_ACTIVITY_TO_AREA; Fold5 support is unverified. CAM-020..026 cover overlay folder long-press, annotation parity in camera/library, doc/QR scan, direct controls/horizon, 2x2 multicamera, dual displays and Japanese voice shutter. Do not guarantee 4 concurrent feeds or manufacturer-private features. Remove the permanent bottom mode bar from camera UI, but retain DIMPLE entry requirements. All new behavior remains unimplemented.


R8 adds CAM-027/028: in-place thumbnail preview, direct pen input without entering an editor menu, immediate text/voice attachments, derivative autosave preserving originals, and built-in video cuts. Read ROADMAP.md for requested behavior versus proposed controls. Keep capture and browsing states separate. For mockups, the owner explicitly wants ideal four-feed and concurrent recording interactions without hardware restrictions or repeated feasibility warnings. Real-device capability checks still apply to production implementation. Android functionality remains unimplemented.


R9 / CAM-029..031: stacked versions expand vertically; distinguish audio, text and video thumbnails. Separate storage folders (family/work) from dated events and multi-valued tags. Add favorites and searchable comments that travel to NAS with media. Read PORTABLE_METADATA.md for the documented XMP/JSON proposal; never call custom JSON automatically interoperable. Preserve originals and existing version snapshots. All remain unimplemented in Android.


R10 / CAM-032..035: explicit rear-camera cover preview with separately optional shutter; unfolding/folding demo showing both surfaces; LIVE label and collapsible overlays; chapter markers during recording while retaining simultaneous photo capture; trim/join editing with coarse seek, overlay jog, frame-step buttons and frame extraction. See ROADMAP.md and UI_REFERENCES.md. Preserve active capture through display changes and use media timestamps for real chapters/frame extraction. Mock uses 30fps simulated media, not actual encoding or verified device operation.
