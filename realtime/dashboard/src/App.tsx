import { useEffect, useMemo, useRef, useState } from "react";
import type { KeyboardEvent as ReactKeyboardEvent, PointerEvent as ReactPointerEvent } from "react";
import type { DailyMarketReport, HistoryPoint, MarketNews, MarketState, MonaPriceItemState, MonaPriceState, RealtimeEvent, SeasonState, StockState, WebAccountResponse } from "./types";

const apiUrl = (import.meta.env.VITE_API_URL as string | undefined)?.replace(/\/$/, "") ?? "";
const wsUrl = ((import.meta.env.VITE_WS_URL as string | undefined) ?? apiUrl.replace(/^http/, "ws")).replace(/\/$/, "");
const serverId = (import.meta.env.VITE_SERVER_ID as string | undefined) ?? "monaka-main";
const emptyState: MarketState = { currency: "MONA", marketOpen: false, season: null, stocks: [], activeEvents: [], dailyReport: null, monaPrice: null };
const periods = ["1h", "6h", "24h", "7d"] as const;
const periodLabels: Record<Period, string> = { "1h": "1時間", "6h": "6時間", "24h": "24時間", "7d": "7日" };
type Period = typeof periods[number];
type Connection = "connecting" | "live" | "offline";

export default function App() {
  const pricePage = window.location.pathname.replace(/\/+$/, "") === "/prices";
  const [market, setMarket] = useState<MarketState>(emptyState);
  const [connection, setConnection] = useState<Connection>("connecting");
  const [lastReceived, setLastReceived] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState("");
  const [period, setPeriod] = useState<Period>("24h");
  const [history, setHistory] = useState<HistoryPoint[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const sequence = useRef(0);

  useEffect(() => {
    if (!apiUrl || !wsUrl) return;
    let stopped = false;
    let socket: WebSocket | null = null;
    let retryTimer: number | undefined;
    let retryDelay = 1_000;

    const acceptSnapshot = (message: { state: MarketState; sequence: number; updatedAt?: string | null }) => {
      if (message.sequence >= sequence.current) {
        sequence.current = message.sequence;
        setMarket(message.state);
        if (message.updatedAt) setLastReceived(message.updatedAt);
      }
    };
    const refresh = async () => {
      try {
        const response = await fetch(`${apiUrl}/v1/snapshot?serverId=${encodeURIComponent(serverId)}`, { cache: "no-store" });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        acceptSnapshot(await response.json());
      } catch { if (!stopped) setConnection("offline"); }
    };
    const connect = () => {
      if (stopped) return;
      setConnection("connecting");
      socket = new WebSocket(`${wsUrl}/v1/stream?serverId=${encodeURIComponent(serverId)}`);
      socket.onopen = () => { retryDelay = 1_000; setConnection("live"); };
      socket.onmessage = (message) => {
        const value = JSON.parse(String(message.data)) as {
          kind: "snapshot" | "event"; sequence: number; state?: MarketState; updatedAt?: string | null; event?: RealtimeEvent;
        };
        if (value.kind === "snapshot" && value.state) acceptSnapshot({ state: value.state, sequence: value.sequence, updatedAt: value.updatedAt });
        if (value.kind === "event" && value.event && value.sequence > sequence.current) {
          sequence.current = value.sequence;
          setMarket((current) => applyEvent(current, value.event!));
          setLastReceived(value.event.timestamp);
        }
      };
      socket.onerror = () => socket?.close();
      socket.onclose = () => {
        if (stopped) return;
        setConnection("offline");
        retryTimer = window.setTimeout(connect, retryDelay + Math.random() * 500);
        retryDelay = Math.min(30_000, retryDelay * 2);
      };
    };

    void refresh().then(connect);
    const poll = window.setInterval(() => { if (socket?.readyState !== WebSocket.OPEN) void refresh(); }, 30_000);
    return () => {
      stopped = true;
      window.clearInterval(poll);
      if (retryTimer) window.clearTimeout(retryTimer);
      socket?.close();
    };
  }, []);

  useEffect(() => {
    if (!selectedId && market.stocks[0]) setSelectedId(market.stocks[0].id);
    if (selectedId && !market.stocks.some((stock) => stock.id === selectedId)) setSelectedId(market.stocks[0]?.id ?? "");
  }, [market.stocks, selectedId]);

  const selected = useMemo(() => market.stocks.find((stock) => stock.id === selectedId) ?? null, [market.stocks, selectedId]);
  useEffect(() => {
    if (!apiUrl || !selected) return;
    const controller = new AbortController();
    setHistoryLoading(true);
    fetch(`${apiUrl}/v1/history?serverId=${encodeURIComponent(serverId)}&stockId=${encodeURIComponent(selected.id)}&period=${period}`, { signal: controller.signal })
      .then((response) => { if (!response.ok) throw new Error(`HTTP ${response.status}`); return response.json(); })
      .then((value: { points: HistoryPoint[] }) => setHistory(value.points))
      .catch((error: Error) => { if (error.name !== "AbortError") setHistory([]); })
      .finally(() => setHistoryLoading(false));
    return () => controller.abort();
  }, [period, selected?.id, selected?.updatedAt]);

  if (!apiUrl || !wsUrl) return <ConfigurationError />;
  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand"><span className="brand-mark">{pricePage ? "物" : "株"}</span><div><strong>{pricePage ? "MonaPrice掲示板" : "MonaKabu掲示板"}</strong><small>MONAKA SERVER {pricePage ? "アイテム相場板" : "株価実況板"}</small></div></div>
        <nav className="board-nav" aria-label="サイトメニュー"><a className={!pricePage ? "current" : ""} aria-current={!pricePage ? "page" : undefined} href="/">株式市場</a><a className={pricePage ? "current" : ""} aria-current={pricePage ? "page" : undefined} href="/prices">アイテム相場</a>{pricePage ? <><a href="#items">商品一覧</a><a href="#price-chart">チャート</a></> : <><a href="#market">市場一覧</a><a href="#web-trading">Web取引</a></>}</nav>
        <div className="connection"><span className={`pulse ${connection}`} />{connection === "live" ? "リアルタイム接続中" : connection === "connecting" ? "接続中…" : "再接続中…"}</div>
      </header>

      <main>{pricePage ? <MonaPricePage state={market.monaPrice} /> : <>
        <section className="market-hero">
          <div className="board-intro"><p className="eyebrow">■ 株式市場実況板</p><h1>MonaKabu＠MONAKA SERVER</h1><p className="lead">Minecraft内の株価を実況する掲示板です。値動きは自動更新されます。<br />煽り・買い占め・狼狽売りはほどほどに。</p></div>
          <MarketClock season={market.season} open={market.marketOpen} />
        </section>

        {market.activeEvents.length > 0 && <News events={market.activeEvents} stocks={market.stocks} />}

        {market.dailyReport && <DailyReport report={market.dailyReport} currency={market.currency} />}

        <WebTrading market={market} selected={selected} />

        <div className="thread-title" id="market">銘柄一覧＠現在の株価</div>
        <section className="stock-grid" aria-label="銘柄一覧">
          {market.stocks.map((stock, index) => <StockCard key={stock.id} index={index + 1} stock={stock} currency={market.currency} selected={stock.id === selectedId} onClick={() => setSelectedId(stock.id)} />)}
          {market.stocks.length === 0 && <div className="empty">最初の市場スナップショットを待っています…</div>}
        </section>

        {selected && <section className="chart-panel" id="chart">
          <div className="thread-title">【{selected.symbol}】{plain(selected.displayName)} 株価実況スレ</div>
          <div className="chart-heading">
            <div><p className="post-meta"><span>1</span> 名前：<b>名無しさん＠投資中</b> 投稿日：{dateTime(selected.updatedAt)} ID:{selected.symbol}</p><h2>{plain(selected.displayName)}の現在値を実況します</h2></div>
            <div className="quote"><strong>{money(selected.price)}</strong><span>{market.currency}</span><Change value={selected.changePercent} /></div>
          </div>
          <div className="periods"><span>表示期間：</span>{periods.map((value) => <button className={period === value ? "active" : ""} key={value} onClick={() => setPeriod(value)}>[{periodLabels[value]}]</button>)}</div>
          <PriceChart points={history} positive={selected.changePercent >= 0} loading={historyLoading} currency={market.currency} />
          <div className="metrics">
            <Metric label="本日の高値" value={`${money(selected.dailyHigh)} ${market.currency}`} />
            <Metric label="本日の安値" value={`${money(selected.dailyLow)} ${market.currency}`} />
            <Metric label="トレンド" value={trendLabel(selected.trend)} accent={selected.trend.toLowerCase()} />
            <Metric label="最終更新" value={dateTime(selected.updatedAt)} />
          </div>
        </section>}
      </>}
      </main>

      <footer id="about"><span>{pricePage ? "MonaPrice Item Market" : "MonaKabu Public Market Data"} / MONAKA SERVER</span><span>最終受信：{lastReceived ? dateTime(lastReceived) : "待機中"}</span><a href="#">▲ページ上部へ</a></footer>
    </div>
  );
}

