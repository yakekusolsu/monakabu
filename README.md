# MonaKabu

MonaKabu は、Minecraft サーバー内通貨で架空企業の株を売買する Paper プラグインです。2週間を1シーズンとし、隔週日曜日の指定時刻に市場を閉じ、全保有株を最終株価で自動決済します。株価・保有株・取引・イベント・シーズン状態はDBへ保存され、予定時刻にサーバーが停止していても次回起動時に決済を再開します。

通貨の表示単位は `MONA` です。

## 要件

- Paper 1.21.11
- Java 21
- Vault と Vault 対応経済プラグイン（Jecon 等）
- 任意: PlaceholderAPI
- 任意: LuckPerms（通常のBukkit権限だけでも動作します）
- SQLite（標準）または MySQL 8 / MariaDB

NMS は使用していません。メッセージとGUI名は Adventure MiniMessage 形式です。

## インストール

1. `build/libs/MonaKabu-1.2.0.jar` を `plugins/` へ配置します。
2. Vault と経済プラグインを導入します。
3. Paper を起動し、`plugins/MonaKabu/` に設定ファイルを生成します。
4. `config.yml`、`stocks.yml`、必要に応じて `events.yml` と `gui.yml` を編集します。
5. `/monakabu reload` で通常設定を再読込できます。DB接続設定を変更した場合はサーバーを再起動してください。

## ビルド

```bash
./gradlew build
```

Windows:

```powershell
.\gradlew.bat build
```

Gradle toolchain が Java 21 を自動取得します。成果物は `build/libs/MonaKabu-1.2.0.jar` です。

## シーズンと隔週日曜日決済

```yaml
season:
  enabled: true
  duration-days: 14
  end-day: SUNDAY
  anchor-date: '2026-08-23'
  settlement-time: '21:00'
  auto-start-next-season: true
  notifications: [24h, 12h, 6h, 1h, 30m, 10m, 5m, 1m]
```

`anchor-date` の指定時刻を Season 1 の基準開始点として、14日ごとの境界を計算します。例では 2026-08-23 21:00（Asia/Tokyo）から開始し、2026-09-06 21:00 に終了します。`anchor-date + duration-days` の曜日が `end-day` と一致しない設定は、誤決済防止のため起動時に拒否されます。

市場状態は `OPEN → CLOSING → SETTLEMENT → CLOSED → OPENING → OPEN` としてDBに保存されます。現在の実装では通常の時刻到達時に `OPEN` から原子的に `SETTLEMENT` を取得し、管理操作や将来の段階的クローズ用に `CLOSING` も予約されています。

### 再起動・二重処理対策

- `seasons.ends_at` がスケジューラとは別に永続化されます。
- 起動直後と定期監査で、期限超過した `OPEN` / `SETTLEMENT` シーズンを検出します。
- 決済キー `SEASON-<番号>-FINAL` とシーズン状態の条件付き更新で、一つの処理だけが決済を取得します。
- 決済取引IDは `SETTLE-S<番号>-<UUID>-<銘柄>` で決定的に生成され、DBの主キーで重複を拒否します。
- 保有株のゼロ化、決済取引、未受取金作成は同じDBトランザクションです。
- 決済は設定件数ごとのバッチ処理で、途中停止しても残りの `shares > 0` から再開します。
- 通知済みタイミングも `(season_id, threshold_seconds)` の一意キーで保存します。

## Vault支払の安全性

売却・決済代金は最初に `pending_payments` へ一意な支払IDで記録し、次に `PENDING → PROCESSING` を条件付き取得してからVaultへ入金します。正常入金後に `PAID` へ変更されます。

Vault API はDBとの分散トランザクションや照会可能な外部取引IDを提供しません。そのため、プロセスがVault入金の直後かつ `PAID` 保存前という極小区間で停止した場合、MonaKabuは重複入金を避けるため、その支払を `PROCESSING` のまま自動再試行しません。管理者が経済ログと照合する安全側の設計です。明確に失敗した入金だけが `PENDING` へ戻ります。

