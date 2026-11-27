<div align="center">

<p align="center"><a href="README.md">English</a> | <b>日本語</b></p>

<h1 align="center">TabScore</h1>

<h3 align="center">タブリストの名前の右に、好きな統計の数字を出す。</h3>

<p align="center">プレイヤーの統計を自前で SQLite に記録し、PlaceholderAPI で出す Paper 26.3 用プラグインです。TAB の <code>playerlist-objective</code> に出す項目を、コマンド1つで切り替えられます。</p>

<p align="center">
  <a href="https://github.com/Lapius7/TabScore-plugin/releases"><img alt="Release" src="https://img.shields.io/github/v/release/Lapius7/TabScore-plugin?label=release&style=flat-square&color=e8833a&logo=github"></a>
  <img alt="Paper 26.3" src="https://img.shields.io/badge/Paper-26.3-62b47a?style=flat-square">
  <img alt="Java 25" src="https://img.shields.io/badge/Java-25-ed8b00?style=flat-square&logo=openjdk&logoColor=white">
  <a href="https://github.com/Lapius7/TabScore-plugin/stargazers"><img alt="Stars" src="https://img.shields.io/github/stars/Lapius7/TabScore-plugin?style=flat-square&color=e3b341&logo=github"></a>
  <a href="https://github.com/Lapius7/TabScore-plugin/commits/main"><img alt="Last commit" src="https://img.shields.io/github/last-commit/Lapius7/TabScore-plugin?style=flat-square&color=3b82f6"></a>
</p>

<p align="center">
  <a href="#導入">導入</a> ·
  <a href="#コマンド">コマンド</a> ·
  <a href="#プレースホルダー">プレースホルダー</a> ·
  <a href="#設定">設定</a> ·
  <a href="#注意">注意</a>
</p>

</div>

---

TabScore は、ブロックを掘った数、倒したモブ、歩いた距離、プレイ時間などを数え、プレイヤーの UUID ごとに累計と日別で保存します。数値はゲーム本体の統計ではなく、このプラグイン自身の記録から出します。管理者が `/tabscore change` で項目を1つ選ぶと、`%tabscore_value%` が全員分の値を返します。このプレースホルダーを TAB に設定すると、タブリストの名前の右に数字が出ます。

## 特長

- プレイヤーごとの累計と日別の値を、自前の統計として SQLite に保存します。再起動しても消えません。
- `/tabscore change mine:diamond_ore:today` のように、全員のタブリストの数字をコマンドで変えられます。選んだ項目は再起動後も残ります。
- `/tabscore me`・`top`・`info` で、自分の値、上位 10 人、今日の値つきの一覧を見られます。
- エンティティ数、チャンク数、TPS、MSPT、CPU、RAM のプレースホルダーを、どのスレッドからでも解決できる形で返します。TAB から使えます。
- ゲーム本体の統計ファイルを、オフラインのプレイヤーも含めて DB に一度だけ取り込めます。
- 名前、UUID、接続アドレスの有無で、偽プレイヤー(Bot)を記録から外せます。
- イベントはメモリ上で数え、別スレッドで SQLite に書くので、メインスレッドで I/O をしません。

## 導入

Paper 26.3 と Java 25 が必要です。プレースホルダーには PlaceholderAPI、タブリストへの表示には TAB を使います。