type ItemSort = "name" | "price-high" | "price-low" | "rise" | "fall" | "volume";

function MonaPricePage({ state }: { state: MonaPriceState | null }) {
  const [selectedId, setSelectedId] = useState("");
  const [search, setSearch] = useState("");
  const [category, setCategory] = useState("all");
  const [sort, setSort] = useState<ItemSort>("rise");
  const [page, setPage] = useState(1);
  const [period, setPeriod] = useState<Period>("24h");
  const [history, setHistory] = useState<HistoryPoint[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const pageSize = 30;

  useEffect(() => {
    document.title = "MonaPrice掲示板 - MONAKA SERVERアイテム相場";
    return () => { document.title = "MonaKabu掲示板 - MONAKA SERVER株価実況板"; };
  }, []);

  useEffect(() => {
    if (!state?.items.length) return;
    if (!selectedId || !state.items.some((item) => item.id === selectedId)) setSelectedId(state.items[0]!.id);
  }, [state?.items, selectedId]);

  const categories = useMemo(() => {
    const values = new Map<string, string>();
    for (const item of state?.items ?? []) values.set(item.category, item.categoryName);
    return [...values.entries()].sort((left, right) => left[1].localeCompare(right[1], "ja"));
  }, [state?.items]);

  const filtered = useMemo(() => {
    const needle = search.trim().toLocaleLowerCase("ja");
    const values = (state?.items ?? []).filter((item) => (category === "all" || item.category === category)
      && (!needle || item.id.toLowerCase().includes(needle) || item.displayName.toLocaleLowerCase("ja").includes(needle)));
    return values.sort((left, right) => {
      if (sort === "price-high") return right.price - left.price;
      if (sort === "price-low") return left.price - right.price;
      if (sort === "rise") return right.changePercent - left.changePercent;
      if (sort === "fall") return left.changePercent - right.changePercent;
      if (sort === "volume") return (right.buyVolume + right.sellVolume) - (left.buyVolume + left.sellVolume);
      return left.displayName.localeCompare(right.displayName, "ja");
    });
  }, [state?.items, search, category, sort]);

  const pageCount = Math.max(1, Math.ceil(filtered.length / pageSize));
  const safePage = Math.min(page, pageCount);
  const visible = filtered.slice((safePage - 1) * pageSize, safePage * pageSize);
  const selected = state?.items.find((item) => item.id === selectedId) ?? null;

  useEffect(() => { setPage(1); }, [search, category, sort]);
  useEffect(() => {
    if (!apiUrl || !selected) return;
    const controller = new AbortController();
    setHistoryLoading(true);
    fetch(`${apiUrl}/v1/monaprice/history?serverId=${encodeURIComponent(serverId)}&itemId=${encodeURIComponent(selected.id)}&period=${period}`, { signal: controller.signal })
      .then((response) => { if (!response.ok) throw new Error(`HTTP ${response.status}`); return response.json(); })
      .then((value: { points: HistoryPoint[] }) => setHistory(value.points))
      .catch((error: Error) => { if (error.name !== "AbortError") setHistory([]); })
      .finally(() => setHistoryLoading(false));
    return () => controller.abort();
  }, [period, selected?.id, selected?.updatedAt]);

  if (!state) return <>
    <section className="market-hero"><div className="board-intro"><p className="eyebrow">■ アイテム相場実況板</p><h1>MonaPrice＠MONAKA SERVER</h1><p className="lead">Minecraft内の需要と供給を反映したアイテム価格を公開します。<br />MonaPriceから最初の相場情報が届くまでお待ちください。</p></div></section>
    <div className="empty monaprice-waiting">MonaPrice連携データを待っています…</div>
  </>;

  return <>
    <section className="market-hero monaprice-hero">
      <div className="board-intro"><p className="eyebrow">■ アイテム相場実況板</p><h1>MonaPrice＠MONAKA SERVER</h1><p className="lead">需要・供給と市場イベントで変動するMinecraftアイテムの参考相場です。<br />ショップでの実際の取引条件は各ショップの表示を確認してください。</p></div>
      <div className="market-clock price-index"><div><span className="market-dot open" /><strong>物価指数</strong></div><p>全{state.items.length.toLocaleString("ja-JP")}商品</p><time>{money(state.index.current)} <small>INDEX</small></time><Change value={state.index.changePercent} /><MonaPriceCountdown state={state} /></div>
    </section>

    <section className="price-controls" id="items" aria-label="商品検索と並べ替え">
      <label>商品検索<input type="search" value={search} onChange={(event) => setSearch(event.target.value)} placeholder="例: ダイヤモンド / DIAMOND" /></label>
      <label>カテゴリー<select value={category} onChange={(event) => setCategory(event.target.value)}><option value="all">すべて</option>{categories.map(([id, name]) => <option value={id} key={id}>{name}</option>)}</select></label>
      <label>並べ替え<select value={sort} onChange={(event) => setSort(event.target.value as ItemSort)}><option value="rise">値上がり順</option><option value="fall">値下がり順</option><option value="volume">売買量順</option><option value="price-high">価格が高い順</option><option value="price-low">価格が安い順</option><option value="name">名前順</option></select></label>
      <strong>{filtered.length.toLocaleString("ja-JP")}件</strong>
    </section>

    <section className="item-market-table" aria-label="MonaPrice商品一覧">
      <div className="thread-title">商品相場一覧＠{dateTime(state.capturedAt)}</div>
      <div className="item-table-scroll"><table><thead><tr><th>商品</th><th>市場価格</th><th>購入価格</th><th>売却価格</th><th>前回比</th><th>売買量</th></tr></thead>
        <tbody>{visible.map((item) => <MonaPriceRow key={item.id} item={item} currency={state.currency} selected={item.id === selectedId} onClick={() => { setSelectedId(item.id); window.setTimeout(() => document.getElementById("price-chart")?.scrollIntoView({ behavior: "smooth" }), 0); }} />)}</tbody>
      </table>{visible.length === 0 && <div className="empty">条件に一致する商品がありません</div>}</div>
      <div className="pagination"><button disabled={safePage <= 1} onClick={() => setPage(1)}>[最初]</button><button disabled={safePage <= 1} onClick={() => setPage(safePage - 1)}>[前へ]</button><span>{safePage} / {pageCount}</span><button disabled={safePage >= pageCount} onClick={() => setPage(safePage + 1)}>[次へ]</button><button disabled={safePage >= pageCount} onClick={() => setPage(pageCount)}>[最後]</button></div>
    </section>

    {selected && <section className="chart-panel" id="price-chart">
      <div className="thread-title">【{selected.id}】{selected.displayName} 相場実況スレ</div>
      <div className="chart-heading"><div><p className="post-meta"><span>1</span> 名前：<b>名無しさん＠商人</b> 投稿日：{dateTime(selected.updatedAt)} ID:{selected.id}</p><h2>{selected.displayName}の市場価格</h2></div><div className="quote"><strong>{money(selected.price)}</strong><span>{state.currency}</span><Change value={selected.changePercent} /></div></div>
      <div className="periods"><span>表示期間：</span>{periods.map((value) => <button className={period === value ? "active" : ""} key={value} onClick={() => setPeriod(value)}>[{periodLabels[value]}]</button>)}</div>
      <PriceChart points={history} positive={selected.changePercent >= 0} loading={historyLoading} currency={state.currency} />
      <div className="metrics monaprice-metrics"><Metric label="ショップ購入価格" value={`${money(selected.buyPrice)} ${state.currency}`} /><Metric label="ショップ売却価格" value={`${money(selected.sellPrice)} ${state.currency}`} /><Metric label="期間内高値" value={`${money(selected.highPrice)} ${state.currency}`} /><Metric label="期間内安値" value={`${money(selected.lowPrice)} ${state.currency}`} /><Metric label="買い数量" value={money(selected.buyVolume)} /><Metric label="売り数量" value={money(selected.sellVolume)} /><Metric label="カテゴリー" value={selected.categoryName} /><Metric label="最終更新" value={dateTime(selected.updatedAt)} /></div>
    </section>}
  </>;
}

function MonaPriceRow({ item, currency, selected, onClick }: { item: MonaPriceItemState; currency: string; selected: boolean; onClick: () => void }) {
  return <tr className={selected ? "selected" : ""} onClick={onClick} tabIndex={0} onKeyDown={(event) => { if (event.key === "Enter" || event.key === " ") { event.preventDefault(); onClick(); } }}>
    <th><strong>{item.displayName}</strong><small>{item.id} / {item.categoryName}</small></th><td data-label="市場価格"><b>{money(item.price)}</b> <small>{currency}</small></td><td data-label="購入価格" className="daily-high">{money(item.buyPrice)}</td><td data-label="売却価格" className="daily-low">{money(item.sellPrice)}</td><td data-label="前回比"><Change value={item.changePercent} /></td><td data-label="売買量"><span className="volume-buy">買 {money(item.buyVolume)}</span><span className="volume-sell">売 {money(item.sellVolume)}</span></td>
  </tr>;
}

function MonaPriceCountdown({ state }: { state: MonaPriceState }) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => { const timer = window.setInterval(() => setNow(Date.now()), 1000); return () => window.clearInterval(timer); }, []);
  const expected = Date.parse(state.capturedAt) + state.nextUpdateSeconds * 1000;
  const seconds = Math.max(0, Math.ceil((expected - now) / 1000));
  return <p className="next-update">次回更新まで 約{Math.floor(seconds / 60)}分{seconds % 60}秒</p>;
}