購入は `PREPARED → ECONOMY_APPLIED → COMPLETED` のジャーナルです。再起動時に `ECONOMY_APPLIED` は保有株へ回復し、市場終了後なら一意な返金支払へ変換します。結果が不明な `PREPARED` は再引落しせず `REVIEW_REQUIRED` にします。

## 株の作成

`stocks.yml` の `stocks` 配下へ追加します。IDは小文字英数字、`_`、`-` が使用できます。

```yaml
stocks:
  mona_mining:
    display-name: '<gold>モナカ鉱業'
    symbol: MMI
    initial-price: 1000
    min-price: 100
    max-price: 10000
    volatility: 0.05
    drift: 0.001
    icon:
      material: DIAMOND_PICKAXE
      custom-model-data: 0
```

- `initial-price`: 新シーズン開始価格
- `min-price` / `max-price`: 絶対価格範囲
- `volatility`: 日次変動の強さ。`0.05` は低変動、`0.10` 前後は中程度、`0.16` 以上は高リスクです。
- `market.volatility-multiplier`: 全銘柄の通常変動へ掛ける倍率です。デフォルトの`1.5`は従来基準の約1.5倍、`1.0`で従来相当になります。
- `drift`: 長期的な方向性。更新間隔に応じて期間調整されます。

設定から削除した銘柄のDBレコードは、過去取引と未決済保有株を守るため自動削除しません。公開運用ではシーズン境界で銘柄を追加・削除してください。

## 株価生成

更新間隔は `market.price-update-interval`（標準5分）です。価格エンジンは次を合成します。

- 銘柄ごとの volatility と drift
- `BULL` / `NORMAL` / `BEAR` トレンド
- 正規分布のランダムショック
- 初期価格への弱い平均回帰
- 期間配分した市場イベント効果
- 1更新の `max-change-per-update`
- 銘柄の最低・最高価格

イベントの `modifier: 1.20` は毎更新20%を無制限に加える値ではなく、イベント期間全体へ対数的に配分されます。これにより設定効果を保ちつつ連続暴騰を防ぎます。

## 市場イベント

`events.yml`:

```yaml
events:
  mining_boom:
    stock: mona_mining
    name: 巨大鉱脈発見
    modifier: 1.20
    duration: 30m
    weight: 10
    message: '<gold>【速報】巨大鉱脈が発見されました！'
```

`events.chance-per-check` と重みでランダム発生します。発生中イベントと終了日時はDBへ保存され、再起動後に復元します。管理者は `/monakabu event <stock> <event>` で発生させられます。

## 手数料、税、上限

```yaml
fees:
  buy: {percent: 1.0}
  sell: {percent: 2.0}
capital-gains-tax:
  enabled: true
  percent: 10.0
limits:
  max-shares-per-stock: 1000
  max-total-investment: 10000000
```

売却税は移動平均取得単価から計算した利益部分だけに適用します。損失売却に税はかかりません。LuckPerms等から `monakabu.limit.shares.5000`、`monakabu.limit.investment.50000000` を付与すると、そのプレイヤーの上限を引き上げられます。

サーキットブレーカー、値幅制限、倒産は `config.yml` で変更できます。倒産は標準で無効です。

## GUI

`/kabu`、`/stock`、`/monakabu` は同じメインGUIを開きます。株一覧、売買、保有株、ランキング、ニュース、取引履歴を利用できます。簡易チャートは保存価格から `▁▂▃▄▅▆▇█` で生成します。

GUI表示中のクリックは上部・下部を含めてキャンセルし、Shift Click、Number Key、Double Click、Collect to Cursor、交換を遮断します。DragもGUI全体でキャンセルします。画面のItemStackは取引の根拠にせず、クリック時に銘柄IDとDB状態を再検証します。

