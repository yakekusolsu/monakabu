package jp.monakaserver.monakabu.trading;

/** Share counts must round-trip exactly through the web API's JSON numbers. */
public final class ShareLimits {
    public static final long MAX_SAFE_SHARES = 9_007_199_254_740_991L;

    private ShareLimits() {}

    /** Zero disables the configurable cap; numeric safety still applies. */
    public static long effective(long configured) {
        if (configured < 0) throw new IllegalArgumentException("Share limit must be zero or positive");
        return configured == 0 ? MAX_SAFE_SHARES : Math.min(configured, MAX_SAFE_SHARES);
    }

    public static long remaining(long configured, long held) {
        if (held < 0 || held > MAX_SAFE_SHARES) throw new IllegalStateException("INVALID_AMOUNT");
        return Math.max(0, effective(configured) - held);
    }

    public static boolean validOrder(long shares, long configured) {
        return shares > 0 && shares <= effective(configured);
    }
}