function StockCard({ index, stock, currency, selected, onClick }: { index: number; stock: StockState; currency: string; selected: boolean; onClick: () => void }) {
  return <button className={`stock-card ${selected ? "selected" : ""}`} onClick={onClick}>
    <p className="post-meta"><span>{index}</span> 名前：<b>{plain(stock.displayName)}</b> 投稿日：{dateTime(stock.updatedAt)} ID:{stock.symbol}</p>
    <div className="post-body"><div className="stock-title"><span className="ticker">銘柄コード：{stock.symbol}</span>{stock.halted && <span className="halted">取引停止中</span>}</div>
      <div className="card-price"><strong>{money(stock.price)}</strong><span>{currency}</span></div>
      <Change value={stock.changePercent} /><span className="stock-comment">　{stock.changePercent >= 0 ? "上がってるぞ(ﾟ∀ﾟ)" : "下がってる…(´・ω・｀)"}</span>
      <div className="stock-quick"><span>高値 <b>{money(stock.dailyHigh)}</b></span><span>安値 <b>{money(stock.dailyLow)}</b></span><span>気配 <b>{trendLabel(stock.trend)}</b></span></div>
    </div>
  </button>;
}

function Change({ value }: { value: number }) {
  const positive = value >= 0;
  return <span className={`change ${positive ? "positive" : "negative"}`}>{positive ? "▲" : "▼"} {Math.abs(value).toFixed(2)}%</span>;
}

