# 静的解析・敵対検証・修正サマリー

## Step 1 潜在リスク一覧

| 番号 | 該当箇所 | リスク種別 | 不具合 | 危険度 |
|------|----------|------------|--------|--------|
| 1 | SearchSessionFull.tryTransition(ANNEAL) (src) | 状態一貫性 | HARD 増を無条件受理し得る | **高** |
| 2 | tryMetropolis + reheat hot=1e9 | 状態一貫性 | 高温で HARD 悪化を current に取り込み得る | **高** |
| 3 | G1/propose nextInt(S) | 境界 | S=0 で IllegalArgumentException | **高** |
| 4 | budgetMs=0 かつ maxIters=0 | 無限ループ | timeUp が常に偽で while が終わらない | **高** |
| 5 | TemperatureParams.alpha≥1 | 停滞 | 温度が下がらず悪化受理が続く | **中** |
| 6 | 初期 schedule 形状 ≠ S×T | インデックス | ArrayIndexOutOfBounds | **高** |
| 7 | soft 表示 vs softSum | 一貫性 | 重み付きと件数が混同される | **低** |
| 8 | AlnsPolish 固定 200 iter | 計算量 | 大規模で後処理が重い（上限あり） | **低** |
| 9 | wish 固定 + c3n | 論理 | 構造壁を探索で消せない（データ問題） | **中**（仕様） |

## Step 2 敵対シナリオ

1. **S=0 / T=0 Problem** → nextInt(0) クラッシュ  
2. **ANNEAL で HARD+1 Move を連続投入** → best は守られても current が壊れ探索が迷走  
3. **budgetMs=0, maxIters=0** → 無限ループ（フリーズ）  
4. **alpha=1.0** → 冷却停止、SOFT 悪化の歩き回り  
5. **矛盾希望×禁止連続** → HARD が 1 残る（クラッシュではなく解なし）  

## Step 3 修正

1. ANNEAL: `rep.hard > currentReport.hard` なら revert+Reject  
2. tryMetropolis: 同様の HARD ゲート（再加熱でも不変）  
3. Session init: `ProblemGuards.requireRunnable` + shape check  
4. G1: runnable / zero-budget 早期 return  
5. TemperatureParams: alpha/tMin/window を安全範囲に clamp  

## 再発防止

- 合法性・HARD 非悪化は Session の単一入口で強制  
- 生成器は `ProblemGuards.isRunnable` 必須  
- 時間停止は `budgetMs>0 || maxIters>0` を前提  
