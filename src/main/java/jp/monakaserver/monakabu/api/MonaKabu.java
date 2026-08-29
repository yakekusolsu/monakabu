package jp.monakaserver.monakabu.api;

import java.util.Objects;

public final class MonaKabu {
    private static volatile MonaKabuAPI api;
    private MonaKabu(){}
    public static MonaKabuAPI getAPI(){return Objects.requireNonNull(api,"MonaKabu is not enabled");}
    public static void register(MonaKabuAPI value){api=Objects.requireNonNull(value);}
    public static void unregister(){api=null;}
}