function PriceChart({ points, positive, loading, currency }: { points: HistoryPoint[]; positive: boolean; loading: boolean; currency: string }) {
  const [hoveredIndex, setHoveredIndex] = useState<number | null>(null);
  if (loading && points.length === 0) return <div className="chart-empty">チャートを読み込み中…</div>;
  if (points.length < 2) return <div className="chart-empty">価格履歴が2件以上になるとチャートを表示します</div>;
  const values = points.map((point) => point.price);
  const minimum = Math.min(...values); const maximum = Math.max(...values); const range = Math.max(1, maximum - minimum);
  const coordinates = points.map((point, index) => {
    const x = (index / (points.length - 1)) * 1000;
    const y = 260 - ((point.price - minimum) / range) * 220;
    return `${x.toFixed(2)},${y.toFixed(2)}`;
  }).join(" ");
  const area = `0,280 ${coordinates} 1000,280`;
  const safeHoveredIndex = hoveredIndex === null ? null : Math.min(hoveredIndex, points.length - 1);
  const hovered = safeHoveredIndex === null ? null : points[safeHoveredIndex]!;
  const hoverX = safeHoveredIndex === null ? 0 : (safeHoveredIndex / (points.length - 1)) * 1000;
  const hoverY = hovered ? 260 - ((hovered.price - minimum) / range) * 220 : 0;
  const selectPointer = (event: ReactPointerEvent<SVGSVGElement>) => {
    const bounds = event.currentTarget.getBoundingClientRect();
    const ratio = Math.max(0, Math.min(1, (event.clientX - bounds.left) / Math.max(1, bounds.width)));
    setHoveredIndex(Math.round(ratio * (points.length - 1)));
  };
  const selectKeyboard = (event: ReactKeyboardEvent<SVGSVGElement>) => {
    if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    if (event.key === "Home") setHoveredIndex(0);
    else if (event.key === "End") setHoveredIndex(points.length - 1);
    else setHoveredIndex((current) => Math.max(0, Math.min(points.length - 1, (current ?? points.length - 1) + (event.key === "ArrowRight" ? 1 : -1))));
  };
  return <div className="chart-wrap">
    <div className="chart-plot">
      <svg viewBox="0 0 1000 300" role="img" aria-label="株価チャート。カーソル、タップ、左右矢印キーで価格を確認できます" preserveAspectRatio="none" tabIndex={0}
        onPointerDown={selectPointer} onPointerMove={selectPointer} onPointerLeave={(event) => { if (event.pointerType === "mouse") setHoveredIndex(null); }} onKeyDown={selectKeyboard} onBlur={() => setHoveredIndex(null)}>
        <defs><linearGradient id="chartFill" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stopColor={positive ? "#42e8a1" : "#ff6577"} stopOpacity=".32"/><stop offset="1" stopColor={positive ? "#42e8a1" : "#ff6577"} stopOpacity="0"/></linearGradient></defs>
        {[60, 120, 180, 240].map((y) => <line key={y} x1="0" x2="1000" y1={y} y2={y} className="grid-line" />)}
        <polygon points={area} fill="url(#chartFill)" />
        <polyline points={coordinates} className={positive ? "chart-up" : "chart-down"} />
        {hovered && <><line x1={hoverX} x2={hoverX} y1="20" y2="275" className="chart-cursor-line"/><circle cx={hoverX} cy={hoverY} r="6" className={positive ? "chart-point-up" : "chart-point-down"}/></>}
      </svg>
      {hovered && <div className={`chart-tooltip ${hoverY < 85 ? "below" : ""} ${hoverX > 820 ? "align-right" : hoverX < 180 ? "align-left" : ""}`} style={{ left: `${hoverX / 10}%`, top: `${hoverY / 3}%` }} role="status">
        <strong>{money(hovered.price)} {currency}</strong><span>{dateTime(hovered.recordedAt)}</span>
      </div>}
    </div>
    <div className="chart-range"><span>{dateTime(points[0]!.recordedAt)}</span><span>{money(maximum)} / {money(minimum)}</span><span>{dateTime(points.at(-1)!.recordedAt)}</span></div>
  </div>;
}