`gui.yml` でメインメニューの Material、Slot、Name、Lore、CustomModelData と売買数量スロットを変更できます。銘柄アイコンは `stocks.yml` から設定します。

## リアルタイムチャートホログラム

設置位置に立ち、次を実行します。

```text
/monakabu setholo
```

全企業を一枚に表示します。単一銘柄だけを表示する場合は `/monakabu setholo mona_mining`、明示的な全銘柄指定は `/monakabu setholo all` です。PaperのTextDisplayを使い、現在価格、変動率、`▁▂▃▄▅▆▇█` チャートを株価更新後に再描画します。設置位置はDBへ保存され、再起動後に復元されます。

```text
/monakabu removeholo nearest
/monakabu removeholo all
```

ホログラム数、表示期間、点数、表示距離は `config.yml` の `hologram` で調整できます。

## コマンド

| コマンド | 内容 |
|---|---|
| `/kabu`, `/stock` | GUI |
| `/monakabu portfolio` | 保有株 |
| `/monakabu stats [player]` | 統計 |
| `/monakabu ranking` | ランキング |
| `/monakabu history` | 取引履歴 |
| `/monakabu admin` | 管理GUI |
| `/monakabu reload` | 設定再読込 |
| `/monakabu price <stock> <price>` | 株価変更 |
| `/monakabu event <stock> <event>` | イベント発生 |
| `/monakabu halt <stock> [duration]` | 取引停止 |
| `/monakabu resume <stock>` | 再開 |
| `/monakabu season info` | シーズン確認 |
| `/monakabu season end` | 強制終了 |
| `/monakabu season start` | 次シーズン開始 |
| `/monakabu portfolio <player>` | 他プレイヤーの保有株 |
| `/monakabu stats <player>` | 他プレイヤーの統計 |
| `/monakabu economy` | 経済統計 |
| `/monakabu setholo [stock\|all]` | チャートホログラム設置 |
| `/monakabu removeholo [nearest\|all]` | ホログラム削除 |

管理GUIのシーズン終了は確認画面を経由します。

## 権限

一般: `monakabu.use`, `monakabu.trade`, `monakabu.buy`, `monakabu.sell`, `monakabu.portfolio`, `monakabu.ranking`, `monakabu.history`

管理: `monakabu.admin`, `monakabu.admin.reload`, `monakabu.admin.price`, `monakabu.admin.event`, `monakabu.admin.halt`, `monakabu.admin.season`, `monakabu.admin.portfolio`

ホログラム管理: `monakabu.admin.hologram`

## PlaceholderAPI

- `%monakabu_season%`
- `%monakabu_season_remaining%`
- `%monakabu_total_profit%`
- `%monakabu_realized_profit%`
- `%monakabu_portfolio_value%`
- `%monakabu_stock_mona_mining_price%`
- `%monakabu_stock_mona_mining_change%`
- `%monakabu_ranking%`

DB値は非同期でキャッシュ更新されるため、最初の1回だけ `0` が返る場合があります。Placeholder要求スレッドでDBを待機しません。

## MySQL / MariaDB

```yaml
database:
  type: mysql # または mariadb
  host: localhost
  port: 3306
  name: monakabu
  username: monakabu
  password: strong-password
  pool-size: 10
```

DBとユーザーを事前作成し、UTF-8を利用してください。SQLiteはWAL、foreign keys、busy timeoutを有効化し、書込競合を避けるためプールを1接続に固定します。MySQL/MariaDBではHikariCPを使用します。

主要テーブル: `players`, `stocks`, `stock_prices`, `portfolios`, `transactions`, `seasons`, `season_results`, `market_events`, `pending_payments`, `season_notifications`, `realtime_outbox`。検索用途の複合INDEXも自動作成します。

## ランキング報酬

