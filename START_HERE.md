# Theta Camera — 全仕様・ソース引継ぎ R11

依頼：「ここまでの仕様を一つも漏らさず、zipにまとめて」。最新追加は写真から少しずつ登録する端末内の人物タグ付け。アプリ名はTheta Camera / Theta.Camera.Hatch、主対象はGalaxy Z Fold5。

## まず読む順番

1. `SPEC_COMPLETE.md` — 現時点の統合仕様。後の発言による変更を反映。
2. `REQUIREMENT_COVERAGE.csv` — CAM-001〜036と細分要件の照合表。
3. `FACE_TAGGING.md` — 新規写真の顔検出→タップ登録→次回から名前表示。
4. `DECISIONS_AND_OPEN_QUESTIONS.md` — 確定した方向、撤回・更新、設計案の区別。
5. `IMPLEMENTATION_STATUS.md` / `ROADMAP.md` — 実装済みと今後の作業。
6. `mock/index.html` — ブラウザで開ける最新版操作モック。
7. Androidを変更する際は `AGENTS.md` / `CLAUDE.md` / `README.md` / `VALIDATION.md`。

## このZIPに入っているもの

- Android 0.1.0のソース・Gradle wrapper・既存テスト・署名済みAPK（`apk/`）。コードとAPKは今回変更していない。
- 現在の操作モックの単独HTML、編集用ソース、過去モック、状態遷移検証コード。
- 統合仕様、全要件の照合表、発言ベースの要望台帳、追加ロードマップ、顔タグ設計。
- NAS持出しメタデータ案、ハードウェア候補、既存製品・編集UI・顔処理の参照URL。
- 手描き原案2枚、コンセプト動画とその再生成ソースZIP。
- R10までの文書原本（`archive/r10-documents/`）。履歴内の旧記述より統合仕様とR11が優先。
- `MANIFEST.json` / `SHA256SUMS.txt` — 同梱ファイルの一覧・サイズ・ハッシュ。

**モックを触れることとAndroidへの実装完了は別。** 顔検出・人物登録は今回仕様として追加したもので、モックにもAPKにもまだ実装していない。モックの映像・録画・音声・保存はシミュレーション。

所有者の更新インストール用署名鍵を従来どおり `signing/` に含む。ソースをGitHub等へ移す際は `.gitignore` に従い署名鍵・設定を公開しない。