function MarketClock({ season, open }: { season: SeasonState | null; open: boolean }) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => { const timer = window.setInterval(() => setNow(Date.now()), 1000); return () => window.clearInterval(timer); }, []);
  const remaining = season ? Math.max(0, Date.parse(season.endsAt) - now) : 0;
  const days = Math.floor(remaining / 86_400_000); const hours = Math.floor(remaining / 3_600_000) % 24;
  const minutes = Math.floor(remaining / 60_000) % 60; const seconds = Math.floor(remaining / 1000) % 60;
  return <div className="market-clock"><div><span className={`market-dot ${open ? "open" : "closed"}`} /><strong>{open ? "取引中" : statusLabel(season?.status)}</strong></div><p>Season {season?.number ?? "—"} 終了まで</p><time>{days}<small>日</small> {pad(hours)}:{pad(minutes)}:{pad(seconds)}</time></div>;
}

function News({ events, stocks }: { events: MarketNews[]; stocks: StockState[] }) {
  const latest = events.at(-1)!;
  const targetIds = latest.stockIds?.length ? latest.stockIds : [latest.stockId];
  const symbols = targetIds.map((id) => stocks.find((stock) => stock.id === id)?.symbol ?? id);
  const targets = symbols.length > 5 ? `${symbols.slice(0, 5).join(" / ")} / 他${symbols.length - 5}銘柄` : symbols.join(" / ");
  return <section className="news"><span>【市場速報】</span><strong>{latest.name}</strong><p>{plain(latest.message)}</p><small>{targets} / {latest.modifier >= 1 ? "好材料ｷﾀ━━(ﾟ∀ﾟ)━━!!" : "悪材料…"}</small></section>;
}