`season-rewards.enabled: true` にすると順位ごとの未受取金とコンソールコマンドを適用します。`season_results.rewards_applied` の条件付き更新で同じ順位の報酬を二度作成しません。コマンドの `%player%` は確定時のプレイヤー名へ置換されます。

## Discord Webhook

```yaml
webhook:
  enabled: true
  url: 'https://discord.com/api/webhooks/...'
  username: MonaKabu
  timeout-seconds: 5
  chart-enabled: true
  chart-interval: 5m
  chart-period: 24h
  chart-points: 32
  chart-only-when-open: true
```

市場イベントとシーズン終了に加え、Webhookを有効にすると標準で5分ごとに全企業の価格・変動率・チャートを送信します。Java HttpClientによる完全非同期処理で、失敗は警告ログだけに記録され、市場処理を停止しません。

## Render / Vercel リアルタイムWeb市場

`realtime/backend` はRender向けのNode.js API、`realtime/dashboard` はVercel向けのReact画面です。株価がMonaKabuのDBへコミットされた後に署名付きイベントを送信し、RenderがPostgreSQLへ保存してWebSocketでブラウザーへ配信します。Web公開データにプレイヤーUUID、名前、所持金、保有株、取引内容は含みません。

```yaml
realtime:
  enabled: true
  endpoint: 'https://your-monakabu-api.onrender.com/v1/ingest'
  server-id: monaka-main
  secret-environment-variable: MONAKABU_REALTIME_SECRET
  snapshot-interval: 60s
```

秘密鍵は32文字以上とし、MinecraftサーバーとRenderの `MONAKABU_SHARED_SECRET` に同じ値を設定します。送信前にイベントを `realtime_outbox` へ保存するため、Render停止中やネットワーク障害中も市場処理を止めず、復旧後に順番に再送します。Render側では `(server_id, event_id)` の一意制約で再送を重複反映しません。

完全なデプロイ手順、環境変数、ローカル実行方法は [`realtime/README.md`](realtime/README.md) を参照してください。

## 外部API

```java
MonaKabuAPI api = MonaKabu.getAPI();
api.getStock("mona_mining");
api.getStockPrice("mona_mining");
api.getPortfolio(playerUuid).thenAccept(portfolio -> { /* 非同期 */ });
api.getCurrentSeason();
api.isMarketOpen();
```

イベント: `StockBuyEvent`, `StockSellEvent`, `StockPriceChangeEvent`, `MarketEventStartEvent`, `MarketEventEndEvent`, `SeasonStartEvent`, `SeasonEndEvent`, `PortfolioSettlementEvent`。売買イベントは `Cancellable` です。DBを使うAPIは `CompletableFuture` を返します。

## 運用とトラブルシューティング

- 起動時に「Vault economy provider was not found」: VaultだけでなくJecon等の経済プロバイダを導入してください。
- シーズン設定で起動しない: `anchor-date + duration-days` が `end-day` になるか確認してください。
- MySQL接続失敗: ホスト、DB名、権限、ファイアウォールを確認してください。
- `REVIEW_REQUIRED`: Vault処理境界でサーバープロセスが停止した安全保留です。DBの取引・支払IDと経済プラグインのログを照合してください。自動再試行で重複入出金させないための状態です。
- 詳細価格履歴: `market.history-retention-detailed-days` より古いデータは定期削除対象です。長期集約を追加する場合も `stock_prices(season_id, stock_id, recorded_at)` を基準にしてください。
- Web画面が更新されない: `realtime.enabled`、Renderの `/health`、両側の共有秘密鍵、Renderの `ALLOWED_ORIGINS`、`realtime_outbox.last_error` を確認してください。
- バックアップ: SQLiteではサーバー停止中に `monakabu.db` を保存してください。MySQLでは整合性のあるスナップショットを使用してください。

公開前にステージングサーバーで、利用中のVault経済プロバイダがオフラインプレイヤーへの入金を正しく扱うこと、権限、シーズン時刻、報酬コマンドを確認してください。
