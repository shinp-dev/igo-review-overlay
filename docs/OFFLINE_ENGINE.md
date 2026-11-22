# 9路の端末内解析エンジン導入方針

## 決定事項
- KataGoという名前の採用より**9路の最善手・勝率・目数差の精度**を優先する
- **スマホ単体**で解析できることが完成条件。PC、LAN、外部APIが必須の方式は暫定扱い
- 一手ずつの解析のみ。悪手回数集計・棋譜入力・全局面グラフは対象外
- 終局後の観戦・振り返り用。盤面右側の余白タップで評価値の表示／非表示

## 比較した選択肢

| 候補 | 9路での期待精度 | Android実装の現実性 | 判断 |
| --- | --- | --- | --- |
| KataGo＋9路用モデル | 公式に9x9専用追加学習版あり | JNI/CPU推論とモデル導入が必要 | **第一候補** |
| KataGo＋小型b6c96 | 強力なGoニューラルネットだが9路専用モデルほどの精度は未確認 | [KataDroid](https://github.com/zhzy0077/KataDroid)でCPU＋JNI実績。ただし現行ブリッジは19路固定 | 実装の土台・実測ベースライン |
| GNU Go 3.8 | 現代のKataGoと同等の勝率・目数差精度は保証できない | [Android NDK移植](https://github.com/ligi/gobandroid-ai-gnugo)あり | 精度不足なら不採用 |

モデルの選択は知名度やファイルサイズだけで決めず、9路の固定局面で検証する。

### 参考資料
- [KataGo公式 9路専用ネットワーク](https://katagotraining.org/extra_networks/)
- [KataDroid Android JNI/LiteRT](https://github.com/zhzy0077/KataDroid)
- [KataGo公式リリース: 9路専用モデル](https://github.com/lightvector/KataGo/releases/tag/v1.13.2-kata9x9)

## 実装境界

`GoAnalysisEngine` で解析を抽象化し、現行の `LanGoAnalysisEngine` と、今後追加する `NativeGoAnalysisEngine` を差し替え可能にする。
現在の `LanGoAnalysisEngine` は **KataGoをPC側で実行する暫定方式**。
ネイティブの9路探索部分は **未実装**。クラスや設計だけで「組み込み完了」とはしない。

### ネイティブ実装の完了条件
1. ソース固定（commit、SHA-256）、ライセンス、モデルの配布条件を記録
2. JNI側で9×9の合法手・取り・コウ・中国ルール・コミ7を正しく扱う
3. 探索を直列ワーカーで実行し、局面が変更された際に旧結果を表示しない
4. Pixel 8a実機でCPU/メモリ/発熱/再解析を測る
5. 教師（KataGo 9路専用モデル）との最善手一致・形勢評価を比較
6. Wi-Fiを切った状態でAPKのみで動作し、設定画面のPC接続先が不要

現行の局面画像からは過去の手順・コウ履歴を完全復元できない。ネイティブ化しても**局面だけからの完全な合法性再現は不可能**なので、連続観測による履歴管理は別途検討する。