function DailyReport({ report, currency }: { report: DailyMarketReport; currency: string }) {
  return <section className="daily-report" aria-labelledby="daily-report-title">
    <div className="thread-title" id="daily-report-title">【21:15】本日の値幅まとめ＠{report.reportDate}</div>
    <p className="daily-report-note">本日の高値と安値の差です。更新：{dateTime(report.generatedAt)}</p>
    <div className="daily-report-scroll">
      <table className="daily-report-table">
        <thead><tr><th>銘柄</th><th>現在値</th><th>高値</th><th>安値</th><th>値幅</th></tr></thead>
        <tbody>{report.stocks.map((stock) => <tr key={stock.stockId}>
          <th><strong>{stock.symbol}</strong><small>{plain(stock.displayName)}</small></th>
          <td data-label="現在値">{money(stock.currentPrice)} <small>{currency}</small></td>
          <td data-label="高値" className="daily-high">{money(stock.dailyHigh)}</td>
          <td data-label="安値" className="daily-low">{money(stock.dailyLow)}</td>
          <td data-label="値幅"><strong>{money(stock.range)}</strong> <span>({stock.rangePercent.toFixed(2)}%)</span></td>
        </tr>)}</tbody>
      </table>
    </div>
  </section>;
}

function WebTrading({ market, selected }: { market: MarketState; selected: StockState | null }) {
  const [token, setToken] = useState(() => window.localStorage.getItem("monakabu-web-token") ?? "");
  const [account, setAccount] = useState<WebAccountResponse | null>(null);
  const [code, setCode] = useState("");
  const [shares, setShares] = useState(1);
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState("");

  const loadAccount = async (session = token) => {
    if (!session) return;
    try {
      const response = await fetch(`${apiUrl}/v1/account`, { headers: { Authorization: `Bearer ${session}` }, cache: "no-store" });
      if (response.status === 401) { window.localStorage.removeItem("monakabu-web-token"); setToken(""); setAccount(null); return; }
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      setAccount(await response.json() as WebAccountResponse);
    } catch { setMessage("アカウント情報を取得できませんでした。"); }
  };

  useEffect(() => {
    if (!token) return;
    void loadAccount(token);
    const timer = window.setInterval(() => void loadAccount(token), 3_000);
    return () => window.clearInterval(timer);
  }, [token]);

  const exchange = async () => {
    setLoading(true); setMessage("");
    try {
      const response = await fetch(`${apiUrl}/v1/auth/exchange`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ serverId, code: code.toUpperCase() }) });
      const value = await response.json() as { token?: string; error?: string };
      if (!response.ok || !value.token) throw new Error(value.error ?? "連携に失敗しました");
      window.localStorage.setItem("monakabu-web-token", value.token); setToken(value.token); setCode(""); setMessage("Minecraftアカウントと連携しました。");
    } catch (error) { setMessage(error instanceof Error && error.message.includes("expired") ? "コードが無効・期限切れ・使用済みです。" : "連携できませんでした。コードを確認してください。"); }
    finally { setLoading(false); }
  };

  const order = async (type: "BUY" | "SELL") => {
    if (!selected || !token || !Number.isSafeInteger(shares) || shares < 1) return;
    const verb = type === "BUY" ? "購入" : "売却";
    if (!window.confirm(`${plain(selected.displayName)}を${shares}株${verb}しますか？`)) return;
    setLoading(true); setMessage("");
    try {
      const response = await fetch(`${apiUrl}/v1/orders`, { method: "POST", headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" }, body: JSON.stringify({ requestId: crypto.randomUUID(), type, stockId: selected.id, shares }) });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      setMessage(`${verb}注文を受け付けました。Minecraftサーバーで処理中です。`); await loadAccount(token);
    } catch { setMessage("注文を受け付けられませんでした。市場状態と入力内容を確認してください。"); }
    finally { setLoading(false); }
  };

  const refreshAccount = async () => {
    if (!token) return; setLoading(true);
    try {
      const response = await fetch(`${apiUrl}/v1/account/refresh`, { method: "POST", headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" }, body: JSON.stringify({ requestId: crypto.randomUUID() }) });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      setMessage("残高・保有株の更新を依頼しました。");
    } catch { setMessage("残高更新を依頼できませんでした。"); }
    finally { setLoading(false); }
  };

  const logout = async () => {
    if (token) void fetch(`${apiUrl}/v1/auth/logout`, { method: "POST", headers: { Authorization: `Bearer ${token}` } });
    window.localStorage.removeItem("monakabu-web-token"); setToken(""); setAccount(null); setMessage("サイトからログアウトしました。");
  };

  const holding = selected ? account?.account?.portfolio.find((position) => position.stockId === selected.id)?.shares ?? 0 : 0;
  return <section className="web-trading" id="web-trading">
    <div className="thread-title">【本人認証】Minecraft連携・Web売買</div>
    {!token ? <div className="link-box">
      <div><p className="post-meta"><span>認証方法</span> 名前：<b>MonaKabu運営</b></p><p>Minecraft内で <code>/monakabu link</code> を実行し、表示された8文字のコードを入力してください。</p><small>コードは10分間・1回限りです。他人には共有しないでください。</small></div>
      <div className="link-form"><input value={code} onChange={(event) => setCode(event.target.value.toUpperCase().replace(/[^A-Z2-9]/g, "").slice(0, 8))} placeholder="ABCDEFG2" maxLength={8} autoComplete="one-time-code" aria-label="連携コード"/><button disabled={loading || code.length !== 8} onClick={() => void exchange()}>[連携する]</button></div>
    </div> : <div className="account-box">
      <div className="account-head"><div><span>ログイン中：</span><strong>{account?.identity.playerName ?? "確認中…"}</strong></div><div><button onClick={() => void refreshAccount()} disabled={loading}>[残高更新]</button><button onClick={() => void logout()}>[ログアウト]</button></div></div>
      <div className="account-summary"><div><span>利用可能残高</span><strong>{money(account?.account?.balance ?? 0)} {market.currency}</strong></div><div><span>選択銘柄の保有</span><strong>{selected ? `${holding} 株` : "—"}</strong></div><div><span>情報更新</span><strong>{account?.account ? dateTime(account.account.capturedAt) : "待機中"}</strong></div></div>
      {selected && <div className="order-form"><div><b>{plain(selected.displayName)}</b><span>{money(selected.price)} {market.currency} / 1株（成行・手数料別）</span></div><label>株数 <input type="number" min="1" max="1000" step="1" value={shares} onChange={(event) => setShares(Math.max(1, Math.min(1000, Number(event.target.value) || 1)))} /></label><button className="buy-order" disabled={loading || account?.identity.canBuy === false || !market.marketOpen || selected.halted || selected.bankrupt} onClick={() => void order("BUY")}>買う</button><button className="sell-order" disabled={loading || account?.identity.canSell === false || !market.marketOpen || selected.halted || selected.bankrupt || holding < shares} onClick={() => void order("SELL")}>売る</button></div>}
      {account?.orders.length ? <div className="web-orders"><h3>最近の注文</h3>{account.orders.slice(0, 6).map((item) => <div key={item.orderId}><span>{dateTime(item.createdAt)}</span><b>{item.type === "BUY" ? "購入" : item.type === "SELL" ? "売却" : "更新"} {item.stockId ?? ""} {item.shares || ""}</b><em className={`order-${item.status.toLowerCase()}`}>{orderStatus(item.status, item.result?.reason)}</em></div>)}</div> : null}
    </div>}
    {message && <p className="web-message">{message}</p>}
  </section>;
}

function Metric({ label, value, accent }: { label: string; value: string; accent?: string }) { return <div className="metric"><span>{label}</span><strong className={accent}>{value}</strong></div>; }
function ConfigurationError() { return <div className="configuration-error"><h1>MonaKabu Live</h1><p>VITE_API_URL と VITE_WS_URL をVercelの環境変数に設定してください。</p></div>; }
function applyEvent(current: MarketState, event: RealtimeEvent): MarketState {
  if (event.type === "market.snapshot") {
    const snapshot = event.data as MarketState;
    return { ...snapshot, dailyReport: snapshot.dailyReport ?? current.dailyReport ?? null, monaPrice: snapshot.monaPrice ?? current.monaPrice ?? null };
  }
  if (event.type === "stock.price.changed") { const stock = event.data as StockState; return { ...current, stocks: [...current.stocks.filter((item) => item.id !== stock.id), stock].sort((a, b) => a.id.localeCompare(b.id)) }; }
  if (event.type === "season.started" || event.type === "season.ended") { const season = event.data as SeasonState; return { ...current, season, marketOpen: season.status === "OPEN" }; }
  if (event.type === "market.event.started") { const news = event.data as MarketNews; return { ...current, activeEvents: [...current.activeEvents.filter((item) => item.instanceId !== news.instanceId), news] }; }
  if (event.type === "market.event.ended") { const news = event.data as MarketNews; return { ...current, activeEvents: current.activeEvents.filter((item) => item.instanceId !== news.instanceId) }; }
  if (event.type === "market.daily.report") return { ...current, dailyReport: event.data as DailyMarketReport };
  if (event.type === "monaprice.snapshot") return { ...current, monaPrice: event.data as MonaPriceState };
  return current;
}
function plain(value: string) { return value.replace(/<[^>]+>/g, ""); }
function money(value: number) { return new Intl.NumberFormat("ja-JP", { maximumFractionDigits: 2 }).format(value); }
function dateTime(value: string) { return new Intl.DateTimeFormat("ja-JP", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit" }).format(new Date(value)); }
function pad(value: number) { return value.toString().padStart(2, "0"); }
function trendLabel(value: StockState["trend"]) { return value === "BULL" ? "強気" : value === "BEAR" ? "弱気" : "通常"; }
function statusLabel(value?: SeasonState["status"]) { return ({ OPENING: "開場準備", CLOSING: "終了処理", SETTLEMENT: "決済中", CLOSED: "閉場中" } as Record<string, string>)[value ?? ""] ?? "待機中"; }
function orderStatus(status: string, reason?: string) { return status === "COMPLETED" ? "完了" : status === "FAILED" ? `失敗${reason ? `: ${reasonLabel(reason)}` : ""}` : status === "CANCELLED" ? "取消" : status === "CLAIMED" ? "処理中" : "受付済み"; }
function reasonLabel(reason: string) { return ({ NOT_ENOUGH_MONEY: "残高不足", NOT_ENOUGH_SHARES: "保有株不足", MARKET_CLOSED: "市場閉場", STOCK_HALTED: "取引停止", LIMIT_SHARES: "保有上限", LIMIT_INVESTMENT: "投資上限", BUSY: "別の注文を処理中", WEB_ORDER_LIMIT: "注文上限超過", REVIEW_REQUIRED: "管理者確認中" } as Record<string, string>)[reason] ?? reason; }
