# Igo Review Overlay — 囲碁クエスト9路振り返り

**9路専用**のAndroid解析オーバーレイです。囲碁クエストの振り返り画面をMediaProjectionで読み取り、KataGoの候補手・勝率・目数差を交点内に表示します。盤面上部の石に重ならない緑バーをタップすると評価値を表示／非表示にできます。盤面本体へのタップは奪いません。

## 現在の実装（2026-11-22）

- 9×9盤面認識、最終着手の丸印から手番判定、「枠調整」中だけ表示する左上／右下のドラッグハンドル（解析中は非表示）
- 候補手1〜5、勝率／目数差、探索回数50/100/200/500/1000、**オーバーヒート設定（探索2/4/6スレッド）**、透明度、評価値の文字サイズ70〜150%（初期100%）、手動・自動再解析
- **端末内KataGo CPUエンジン（GTP）**：公式KataGo v1.17.2をarm64-v8a向けに再現可能な手順でコンパイルし、ネイティブライブラリの格納先に実行ファイルとして同梱
- **モデル切替**：軽量`g170-b6c96-s175395328-d26788732`（CC0）／9路専用`kata9x9-b18c384nbt-20231025`（KataGo Network License）／手動取り込みモデル。両標準モデルをSHA-256検証してAPKに同梱（9路専用は約98MB増）
- SGFを通じた9路・中国ルール・コミ7の局面設定。KataGoのGTP `kata-search_analyze` による探索と解析表示
- 画面共有・KataGoの終了通知、解析結果の世代管理（古い盤面の候補手を出さない）
- JavaのSGF/GTP解析テスト、Pythonの旧ブリッジテスト、GitHub Actionsビルド

### 重要な区別

**ネイティブソースを追加しただけでは動作確認完了ではありません。**
GitHub Actionsのネイティブビルド・モデル取得・APK同梱が成功し、実機で起動／検索を確認するまでは検証版です。KataGoのCPU速度、9路専用モデルとの棋力差も未測定です。特に初回ネットワーク読み込みの速度・連続解析のメモリ使用量はPixel 8aで確認が必要です。

## APKのビルド

KataGo ARM64実行ファイル、対応するKataGo/Eigenソース、標準・9路専用ネットワークはすべてGitに同梱済みです。モデルは32MiB以下の分割ファイルをローカルで結合し、元のSHA-256で検証します。WindowsではGit、JDK 17以上、Android SDKが必要です。通常ビルドにNDK/CMake、外部エンジン・モデル取得、Git LFS、GitHub Actionsは不要です。

```bat
git clone https://github.com/shinp-dev/igo-review-overlay.git
cd igo-review-overlay
gradlew.bat :app:installDebug
```

Linux/macOSでは `./gradlew :app:installDebug`。初回から同梱済みファイルを利用し、欠落・破損時には外部取得にフォールバックせずビルドを中止します。通常のGradle・Android SDK依存関係は別途必要です。GitHub ActionsのAPKは任意です。

## 使い方

1. APKをインストールし、解析回数・候補数・表示内容を設定する（**PC接続設定は不要**）。
2. 「盤面解析を開始」を選び、Androidのオーバーレイ許可と画面全体の共有を許可する。
3. 囲碁クエストの9路の**終局後振り返り画面**で、位置がずれていれば「枠調整」を押し、ハンドルを最外周の交点に合わせ、「調整完了」を押す。解析中はハンドルを表示しない。
4. 一手ずつ動かすと盤面と最終手マークを認識して解析する。**再生ボタンの操作・検出に依存しない**。
5. 盤面の**上側の緑バー**をタップすると評価値の表示をオン／オフできる。操作パネルの「手番」「解析」「枠調整」「終了」も利用できる。

## 制約・注意点

- 黒／白の石と最終着手マークは画面の色・形状に依存。囲碁クエストのUI変更時は調整が必要です。実機未検証です。
- 現在は過去の着手履歴を復元しません。単独局面の**コウ・スーパーコウの履歴**は完全には再現できません。局面からの評価を参考値として扱ってください。
- GTP候補手の数値は**次の手番側**の評価です。ステータスの勝率／目数差にはGTPの `rootInfo true` で取得した探索全体の評価を使います。
- 標準は軽量なg170 b6c96ですが、9路専用b18モデルも設定から選べます。端末内での速度・温度・メモリ負荷は未測定です。オーバーヒート設定（4/6スレッド）は速度向上を保証しません。Android側の熱制御を回避する機能ではありません。
- 19路、棋譜保存、悪手回数、形勢グラフ、一括解析は初期版に含みません。
- 対局中のソフト使用のためではなく、終局後の振り返り用です。
- 旧LAN版は参考用に `LanGoAnalysisEngine` と `bridge/` を残していますが、通常起動では使いません。

## 実装・ライセンス

- KataGo: upstream `lightvector/KataGo` v1.17.2, commit `6a1fc5de9fc253723ac475a0683bf0b9d9b7bd19`。KataGo本体はMITベース＋同梱依存の個別ライセンス。LICENSEを `app/src/main/assets/licenses/KataGo-LICENSE.txt` に同梱します。
- g170モデル: 公式配布ファイル、SHA-256 `f57fddf4672364d385d6ab177364ab819810d1123e229cb2649c4f337a2160b1`。古いg170モデルはCC0例外の対象です。
- 9路専用モデル: 公式[KataGo 9x9リリース](https://github.com/lightvector/KataGo/releases/tag/v1.13.2-kata9x9)の`kata9x9-b18c384nbt-20231025.bin.gz`、SHA-256 `a1298ce1adc1dad7bd868ca962b2384cc8388ed373a00e6bae1114fa6f9e2d61`、[Neural Network License](https://katagotraining.org/network_license/)（アプリの`assets/licenses/KataGo-NETWORK-LICENSE.txt`に告知同梱）。
- 本リポジトリ固有のコード: MIT（[LICENSE](LICENSE)）。
- [KataGo公式コンパイルガイド](https://github.com/lightvector/KataGo/blob/master/Compiling.md)
- [KataGoのGTP拡張仕様](https://github.com/lightvector/KataGo/blob/master/docs/GTP_Extensions.md)
- [KataGoネットワークのライセンス](https://katagotraining.org/network_license/)
