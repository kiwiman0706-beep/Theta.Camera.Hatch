# Development handoff

Read AGENTS.md, README.md, REQUIREMENTS.md, ROADMAP.md, and VALIDATION.md before changing code.

The owner intends to continue development in another coding environment. This archive includes the baseline source, APK, build wrapper, tests, and private signing material.

The newest priority requirements are CAM-001 (one tap to start video directly after launch) and CAM-002 (take photos during uninterrupted video recording). They are not implemented in baseline 0.1.0. Preserve the earlier Fold5 rear-camera selfie auto-switch requirement.

Keep signing/ private. Preserve applicationId and signing identity for updates. Never equate compiling with Fold5 device verification. Record future requests and implementation status in ROADMAP.md.

Read the R2 additions CAM-004 through CAM-006: action-first interaction, tactile case controls, and preset sharing. HARDWARE_CANDIDATES.md records references and limitations. These are roadmap requirements, not implemented features.

R3 adds CAM-007 through CAM-011: folder routing, encrypted/private records, custom memo/business-card app import, document scanner, and attached voice notes with transcription/tags/notes. All remain unimplemented. Read storage and cloud-processing constraints in ROADMAP.md before coding; existing 0.1.0 does not provide confidential storage.

R4 adds CAM-012 through CAM-016: standalone voice recorder, pen/voice/text notes, physical-action orchestration, wena integration, and PC continuity. All remain unimplemented. The concept film illustrates a proposed experience, not verified device behavior. Read ROADMAP.md before implementing these ideas.

R5 / CAM-017: owner already has DIMPLE.IO. Map its four NFC buttons to camera, audio, text, and handwriting entry points; capture first and choose destination/use afterward. Do not model DIMPLE as a BLE keyboard or promise locked-screen launch. Integration remains unimplemented.

R6 adds CAM-018/019: event albums such as YYYYMMDD + event name, selectable during capture and manageable later in an integrated library. Album selection must remain optional; retain capture-first behavior. Retroactive inclusion has an explicit scope. Album removal must not delete originals. All are unimplemented.

R7 supersedes the closed-to-open-only interpretation of CAM-003: the owner also wants launch-while-open with rear-camera preview and shutter on BOTH displays, plus accidental-touch protection. Query PRESENT_ON_AREA separately from TRANSFER_ACTIVITY_TO_AREA; Fold5 support is unverified. CAM-020..026 cover overlay folder long-press, annotation parity in camera/library, doc/QR scan, direct controls/horizon, 2x2 multicamera, dual displays and Japanese voice shutter. Do not guarantee 4 concurrent feeds or manufacturer-private features. Remove the permanent bottom mode bar from camera UI, but retain DIMPLE entry requirements. All new behavior remains unimplemented.


R8 adds CAM-027/028: in-place thumbnail preview, direct pen input without entering an editor menu, immediate text/voice attachments, derivative autosave preserving originals, and built-in video cuts. Read ROADMAP.md for requested behavior versus proposed controls. Keep capture and browsing states separate. For mockups, the owner explicitly wants ideal four-feed and concurrent recording interactions without hardware restrictions or repeated feasibility warnings. Real-device capability checks still apply to production implementation. Android functionality remains unimplemented.


R9 / CAM-029..031: stacked versions expand vertically; distinguish audio, text and video thumbnails. Separate storage folders (family/work) from dated events and multi-valued tags. Add favorites and searchable comments that travel to NAS with media. Read PORTABLE_METADATA.md for the documented XMP/JSON proposal; never call custom JSON automatically interoperable. Preserve originals and existing version snapshots. All remain unimplemented in Android.


R10 / CAM-032..035: explicit rear-camera cover preview with separately optional shutter; unfolding/folding demo showing both surfaces; LIVE label and collapsible overlays; chapter markers during recording while retaining simultaneous photo capture; trim/join editing with coarse seek, overlay jog, frame-step buttons and frame extraction. See ROADMAP.md and UI_REFERENCES.md. Preserve active capture through display changes and use media timestamps for real chapters/frame extraction. Mock uses 30fps simulated media, not actual encoding or verified device operation.