1. [Releases](https://github.com/Lapius7/TabScore-plugin/releases) から `tabscore-0.1.0.jar` を取得し、`plugins/` に置きます。
2. [PlaceholderAPI](https://github.com/PlaceholderAPI/PlaceholderAPI) と [TAB](https://github.com/NEZNAMY/TAB) を未導入なら入れます。
3. サーバーを再起動します。
4. `plugins/TAB/config.yml` の `playerlist-objective` の `value` に、プレースホルダーを設定します。

```yaml
playerlist-objective:
  enabled: true
  value: "%tabscore_value%"
  fancy-display-default: "%player%"   # 既存の設定に合わせる
placeholderapi-refresh-intervals:
  default-refresh-interval: 500
  "%tabscore_value%": 1000
```

5. `/tab reload` を実行し、`/tabscore change mine` のように表示する項目を選びます。

`value` には整数が必要です。`%tabscore_value%` は常に整数を返します。

> [!NOTE]
> TAB の実画面での表示は、まだ実機で確認していません。コマンドとプレースホルダーはこのプラグインが提供するものです。TAB での描画は、TAB の版と設定によって変わります。

## コマンド

`/tabscore` には別名 `/ts` があります。

| コマンド | 権限 | 内容 |
|---|---|---|
| `/tabscore change <項目>` | `tabscore.admin`(既定は OP) | 全員の名前の右に出す項目を切り替えます。再起動後も保持されます |
| `/tabscore me [項目]` | `tabscore.use`(既定は全員) | 自分の値を表示します |
| `/tabscore top [項目]` | `tabscore.use` | 上位 10 人を表示します |
| `/tabscore info [プレイヤー]` | `tabscore.use` | 今日の値つきの詳細一覧を表示します |
| `/tabscore reload` | `tabscore.admin` | `config.yml` と `lang.yml` を再読み込みします |
| `/tabscore import [confirm]` | OP またはコンソール | ゲーム本体の統計を一度だけ取り込みます(二重実行はできません) |

### 項目の書式

`<カテゴリ>[:<キー>][:today|:week]`

例: `mine`、`mine:diamond_ore`、`place:stone`、`kill`、`kill:zombie`、`kill:monster`、`deaths`、`deaths:fall`、`distance`、`distance:elytra`、`playtime`、`playtime:active`、`damage_dealt`、`damage_taken:fall`、`mine:stone:today`、`distance:week`。Tab 補完では、ブロックやエンティティの名前も出ます。

`today` は設定したタイムゾーンでの今日、`week` は今日を含む直近 7 日です。

<details>
<summary>記録する項目</summary>

すべての項目を、プレイヤーの UUID ごとに、累計と日別で保存します。

| 項目 | 内訳(サブキー) | 表示単位 |
|---|---|---|
| `mine` / `place` | ブロック ID ごと | 個 |
| `kill` | エンティティ ID ごと + `player` `monster` `animal` `other` | 体 |
| `deaths` | 死因ごと | 回 |
| `damage_dealt` / `damage_taken` | 相手 / 原因ごと | HP(内部は 0.1HP 単位) |
| `distance` | `walk sprint sneak swim fly elytra boat horse minecart pig strider vehicle climb fall` | **m**(内部は cm。`fall` は合計に含めません) |
| `playtime` | `active`(実プレイ)`afk` `imported` | **分**(内部は秒) |
| `logins` | なし | 回 |
| `fish` `breed` `trade` `use` `craft` `drop` `pickup` `consume` `item_break` `chat` `command` `jump` `enchant` | アイテム ID など | 回/個 |

最終ログインとログイン回数は `/tabscore info` で見られます。

</details>

## プレースホルダー

| プレースホルダー | 値 |
|---|---|
| `%tabscore_value%` | 現在選択中の項目の値(**整数のみ**、カンマ無し) |
| `%tabscore_label%` | 項目名(例 `掘ったブロック: stone (今日)`) |
| `%tabscore_selector%` / `%tabscore_unit%` | 項目 ID / 単位 |
| `%tabscore_<項目>%` | 任意項目の整数値。`%tabscore_mine:stone%` または `%tabscore_mine_stone_today%` |

値はオンラインのプレイヤーについてだけ、メモリから返します。オフラインのプレイヤーは 0 で、DB は読みません。

<details>
<summary>サーバー負荷のプレースホルダー</summary>

PlaceholderAPI 標準の `%server_total_entities%` は、TAB が非同期スレッドから呼ぶと解決しません。次のプレースホルダーは、メインスレッドで集計してキャッシュした値を返すので、どのスレッドから呼んでも解決します。プレイヤーは不要です。

| プレースホルダー | 値 |
|---|---|
| `%tabscore_entities%` / `%tabscore_living%` | 全ワールドのエンティティ数 / うち生物数 |
| `%tabscore_tiles%` | タイルエンティティ数(読み込み済みチャンク) |
| `%tabscore_chunks%` / `%tabscore_forced%` | 読み込み済みチャンク数 / 強制ロードチャンク数 |
| `%tabscore_players%` | オンライン人数 |
| `%tabscore_entities_<world>%` / `%tabscore_chunks_<world>%` | ワールド別(例 `%tabscore_entities_world_nether%`) |
| `%tabscore_tps1%` `%tabscore_tps5%` `%tabscore_tps15%` | TPS(上限 20、小数1桁) |
| `%tabscore_mspt%` | 平均 tick 時間(ms) |
| `%tabscore_cpu%` | プロセスの CPU 使用率(%) |
| `%tabscore_ram_used%` `%tabscore_ram_alloc%` `%tabscore_ram_max%` | 使用中 / 確保済み / 上限(MB) |

</details>

## 設定

`plugins/TabScore/config.yml` に、データの保存先、保存間隔、Bot の除外、既定の項目、AFK の秒数があります。`lang.yml` は全メッセージ(MiniMessage)です。

| キー | 既定値 | 内容 |
|---|---|---|
| `database.file` | `data.db` | SQLite のファイル。プラグインフォルダからの相対パス |
| `flush-interval-seconds` | `30` | 保存の間隔(秒)。退出時と停止時にも保存します |
| `daily-retention-days` | `400` | 日別の値を残す日数 |
| `default-metric` | `mine` | `/tabscore change` を使うまで出す項目 |
| `afk-seconds` | `120` | 動き・視点・操作がこの秒数ないと AFK 扱い |
| `timezone` | 空 | 日付の境界に使うタイムゾーン。空ならサーバーの既定 |
| `bots.exclude` | `true` | 偽プレイヤーを記録しない |
| `bots.name-regex` / `bots.names` / `bots.uuids` | 空 | 除外する Bot の名前(正規表現または一覧)と UUID |
| `bots.exclude-no-address` | `true` | 接続アドレスのないプレイヤーも除外する |
| `metrics.interval-seconds` | `1` | サーバー負荷の集計間隔 |
| `metrics.tile-entities` | `true` | 全ロード済みチャンクを走査してタイルエンティティを数える。チャンクが多いサーバーでは `false` にできます |

## 注意

- 移動距離と稼働時間は毎秒サンプリングします。テレポートは距離に入れません。
- 作成(`craft`)の Shift クリックは、材料の最小スタック数で概算します。
- DB にはスキーマのバージョンがあります。古い DB は起動時に順に移行し、新しいバージョンの DB は読み込みを拒否します。

> [!IMPORTANT]
> `/tabscore import` は、ゲーム本体の統計 JSON(`<ワールド>/players/stats/<uuid>.json`)を別スレッドで読み、オフラインのプレイヤーも含めて全員分を取り込みます。進捗はチャットに出ます。書き込みは「取り込み済み」の印と同じ1トランザクションなので、途中で再起動しても二重に加算されず、やり直せます。
> 結果は近似です。本体に「置いた数」がないため、ブロックの「使用数」を `place` に入れます。本体の死亡数とダメージには種別がないので、`imported` キーに入ります。

## 今後の予定

- 実機でタブリストの表示を確認し、スクリーンショットを足す。
