# 9路向け端末内エンジン

## 現時点
`NativeGoAnalysisEngine` を通常モードで使用。KataGo v1.17.2のEigen CPUバックエンドをNDKでarm64向けクロスコンパイルし、モデルg170 b6c96を同梱する。既存の `LanGoAnalysisEngine` は旧PC連携方式としてのみ保管。

**モデルの軽量化とエンジン組み込みは別問題。**
初版はg170 b6c96を使ってGTPとスクリーンキャプチャを結ぶ。今回、公式9路専用 `kata9x9-b18c384nbt-20231025` もAPKに同梱し、設定画面で切り替えられるようにした（標準はg170）。9路専用の強さと端末内速度は未測定。精度だけでなく初期ロード時間・探索量・消費電力で評価する。探索スレッドは通常2、高負荷4、最大負荷6から選択し、GTP起動時に適用する。

## 検証目標
1. GitHub Actionsでarm64ネイティブ実行ファイルのビルドとモデルのSHA-256検証が成功する
2. APKに本体とネットワークが確実に同梱されている
3. Pixel 8aで `boardsize 9`、`loadsgf`、`kata-search_analyze` が正常動作する
4. 9路序盤・接触戦・コウ・終盤を少なくとも数十局面検査する
5. 画面を切り替え続けても古い候補が残らず、終了時にネイティブプロセスが残らない
6. 回線を切った状態で解析できること

## 実装上の注意
- GTPでは局面をSGFのAB/AWとPLから復元。履歴を持たないためコウの禁着検証は限定的
- `kata-search_analyze` 出力の `info move` を解析する。rootInfo true を指定して探索ルートの勝率・目数差を取得
- `ProcessBuilder` はAPKの `nativeLibraryDir/libkatago.so` を直接実行。これはJNIライブラリではなく名前を.soにしたPIE実行ファイル
- Android 14以降のバイナリ実行制限、16KBページ対応、長時間連続解析について実機で確認する

## Git完全同梱構成

- `app/src/main/jniLibs/arm64-v8a/libkatago.so` : ARM64 PIE実行ファイル
- `third_party/KataGo`, `third_party/Eigen` : 固定リビジョン対応ソースとライセンス
- `third_party/KataGo-models` : 両公式モデルの32MiB以下の分割ファイル（通常Git）
- `third_party/KataGo.arm64.sha256` : バイナリの検証用SHA-256

通常Gradleは同梱ファイルだけを検証し、モデルの元のgzipバイト列を復元してAPKに格納する。ネットワーク取得、NDK/CMakeは不要。モデル取り込み機能は従来通り。欠落・破損時にはビルド失敗とし、取得し直さない。

### 明示的なエンジン再ビルド（通常は不要）

同梱ソースとNDK 27.3.13750724 / CMake 3.22.1 / Ninjaを用いる。
`cmake -S third_party/KataGo/cpp -B .native-build/rebuild -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_TOOLCHAIN_FILE=<NDK>/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-34 -DANDROID_STL=c++_static "-DCMAKE_CXX_FLAGS=-DBYTE_ORDER=1234 -DLITTLE_ENDIAN=1234 -DBIG_ENDIAN=4321" -DUSE_BACKEND=EIGEN -DEIGEN3_INCLUDE_DIRS=<absolute path>/third_party/Eigen -DNO_GIT_REVISION=1 -DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384`
次に `cmake --build .native-build/rebuild --target katago --parallel 2`。
生成されたkatagoをlibkatago.soに格納し、SHA-256を更新する。通常のビルドやCIからはこの処理を実行しない。

### 外部取得の禁止

初回同梱専用Actionsと取得・移行用スクリプトは撤去済み。通常CIはGit管理済みのソース・実行ファイル・モデルのみを利用し、エンジンやモデルの外部取得を行わない。欠落や不整合時はビルドを中止する。
