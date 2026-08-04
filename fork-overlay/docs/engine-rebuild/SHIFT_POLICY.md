# シフト方針

休（慣例 index 0 = `ShiftKinds.REST`）は **通常のシフト種**。

| してはいけない | する |
|----------------|------|
| 休を OFF 特殊として別経路で書く | `canDo` / `allowedShifts` で一律判定 |
| `filter { it > 0 }` で休を候補から除外 | `ShiftKinds.otherThan` / `allowed` |
| 「OFF にする」という API | 休シフトを割り当てる |

連勤・週回数などで「出勤」と「休」を数えるのは制約の意味であり、休の割当禁止ではない。
