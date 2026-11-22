# Igo Review Overlay — 囲碁クエスト9路振り返り

**9路専用**のAndroid解析オーバーレイです。囲碁クエストの振り返り画面をMediaProjectionで読み取り、KataGoの候補手・勝率・目数差を交点内に表示します。盤面右側の細い余白タップで評価値を表示／非表示にできます。盤面本体へのタップは奪いません。

## 現在の実装（2026-11-22）

- 9×9盤面認識、最終着手の丸印から手番判定、盤面枠の左上／右下ドラッグ調整
- 候補手1〜5、勝率／目数差、探索回数50/100/200/500/1000、透明度、手動・自動再解析
- **端末内KataGo CPUエンジン（GTP）**：公式KataGo v1.17.2をarm64-v8a向けに再現可能な手順でコンパイルし、ネイティブライブラリの格納先に実行ファイルとして同梱
- **公式小型モデル** `g170-b6c96-s175395328-d26788732`（CC0）をダウンロード時にSHA-256検証してAPKに同梱
- SGFを通じた9路・中国ルール・コミ7の局面設定。KataGoのGTP `kata-search_analyze` による探索と解析表示
- 画面共有・KataGoの終了通知、解析結果の世代管理（古い盤面の候補手を出さない）
- JavaのSGF/GTP解析テスト、Pythonの旧ブリッジテスト、GitHub Actionsビルド

### 重要な区別

**ネイティブソースを追加しただけでは動作確認完了ではありません。**
GitHub Actionsのネイティブビルド・モデル取得・APK同梱が成功し、実機で起動／検索を確認するまでは検証版です。KataGoのCPU速度、9路専用モデルとの棋力差も未測定です。特に初回ネットワーク読み込みの速度・連続解析のメモリ使用量はPixel 8aで確認が必要です。

## APKのビルド

本プロジェクトはKataGo本体とモデルをGit管理せず、**公式ソース**と**公式重み**からビルド時に同梱します。Android SDK 36、NDK 27.3.13750724、JDK 17、Ninja、Eigen3ヘッダー、CMakeが必要です。

```bash
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.3.13750724"
bash tools/build_katago_android.sh
bash tools/prepare_model.sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

GitHub Actionsの `Android CI` が成功した場合は、成果物の `igo-review-overlay-offline-debug-apk` にKataGo＋モデルが入っています。単純に `./gradlew` だけ実行した場合、ネイティブエンジン／モデルが準備されていないため解析できません。**前段の2スクリプトが必須です。**

## 使い方

1. APKをインストールし、解析回数・候補数・表示内容を設定する（**PC接続設定は不要**）。
2. 「盤面解析を開始」を選び、Androidのオーバーレイ許可と画面全体の共有を許可する。
3. 囲碁クエストの9路の**終局後振り返り画面**で、ハンドルを最外周の交点に合わせる。
4. 一手ずつ動かすと盤面と最終手マークを認識して解析する。**再生ボタンの操作・検出に依存しない**。
5. 盤面の**右側余白の帯**をタップすると評価値の表示をオン／オフできる。操作パネルの「手番」「解析」「終了」も利用できる。

## 制約・注意点

- 黒／白の石と最終着手マークは画面の色・形状に依存。囲碁クエストのUI変更時は調整が必要です。実機未検証です。
- 現在は過去の着手履歴を復元しません。単独局面の**コウ・スーパーコウの履歴**は完全には再現できません。局面からの評価を参考値として扱ってください。
- GTP候補手の数値は**次の手番側**の評価です。ステータスの勝率／目数差にはGTPの `rootInfo true` で取得した探索全体の評価を使います。
- 同梱モデルは軽量なg170 b6c96で、**KataGoの9路専用高精度モデルではありません**。9路専用モデルは精度と端末内性能を比較してから拡張する予定です。
- 19路、棋譜保存、悪手回数、形勢グラフ、一括解析は初期版に含みません。
- 対局中のソフト使用のためではなく、終局後の振り返り用です。
- 旧LAN版は参考用に `LanGoAnalysisEngine` と `bridge/` を残していますが、通常起動では使いません。

## 実装・ライセンス

- KataGo: upstream `lightvector/KataGo` v1.17.2, commit `6a1fc5de9fc253723ac475a0683bf0b9d9b7bd19`。KataGo本体はMITベース＋同梱依存の個別ライセンス。LICENSEを `app/src/main/assets/licenses/KataGo-LICENSE.txt` に同梱します。
- g170モデル: 公式配布ファイル、SHA-256 `f57fddf4672364d385d6ab177364ab819810d1123e229cb2649c4f337a2160b1`。古いg170モデルはCC0例外の対象であり、最新のKataGo全モデルに同じ条件が適用されるわけではありません。
- 本リポジトリ固有のコード: MIT（[LICENSE](LICENSE)）。
- [KataGo公式コンパイルガイド](https://github.com/lightvector/KataGo/blob/master/Compiling.md)
- [KataGoのGTP拡張仕様](https://github.com/lightvector/KataGo/blob/master/docs/GTP_Extensions.md)
- [KataGoネットワークのライセンス](https://katagotraining.org/network_license/)
