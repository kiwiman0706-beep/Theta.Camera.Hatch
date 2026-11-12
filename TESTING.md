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

## 撮って返す（ほかの Hatch から呼ばれる）

入口は `app.thetahatch.action.CAPTURE`（シリーズ仕様 §6.2）。権限 `app.thetahatch.permission.SHARE`（signature）で守っているので、**adb の `am start` からは開けない**（シェルは共通鍵で署名されていない）。Memo Hatch など共通鍵のアプリから試す。

呼ばれている間は STATUS の `capture` に呼び元・モード・返す件数が出る。返したファイルはアプリの `cache/capture/` に残り、30 分より古いものは次の起動で消える。

確かめること：
- Memo の「写真を撮る」「スキャン」から開き、上に「↩ Memo Hatch へ」、下に「やめる｜写真｜動画｜スキャン｜完了（n）」が出る
- 写真（HEIF・JPEG）・動画・スキャン（pdf／images）が Memo に届く。multiple のときは「完了」で返る
- やめる・戻るで RESULT_CANCELED
- 呼ばれたときは Camera Hatch のギャラリー・フォルダに残らない（設定「呼ばれたときも自分にも保存」ON なら残る）

## 人の手が要ること（ADB ではできない）

- 開く・閉じる・半分折る（Flex モード、外画面で自撮り、外画面ライブ）
- 外画面への切替のときに OS が出す確認
- 声シャッターの「はいチーズ」
- 端末の向きを変える（アイコンの回転）

### つまずきやすいところ（Windows の Git Bash から）

- `/sdcard/...` が Windows のパスに書き換えられる。先に `export MSYS_NO_PATHCONV=1`
- Fold5 は画面が 2 つあるので、`screencap` は `-d <ID>` で選ぶ（ID は `adb shell dumpsys SurfaceFlinger --display-id`。内画面 4630947175568309891、外画面 4630946481096930692）。`exec-out screencap` は壊れることがあるので、`adb shell screencap -p /sdcard/Download/s.png` → `adb pull`
- ワイヤレスデバッグは開閉で切れることがある。つなぎ直すと同じ端末が 2 つ見えるので `export ANDROID_SERIAL=<片方>`

## 実機で未確認のこと

Fold5（SC-55D）で確かめたら、ここから消していく。

- 撮って返す（上の節）
- アプリ内アップデートの「更新あり」→ ダウンロード → インストールの流れ（最新版の上では試せない。設定 → アップデートの画面が出て、版 0.13.1 と自動確認 ON が表示されるところまでは確認）
- HEIF を Mac（写真・プレビュー）で開いたときの表示範囲
- HDR（シーン）の写りの差（設定がかかって撮れるところまでは確認）

## 実機で確かめたこと（2026-11-10、0.13.1 Release、Fold5 SC-55D）

- 写真と動画の同時接続：OK。録画中に写真 2 枚が保存され、動画は 9.56 秒・1080p・30fps で止まらない（写真を撮った前後で 1 コマずつ落ちる）
- 連写：10 枚で約 6.8 秒（1 枚あたり約 0.68 秒、約 1.5 枚／秒）。JPEG で保存
- 比率ごとの保存範囲（HEIF、全体＝ispe、表示範囲＝clap、中央）：
  - 3:4 → 全体 4000×3000、表示 3998×3000
  - 9:16 → 全体 4000×2252、表示 4000×2250
  - 1:1 → 全体 4000×3000、表示 3000×3000
  - フル → 全体 4000×2252、表示 4000×1718
  - 回転は irot（縦持ちで 270°）。全体として残るのは 4000×3000 か 4000×2252 で、4080×3060 にはならない（高解像度 OFF の既定）
- HEIF の見え方：libheif（ImageMagick）と Windows フォトは画面どおりに切れて見える。Samsung ギャラリーは clap を無視して全体を表示。Google フォトは範囲は切るが縦横比が全体のままで、横に引き伸ばされる（下の「問題」）
- HDR（シーン）：`MODE hdr` で CONTROL_MODE=USE_SCENE_MODE・SCENE_MODE=HDR が要求に入り、撮影・保存できる
- 暗所ブースト：Fold5 は非対応（OS が "Low-light boost is not supported"）。メニューには出ない
- Flex モード：半分に折ってノート PC の形（折り目が横）にすると、横画面で上が映像・下が操作になり、撮影できる。本の形（折り目が縦）では Flex に入らない（仕様どおり）
- 外画面ライブ：内画面を開いたまま `COVER on` で外画面にライブ映像と LIVE が出る。被写体側から見て向き・左右・遅れとも自然
- 外画面で自撮り：全部開いて `REAR` → OS の確認どおり裏返すと外画面にメインカメラの映像が出て、撮影・保存できる。もう一度 `REAR` で戻る
- アイコンの回転：縦固定のまま、横・反対の横に回すと線アイコン・倍率・サムネイルが起き上がる（左上の「LIVE／広角・1.0×」の文字は回らない）
- 声シャッター：「はいチーズ」2 回で 2 枚撮れた（端末内の音声認識）
- サイドカー：Documents/ThetaCamera/<フォルダ>/ に写真・動画ごとの .xmp と .theta.json ができる。.theta.json に folder・event・display_crop（clap-center、表示／全体のサイズ）が入る

## 実機で見つかった問題（0.13.1）

- スローモーション 120／240fps が使えない：端末は高速度撮影（1920×1080・1280×720 で 120／240）に対応しているのに、`Recorder.getHighSpeedVideoCapabilities` が null で「このレンズはスローモーション非対応です」→ 標準設定で接続し直す。30fps の動画になる。ADB の `FPS 120` は ok を返し、状態の errors にも出ない（DIAG の Last error にだけ出る）
  - 原因：Fold5 の `/vendor/etc/media_profiles*.xml` に高速度用プロファイル（highspeed）が 1 つも無い。CameraX の高速度撮影はこれを前提にしている
  - 0.13.2（作業中、未リリース）：CameraX が使えないときは Camera2 の constrained high-speed セッション＋MediaRecorder（撮影 fps → 再生 30fps）で撮る `SlowMotion.kt` を足した。**実機ではまだ撮れていない**。1 回目はセッション（operatingMode=1）までは組めたが、最初のフレームで HAL がエラー（CamX の AWB で "Input tuning data is NULL"）→ 標準に戻った。プレビューも TEMPLATE_RECORD にそろえ、SessionConfiguration でセッションのパラメータを渡すよう直した版は、電池切れで未確認。これでも駄目なら Samsung 独自の設定が要る可能性があり、Fold5 ではスローをメニューに出さないことも考える
- Google フォトで HEIF が横に引き伸ばされる（clap で切ったあと、全体の縦横比で表示している）。Samsung ギャラリーでは切れない
- HEIF に Exif が入らない（HeifWriter で作り直すため。撮影日時は MediaStore の DATE_TAKEN だけ）
- HEIF 保存のとき、連写の JPEG は画面どおりに切られず全体（フルなら 4000×2252）で保存される → 0.13.2 で、見えていた範囲をサイドカーの display_crop に残すようにした（JPEG 自体は全体のまま。実機未確認）
- ADB の `MODE lowlight` は非対応の端末でも ok を返す（0.13.2 で失敗を返すように直した。`FPS` も標準に戻ったときは失敗を返し、接続の失敗は errors に出す。実機未確認）
