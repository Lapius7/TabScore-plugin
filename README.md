# TabScore

Paper 26.3 用プラグイン。タブリストのプレイヤー名の右に出す大きな数字(TAB の `playerlist-objective`)を、コマンドで切り替えられるようにします。
数値は本体の統計ではなく、**このプラグイン自身が保存する統計**(SQLite)から出します。

- Java 25 / Paper 26.3 / PlaceholderAPI(任意)
- ビルド: `./gradlew build` → `build/libs/tabscore-0.1.0.jar`

## 保存するもの(プレイヤー UUID ごと、累計+日別)

| 項目 | 内訳(サブキー) | 表示単位 |
|---|---|---|
| `mine` / `place` | ブロック ID ごと | 個 |
| `kill` | エンティティ ID ごと + `player` `monster` `animal` `other` | 体 |
| `deaths` | 死因ごと | 回 |
| `damage_dealt` / `damage_taken` | 相手 ID / 原因ごと | HP(内部は 0.1HP 単位) |
| `distance` | `walk sprint sneak swim fly elytra boat horse minecart pig strider vehicle climb fall` | **m**(内部は cm。`fall` は合計に含めない) |
| `playtime` | `active`(実プレイ)`afk` `imported` | **分**(内部は秒) |
| `logins` | - | 回 |
| `fish` `breed` `trade` `use` `craft` `drop` `pickup` `consume` `item_break` `chat` `command` `jump` `enchant` | アイテム ID など | 回/個 |

最終ログインとログイン回数は `/tabscore info` で見られます。

## コマンド

| コマンド | 権限 | 内容 |
|---|---|---|
| `/tabscore change <項目>` | `tabscore.admin`(既定 OP) | 全員の名前右に出す項目を切替。再起動後も保持 |
| `/tabscore me [項目]` | `tabscore.use`(既定 全員) | 自分の値 |
| `/tabscore top [項目]` | `tabscore.use` | 上位 10 人 |
| `/tabscore info [プレイヤー]` | `tabscore.use` | 詳細一覧(今日の値つき) |
| `/tabscore reload` | `tabscore.admin` | config.yml / lang.yml 再読み込み |
| `/tabscore import [confirm]` | OP / コンソールのみ | 本体の統計を一度だけ取り込み(二重実行不可) |

### 項目の書式

`<カテゴリ>[:<キー>][:today|:week]`

`mine` `mine:diamond_ore` `place:stone` `kill` `kill:zombie` `kill:monster` `deaths` `deaths:fall` `distance` `distance:elytra` `playtime` `playtime:active` `damage_dealt` `damage_taken:fall` `mine:stone:today` `distance:week` など。Tab 補完でブロック・エンティティ名も出ます。`today` は今日(設定のタイムゾーン)、`week` は今日を含む直近 7 日。

## PlaceholderAPI

| プレースホルダー | 値 |
|---|---|
| `%tabscore_value%` | 現在選択中の項目の値(**整数のみ**、カンマ無し) |
| `%tabscore_label%` | 項目名(例 `掘ったブロック: stone (今日)`) |
| `%tabscore_selector%` / `%tabscore_unit%` | 項目 ID / 単位 |
| `%tabscore_<項目>%` | 任意項目の整数値。`%tabscore_mine:stone%` または `%tabscore_mine_stone_today%` |

値はオンラインのプレイヤーのみメモリから返します(オフラインは 0)。重い処理はしません。

## TAB の設定(Jori への導入時)

`plugins/TAB/config.yml`:

```yaml
playerlist-objective:
  enabled: true
  value: "%tabscore_value%"
  fancy-display-default: "%player%"   # 既存の設定に合わせる
placeholderapi-refresh-intervals:
  default-refresh-interval: 500
  "%tabscore_value%": 1000
```

変更後 `/tab reload`。`value` は整数が必要で、`%tabscore_value%` は常に整数を返します。

## 設定・言語

`plugins/TabScore/config.yml`: データ保存先・フラッシュ間隔・Bot 除外・既定項目・AFK 秒数。`lang.yml`: 全メッセージ(MiniMessage)。
Bot 除外は `bots.name-regex` / `bots.names` / `bots.uuids` と、接続アドレスの無い偽プレイヤーの自動除外(`exclude-no-address`)。

## 仕組み・注意

- イベントはメモリ上で集計し、別スレッドで `flush-interval-seconds` ごと・退出時・停止時に SQLite へ書きます。メインスレッドで I/O はしません。
- スキーマ版を DB に持ち、古い DB は起動時に順に移行します。新しい版の DB は読み込みを拒否します。
- 移動距離・稼働時間は毎秒サンプリングします。テレポートは距離に入れません。
- `import` はプレイ中の統計の近似を含みます: 本体に「置いた数」が無いため、ブロックの「使用数」を `place` に入れます。本体の死亡数・ダメージは種別が無いので `imported` キーに入ります。
- 作成(`craft`)の Shift クリックは、材料の最小スタック数で概算します。
