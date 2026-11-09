# 物理操作の部材・製品候補

調査: 2026-11-08。候補の整理であり、Fold5とTheta Cameraでの実機動作・装着は未検証。購入や採用は未確定。

| 候補 | 確認できた仕様 | この計画での位置付け |
|---|---|---|
| 8BitDo Micro | 16ボタン、72×40.7×14.1mm、24.8g、Android対応、Keyboard modeで割当可能 | 分解せずケース用ホルダーに装着し、アプリへキー入力を渡す最初の試作候補。現APKに任意キー割当の追加が必要 |
| SmallRig 4841 | 脱着式無線リモコン、2段シャッター、ズームレバー、Androidモード | 撮影用グリップの参考・試用候補。ズームは模擬タッチに切り替える方式もあり、自作アプリで全操作を自由に受け取れるかは未確認。4842はリモコン非同梱なので区別 |
| Leica LUX Grip | 2段シャッター、設定ダイヤル、2つの機能ボタン | 機械的な操作感・配置の参考。公式対応はiPhoneであり、Fold5用購入品として勧めるものではない |
| Seeed XIAO nRF52840 | 21×17.8mm、BLE、11 GPIO、電池充電回路 | ボタンとダイヤルを持つ小型自作コントローラのマイコン候補。配線とファームウェアが必要 |
| Seeed XIAO ESP32C3 | 21×17.8mm、BLE、11 GPIO | 別のマイコン候補。どちらを採用するかは未決定 |
| M5Stack Unit Encoder U135 | 回転＋押込み、I2C、32×24×25.4mm | 卓上／グリップ試作向け。単体でスマホにつながるBluetoothリモコンではない。薄型ケースには厚い |
| ALPS ALPINE EC11E系 | A/B相出力のロータリーエンコーダ。複数仕様あり | 最終的な小型ダイヤルの候補。型番別に寸法・押込み有無を確認 |
| ALPS ALPINE SKSD系 | 4.1×3.9mm、ダブルアクションの表面実装タクトスイッチ | 半押し／全押しの候補。小さく、実装基板と押し子の設計が必要 |

## 段階案

1. 8BitDo Microを分解せず、仮のケースホルダーで配置を試す。
2. Theta Cameraに外部キー割当と入力診断を追加し、写真・録画・ズーム・共有の操作を検証。
3. 欲しい配置が固まったら、XIAO＋専用スイッチ／エンコーダの自作ケースへ進む。

Fold5が開く動作と自撮り時の持ち替えが主用途なので、通常のスマホを幅方向に挟むクランプだけで適合を判断しない。ShiftCam ProGripのクランプは58〜90mm対応で、開いたFold5全体を挟む用途には向かない。ケース裏側の片側に収める設計を検討し、実機寸法で検証する。

## 参照先

- https://www.8bitdo.com/micro/
- https://support.8bitdo.com/faq/micro.html
- https://static.smallrig.com/mall/img/public/1731566479054_.pdf
- https://www.bhphotovideo.com/c/product/1857420-REG/smallrig_4841_rotatable_bilateral_quick_release.html/specs
- https://leica-camera.com/ja-JP/mobile/lux-grip
- https://leica-camera.com/ja-JP/mobile/lux-grip-silver/technical-specification
- https://wiki.seeedstudio.com/XIAO_BLE/
- https://www.switch-science.com/collections/seeed/products/8145
- https://wiki.seeedstudio.com/XIAO_ESP32C3_Getting_Started/
- https://shop.m5stack.com/products/encoder-unit
- https://tech.alpsalpine.com/j/products/category/encorders/sub/01/
- https://tech.alpsalpine.com/j/products/category/tact-switch/sub/02/series/sksd/
- https://shiftcam.gorgias.help/en-US/getting-started-progrip-1646265
