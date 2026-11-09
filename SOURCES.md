# 技術資料

参照日: 2026-11-08。実装は独自のアプリコード。

- Samsung: Rear Displayの実装。One UI 6以降の公開APIとOS確認ダイアログ。
  https://developer.samsung.com/sdp/blog/en/2024/09/26/unfold-the-potential-of-galaxy-fold-devices-with-windowmanagers-rear-display-mode
- Android: Rear Display / dual-screen mode。
  https://developer.android.com/develop/ui/compose/layouts/adaptive/foldables/support-foldable-display-modes
- CameraXリリースとバージョン。
  https://developer.android.com/jetpack/androidx/releases/camera
- メーカー拡張。
  https://developer.android.com/media/camera/camerax/extensions-api
- カメラ拡張の対応端末。
  https://developer.android.com/media/camera/supported-devices
- 公式のカメラサンプル。
  https://github.com/android/camera-samples
- CameraX 1.6.2 ImageCapture APIソース。RAW+JPEGの保存コールバック順と出力形式の確認。
  https://dl.google.com/dl/android/maven2/androidx/camera/camera-core/1.6.2/camera-core-1.6.2-sources.jar

AndroidX等の依存ライブラリのライセンスはそれぞれの配布元に従う。ソースZIPは独自コードとビルド構成を含み、ビルド時に依存ライブラリを取得する。
