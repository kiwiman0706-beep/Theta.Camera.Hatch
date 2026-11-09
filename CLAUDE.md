## Theta Hatch シリーズ共通（必ず参照）

このアプリは Theta Hatch シリーズの 1 つです。シリーズ共通の仕様・デザイン・鍵の決まりは
GitHub の kiwiman0706-beep/Theta.Hatch.Series にあり、このリポジトリの docs より優先します。

- 作業を始める前に、Theta.Hatch.Series を読める状態にする
  （クラウドのスレッドでは add_repo で kiwiman0706-beep/Theta.Hatch.Series を追加して clone する）
- 読むもの：
  - `spec/THETA_HATCH_SPEC.md`（共通仕様）、`spec/THETA_VAULT_SPEC.md`、`spec/THETA_SHARED_SETTINGS.md`、`spec/THETA_GOOGLE_SETUP.md`
  - `design/README.md`（スキン：system・dark・light・pop・alert・scifi。色は `design/skins/skins.json`）
  - `spec/THETA_HATCH_SPEC.md` §6.1（アプリ内自動更新は必須）、`docs/MIGRATION.md`・`catalog/apps.json`（このアプリの目標の ID・リポジトリ名）
  - `secrets/README.md`（署名鍵はシリーズ共通。Secret の名前は ANDROID_KEYSTORE_B64・ANDROID_KEYSTORE_PASSWORD・ANDROID_KEY_ALIAS・ANDROID_KEY_PASSWORD）
- 共通仕様の写しをこのリポジトリに増やさない（変えるときは Theta.Hatch.Series 側を直す）
- 鍵・パスワード・API キーをリポジトリに入れない
- 移行や大きな変更の方針と進み具合は、Theta.Hatch.Series の `progress/<id>.md` に書く（書き方は `progress/_TEMPLATE.md`。自分のアプリのファイルだけを触る）
- 作品の版や概要が変わったら、Theta.Hatch.Series の README の一覧も直す

### このアプリ

- 表示名 Theta Camera Hatch、applicationId `app.thetahatch.camera`（0.2.0 から。0.1.0 の `com.theta.camera.hatch`・旧鍵とは別アプリ）
- Kotlin の namespace（パッケージ）は `com.theta.camera.hatch` のまま
- 署名は CI の Secret 4 つ（`.github/workflows/release.yml`）。手元ビルドは鍵が無ければデバッグ鍵で、配布には使わない
- 更新は `HatchUpdate.kt` でハブ（Home・KidsHome）に頼む。`REQUEST_INSTALL_PACKAGES` は書かない
- UI はカメラの性質上ネイティブ（CameraX）。共通 UI（HTML/CSS/JS）・スキン対応は今後の課題

> R11 全仕様版：まず START_HERE.md と SPEC_COMPLETE.md を参照。Androidは0.1.0のまま。最新モックは mock/index.html に同梱。

# Development handoff

Read AGENTS.md, README.md, REQUIREMENTS.md, ROADMAP.md, and VALIDATION.md before changing code.

The owner intends to continue development in another coding environment. This archive includes the baseline source, APK, build wrapper, tests, and private signing material.

The newest priority requirements are CAM-001 (one tap to start video directly after launch) and CAM-002 (take photos during uninterrupted video recording). They are not implemented in baseline 0.1.0. Preserve the earlier Fold5 rear-camera selfie auto-switch requirement.

（0.2.0 で Hatch 準拠に変更：applicationId は app.thetahatch.camera、署名はシリーズ共通鍵。旧 signing/ の鍵は使わない。）Never equate compiling with Fold5 device verification. Record future requests and implementation status in ROADMAP.md.

Read the R2 additions CAM-004 through CAM-006: action-first interaction, tactile case controls, and preset sharing. HARDWARE_CANDIDATES.md records references and limitations. These are roadmap requirements, not implemented features.

R3 adds CAM-007 through CAM-011: folder routing, encrypted/private records, custom memo/business-card app import, document scanner, and attached voice notes with transcription/tags/notes. All remain unimplemented. Read storage and cloud-processing constraints in ROADMAP.md before coding; existing 0.1.0 does not provide confidential storage.

R4 adds CAM-012 through CAM-016: standalone voice recorder, pen/voice/text notes, physical-action orchestration, wena integration, and PC continuity. All remain unimplemented. The concept film illustrates a proposed experience, not verified device behavior. Read ROADMAP.md before implementing these ideas.

R5 / CAM-017: owner already has DIMPLE.IO. Map its four NFC buttons to camera, audio, text, and handwriting entry points; capture first and choose destination/use afterward. Do not model DIMPLE as a BLE keyboard or promise locked-screen launch. Integration remains unimplemented.

R6 adds CAM-018/019: event albums such as YYYYMMDD + event name, selectable during capture and manageable later in an integrated library. Album selection must remain optional; retain capture-first behavior. Retroactive inclusion has an explicit scope. Album removal must not delete originals. All are unimplemented.

R7 supersedes the closed-to-open-only interpretation of CAM-003: the owner also wants launch-while-open with rear-camera preview and shutter on BOTH displays, plus accidental-touch protection. Query PRESENT_ON_AREA separately from TRANSFER_ACTIVITY_TO_AREA; Fold5 support is unverified. CAM-020..026 cover overlay folder long-press, annotation parity in camera/library, doc/QR scan, direct controls/horizon, 2x2 multicamera, dual displays and Japanese voice shutter. Do not guarantee 4 concurrent feeds or manufacturer-private features. Remove the permanent bottom mode bar from camera UI, but retain DIMPLE entry requirements. All new behavior remains unimplemented.


R8 adds CAM-027/028: in-place thumbnail preview, direct pen input without entering an editor menu, immediate text/voice attachments, derivative autosave preserving originals, and built-in video cuts. Read ROADMAP.md for requested behavior versus proposed controls. Keep capture and browsing states separate. For mockups, the owner explicitly wants ideal four-feed and concurrent recording interactions without hardware restrictions or repeated feasibility warnings. Real-device capability checks still apply to production implementation. Android functionality remains unimplemented.


R9 / CAM-029..031: stacked versions expand vertically; distinguish audio, text and video thumbnails. Separate storage folders (family/work) from dated events and multi-valued tags. Add favorites and searchable comments that travel to NAS with media. Read PORTABLE_METADATA.md for the documented XMP/JSON proposal; never call custom JSON automatically interoperable. Preserve originals and existing version snapshots. All remain unimplemented in Android.


R10 / CAM-032..035: explicit rear-camera cover preview with separately optional shutter; unfolding/folding demo showing both surfaces; LIVE label and collapsible overlays; chapter markers during recording while retaining simultaneous photo capture; trim/join editing with coarse seek, overlay jog, frame-step buttons and frame extraction. See ROADMAP.md and UI_REFERENCES.md. Preserve active capture through display changes and use media timestamps for real chapters/frame extraction. Mock uses 30fps simulated media, not actual encoding or verified device operation.


## R11：CAM-036 人物登録と全仕様書出し

新しい写真の顔を端末内で検出し、顔タップで名前登録または既存人物へ追加、次の写真から名前付き照合。事前の家族写真収集・一括登録を必須にしない。日々の撮影で少しずつ登録を増やす。候補／確定と訂正を分け、確認済みの見本を保持する設計。詳細と受入基準はFACE_TAGGING.md。顔機能はAPK・モックとも未実装。

今回、統合仕様・照合表・最新モック・スケッチ・動画／再生成ソースをまとめて同梱。これまでの追記を削除せず、旧文書原本もarchive/r10-documentsに保持。全仕様の入口はSTART_HERE.md。
