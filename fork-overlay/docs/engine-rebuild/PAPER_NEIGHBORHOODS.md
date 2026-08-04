# 論文由来の構造近傍

| 近傍 | 文献枠 | 実装 |
|------|--------|------|
| Vertical / Horizontal swap | Burke / Vanden Berghe roster | `PaperNeighborhoods` |
| Ejection chain | Glover | BFS path → vacancy |
| Kempe-like day chain | Timetabling / coloring | 同一日 shift A↔B 多対 |
| Ruin-and-recreate | Schrimpf | staff window / day partial |
| Or-opt | TSP 系の系列再配置 | 行上の部分系列挿入 |
| Weekend block | Nurse rostering pattern | 金土日ブロック交換 |
| Block / Rect | 既存 BlockMoves | 帯状四角・転送 |
| VNS | Hansen & Mladenović | `VnsPolish` k=1..kMax |
| ALNS | Ropke & Pisinger | `AlnsPolish` 適応 destroy |

採否は常に STRICT（betterReport）。
