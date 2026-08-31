# MonaKabu Live

MonaKabuの確定済み市場データをWebへ即時配信する付属サービスです。

RenderではなくKAGOYA VPSへセルフホストする場合は、
[`deploy/kagoya/README.md`](../deploy/kagoya/README.md) のDocker Compose移行手順を使用してください。

```text
Paper / MonaKabu → HTTPS + HMAC → Render API / PostgreSQL → WebSocket → Vercel Dashboard
```

プラグインはMinecraftサーバー側で待受ポートを開きません。Renderへの外向きHTTPS通信だけを使います。価格生成が標準の5分間隔なら、価格は5分ごとに変わり、その確定結果が即時配信されます。スナップショットは標準60秒間隔で状態を照合します。

## 1. 秘密鍵を作る

PowerShell:

```powershell
[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLower()
```

出力を安全な場所に保管します。Git、`config.yml`、Vercelの `VITE_` 変数には保存しないでください。

## 2. RenderへAPIとPostgreSQLを作る

1. このディレクトリを含むMonaKabuリポジトリをGitHub等へpushします。
2. Renderで Blueprint を作成し、リポジトリ直下の `render.yaml` を読み込ませます。
3. Blueprint作成画面で `MONAKABU_SHARED_SECRET` を求められたら、手順1の値を入力します。
4. `MONAKABU_SERVER_ID` はプラグインの `realtime.server-id` と同じ値にします。
5. 最初は `ALLOWED_ORIGINS=http://localhost:5173` とし、Vercel URL確定後に更新します。
6. `https://<Renderドメイン>/health` が `{"status":"ok"...}` を返すことを確認します。

Renderを手動作成する場合の設定:

- Root Directory: `realtime/backend`
- Runtime: Docker
- Health Check Path: `/health`
- PostgreSQLを一つ作成し、その内部接続URLを `DATABASE_URL` に指定
- `DATABASE_SSL=false`（Render内部接続の場合）
- Blueprintは意図しない課金を避けるため初期値を無料プランにしています。動作確認後、常時WebSocket接続を維持する本番運用ではWeb ServiceとPostgreSQLを有料プランへ変更してください。
- Web Serviceは1インスタンスで運用してください。複数インスタンスへ拡張する場合はRedis等の共有Pub/Subを追加してください。

## 3. Vercelへ画面を作る

1. 同じリポジトリをVercelへImportします。
2. Root Directoryを `realtime/dashboard` にします。
3. Framework PresetはViteを選択します。
4. 次の環境変数を設定してデプロイします。

```text
VITE_API_URL=https://<Renderドメイン>
VITE_WS_URL=wss://<Renderドメイン>
VITE_SERVER_ID=monaka-main
```

5. Renderの `ALLOWED_ORIGINS` を、末尾スラッシュなしのVercel本番URLへ変更します。複数指定はカンマで区切ります。

```text
https://your-dashboard.vercel.app,https://stocks.example.jp
```

## 4. Minecraftサーバーを設定する

`plugins/MonaKabu/config.yml`:

```yaml
realtime:
  enabled: true
  endpoint: 'https://<Renderドメイン>/v1/ingest'
  server-id: monaka-main
  secret-environment-variable: MONAKABU_REALTIME_SECRET
  secret: ''
  allow-insecure-http: false
  dispatch-interval: 1s
  snapshot-interval: 60s
  request-timeout: 8s
  batch-size: 25
  outbox-retention-days: 7
  retry:
    initial-backoff: 5s
    maximum-backoff: 5m
    max-attempts: 0
```

Minecraftを起動するプロセスへ、手順1と同じ秘密鍵を環境変数として渡します。

Windows起動バッチの例:

```bat
@echo off
set MONAKABU_REALTIME_SECRET=ここに秘密鍵
java -Xms4G -Xmx4G -jar paper.jar --nogui
```

Linux systemdの例:

```ini
[Service]
Environment="MONAKABU_REALTIME_SECRET=ここに秘密鍵"
```

サーバーを再起動するか `/monakabu reload` を実行し、コンソールの `Realtime publishing enabled` を確認します。既存のサーバープロセスへ後から設定した環境変数は反映されないため、その場合は再起動してください。

Web売買を有効にする場合は同じ `config.yml` に次を追加します。既存設定に項目がない場合も、この値が標準値として自動追加されます。

```yaml
web-trading:
  enabled: true
  link-code-lifetime: 10m
  order-poll-interval: 2s
  max-shares-per-order: 1000
```

プレイヤーはゲーム内で `/monakabu link` を実行し、サイトへ8文字のコードを入力します。コードは10分・1回限りです。連携解除は `/monakabu unlink`、サイト側の現在セッションだけを終了する場合はサイトの「ログアウト」を使用します。

## API

公開読み取り:

- `GET /v1/snapshot?serverId=monaka-main`
- `GET /v1/history?serverId=monaka-main&stockId=mona_mining&period=24h`
- `GET /v1/servers`
- `WS /v1/stream?serverId=monaka-main`

プラグイン専用:

- `POST /v1/ingest`
- `POST /v1/plugin/link-code`
- `POST /v1/plugin/unlink`
- `POST /v1/plugin/orders/claim`
- `POST /v1/plugin/orders/result`
- `X-MonaKabu-Timestamp`: Unix秒
- `X-MonaKabu-Signature`: `sha256=HMAC_SHA256(secret, timestamp + "." + rawBody)`
- `X-MonaKabu-Server`: server ID

タイムスタンプの許容差は標準300秒です。MinecraftホストとRenderの時計をNTPで同期してください。

認証済みWeb売買:

- `POST /v1/auth/exchange`（ワンタイムコード交換）
- `GET /v1/account`
- `POST /v1/account/refresh`
- `POST /v1/orders`
- `POST /v1/auth/logout`

`/v1/account`、`/v1/orders`、`/v1/account/refresh`、`/v1/auth/logout` は `Authorization: Bearer <token>` が必要です。セッショントークンは標準14日で失効します。Render環境変数 `WEB_SESSION_DAYS` で1～90日、`WEB_MAX_SHARES_PER_ORDER` でWeb注文1件の上限を変更できます。

## 障害復旧と重複防止

- 配信イベントは送信前にプラグインDBの `realtime_outbox` へ永続化されます。
- HTTP 2xxを確認してから `delivered_at` を設定します。
- 応答が不明な場合は指数バックオフで再送します。
- Renderは `eventId` の一意制約で同じイベントを一度だけ状態へ適用します。
- WebSocket再接続時は完全スナップショットを取得し、欠落イベントを補正します。
- 署名は受信した生バイト列に対して検証し、古いタイムスタンプを拒否します。
- Web注文は一意な注文UUIDをそのままプラグイン取引IDへ使用し、結果送信が失敗して再取得されても二重処理しません。
- ワンタイムコードは使用時にDBトランザクション内で消費し、同じコードの同時利用を拒否します。

## ローカルビルド

API:

```bash
cd realtime/backend
npm install
npm run typecheck
npm test
npm run build
```

ダッシュボード:

```bash
cd realtime/dashboard
npm install
cp .env.example .env.local
npm run dev
npm run build
```

APIのローカル起動にはPostgreSQLと `.env.example` 記載の環境変数が必要です。プラグインからlocalhostへ試験送信する場合だけ `http://127.0.0.1` を許可しています。本番ではHTTPSを使用してください。
