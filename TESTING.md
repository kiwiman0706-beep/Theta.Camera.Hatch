# テストのしかた（ADB で Fold5 をつないで、ローカルの Claude Code から）

シリーズ共通の ADB 仕様（Theta.Hatch.Series `spec/THETA_ADB_SPEC.md`）の窓口を持っている。
どちらも `android.permission.DUMP` で守られていて、adb のシェルからしか使えない。

## 準備

```bash
adb devices
```

Release の APK を入れる（手元ビルドはデバッグ鍵なので、Release 版の上には入らない）。

```bash
curl -L -o ThetaCameraHatch.apk https://github.com/kiwiman0706-beep/Theta.Camera.Hatch/releases/latest/download/ThetaCameraHatch-$(curl -s https://api.github.com/repos/kiwiman0706-beep/Theta.Camera.Hatch/releases/latest | python3 -c 'import sys,json;print(json.load(sys.stdin)["tag_name"][1:])').apk
```

```bash
adb install -r ThetaCameraHatch.apk
```

許可をまとめて与えて起動する。

```bash
adb shell pm grant app.thetahatch.camera android.permission.CAMERA && adb shell pm grant app.thetahatch.camera android.permission.RECORD_AUDIO
```

```bash
adb shell am start -n app.thetahatch.camera/com.theta.camera.hatch.MainActivity
```

## 状態を見る

アプリが開いている間だけ答える（閉じていると `{"error":"app not open"}`）。

```bash
adb shell dumpsys activity service app.thetahatch.camera/app.thetahatch.adb.StatusService
```

または：

```bash
adb shell am broadcast -a app.thetahatch.adb.STATUS -n app.thetahatch.camera/app.thetahatch.adb.CommandReceiver
```

中身：版、足りない許可、カメラ（接続・前後・倍率・レンズ・録画中と秒数・写真モード・比率・画質・fps・フラッシュ・タイマー・EV・写真の解像度）、折りたたみ（ヒンジ角度・半開き・Flex・外画面の状態）、画面（操作表示・声シャッター・保存先・イベント）、直近のエラー 10 件。

## 操作する

形：`adb shell am broadcast -a app.thetahatch.adb.<命令> -n app.thetahatch.camera/app.thetahatch.adb.CommandReceiver --es value <値>`

結果は `result=0`（成功）と `data="{...}"`。知らない命令は 1、アプリが開いていなければ 2。

| 命令 | value | すること |
| --- | --- | --- |
| `PHOTO` | — | 写真（タイマーが設定されていればタイマー付き） |
| `BURST` | 枚数（既定 5） | 連写 |
| `VIDEO` | `start`・`stop`（省略で切替） | 録画 |
| `ZOOM` | 倍率（例 `0.6`・`3`） | ズーム |
| `FACING` | `back`・`front` | 前後のカメラ |
| `MODE` | `standard`・`hdr`・`lowlight`・`auto`・`bokeh`・`face` | 写真モード |
| `FPS` | `30`・`120`・`240` | フレームレート（120・240 はスロー） |
| `ASPECT` | `3:4`・`9:16`・`1:1`・`full` | 比率 |
| `EV` | 段（例 `-0.7`） | 明るさ |
| `FLASH` | `off`・`auto`・`on`・`torch` | フラッシュ |
| `TIMER` | `0`・`3`・`5`・`10` | セルフタイマー |
| `HUD` | `show`・`hide` | 操作の表示 |
| `FOLDER` | フォルダ名 | 保存先（無ければ作る） |
| `EVENT` | イベント名・`none`（`--es start 2026-11-09 --es end 2026-11-10`） | イベント |
| `VOICE` | `on`・`off` | 声シャッター |
| `COVER` | `on`・`off` | 外画面ライブ |
| `REAR` | — | 外画面で自撮り（切替） |
| `REBIND` | — | カメラを再接続 |
| `OPEN` | `settings`・`diagnostics`・`update`・`events`・`search`・`detail` | 画面を開く |
| `CHECK_UPDATE` | — | 新しい版を確認 |
| `DIAG` | — | 端末診断の全文 |
| `GET` / `SET` | `--es key <名前>`（SET は `--es value`） | 設定（autoRear・audio・grid・level・lockPortrait・coverShutter・updateAuto・highResolution・heifClap） |

例：

```bash
adb shell am broadcast -a app.thetahatch.adb.ZOOM -n app.thetahatch.camera/app.thetahatch.adb.CommandReceiver --es value 3
```

## 画面・ログ

```bash
adb exec-out screencap -p > screen.png
```

```bash
adb logcat -s ThetaHatch:* AndroidRuntime:E CameraX:W
```

撮れたファイル：

```bash
adb shell ls -lR /sdcard/DCIM/ThetaCamera /sdcard/Documents/ThetaCamera
```

## 人の手が要ること（ADB ではできない）

- 開く・閉じる・半分折る（Flex モード、外画面で自撮り、外画面ライブ）
- 外画面への切替のときに OS が出す確認
- 声シャッターの「はいチーズ」
- 端末の向きを変える（アイコンの回転）

## 実機で未確認のこと

Fold5（SC-55D）で確かめたら、ここから消していく。

- 写真と動画の同時接続、録画中の写真
- 連写の速さ、比率ごとの保存範囲
- スローモーション 120／240fps、HDR（シーン）、暗所ブースト
- Flex モード、外画面ライブ、外画面で自撮り
- アイコンの回転
- 声シャッター
- サイドカー（Documents/ThetaCamera の .xmp・.theta.json）
- アプリ内アップデート
- 写真の保存形式 HEIF（全体＋表示範囲 clap）：保存できるか、Samsung ギャラリー・Google フォト・PC（Windows・Mac）で画面どおりに切れて見えるか、全体の 4080×3060 が残っているか（`heif-info` や libheif で確認）。動画はこの形式では切り出さない
