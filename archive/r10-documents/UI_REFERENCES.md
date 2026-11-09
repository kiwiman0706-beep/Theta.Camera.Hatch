# UI references — capture, dual screens and video editing

調査日：2026-11-09。機能の参考資料であり、そのアプリやハードウェアの全機能を今回実装済みという意味ではない。各製品を同じ環境で比較試験したものではない。

| 参考 | 確認できた機能 | Thetaに採り入れる案 |
|---|---|---|
| Final Cut Pro for iPad | ジョグの表示・非表示、左右配置、フレーム単位移動、IN/OUT設定との併用 | 必要時だけ出るオーバーレイジョグ。粗いシークと独立した1コマ操作 |
| DaVinci Resolve Speed Editor | JOG／SHUTTLE／SCROLLで移動方法を使い分ける物理ダイヤル | デフォルトは精密ジョグ。将来のDIMPLE等の物理操作とも役割を整理 |
| Blackmagic Camera | HUD上の項目を直接操作、上下スワイプでHUDを隠す | LIVEを広く取り、設定メニューへの往復を減らす |
| Samsung Galaxy Z Fold2 Dual Preview | 内外画面で被写体が構図を確認。Rear Cam Selfieとは別の説明 | 外画面は映像のみ／撮影ボタンありを切替 |
| GoPro HiLight Tags | 撮影中に見どころをマークし、後で見つける | 録画を止めずに「章＋」。静止画保存とは別の操作 |
| Nikon D780 | 動画の選択フレームをJPEG保存、インデックスを追加 | 編集画面でコマを決め、その場で静止画保存 |
| Open Camera | 音による撮影開始、音量キーの撮影・フォーカス等への割当 | 画面以外の入口を用意。音量検知と日本語キーワード認識は区別 |
| Braintoss | 写真・音声・テキストをすぐ入力し受け皿へ送る | まず記録して後で用途を決める入口 |

## Primary references

- Final Cut Pro for iPad — Make precise edits with the jog wheel
  https://support.apple.com/guide/final-cut-pro-ipad/make-precise-edits-with-the-jog-wheel-dev06c7d60ae/ipados
- Blackmagic Design — DaVinci Resolve keyboards
  https://www.blackmagicdesign.com/jp/products/davinciresolve/keyboard
- Blackmagic Camera
  https://www.blackmagicdesign.com/jp/products/blackmagiccamera
- Samsung — Galaxy Z Fold2 cover screen / Dual Preview
  https://www.samsung.com/uk/support/mobile-devices/how-can-i-use-the-cover-screen-of-galaxy-z-fold2/
- GoPro — HiLight Tag（19 Things You Didn’t Know Your GoPro Can Do内）
  https://gopro.com/en/hu/news/things-you-didnt-know-your-gopro-can-do
- Nikon D780 — Editing Movies
  https://onlinemanual.nikonimglib.com/d780/en/07_options_for_movie_recording_and_editing_05.html
- Open Camera Help
  https://opencamera.org.uk/help.html
- Braintoss
  https://braintoss.app/

## 本構想で統合する対象

個別の機能には先例がある。今回確認した範囲で「即撮影→画面内の手書き・音声→イベント・タグ・☆→NASに持ち出せる情報」を全て同じ操作面に統合している製品は確認できていない。製品が存在しないという断定ではない。
