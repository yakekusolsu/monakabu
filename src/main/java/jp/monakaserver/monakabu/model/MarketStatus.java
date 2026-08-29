package jp.monakaserver.monakabu.model;

public enum MarketStatus {
    OPEN,
    CLOSING,
    SETTLEMENT,
    CLOSED,
    OPENING;

    public boolean allowsTrading() {
        return this == OPEN;
    }
}
