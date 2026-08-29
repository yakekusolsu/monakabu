package jp.monakaserver.monakabu.util;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public final class MainThread {
    private MainThread() {}

    public static <T> CompletableFuture<T> call(Plugin plugin, Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable task = () -> {
            try { future.complete(supplier.get()); } catch (Throwable throwable) { future.completeExceptionally(throwable); }
        };
        if (Bukkit.isPrimaryThread()) task.run(); else Bukkit.getScheduler().runTask(plugin, task);
        return future;
    }

    public static void run(Plugin plugin, Runnable runnable) {
        if (Bukkit.isPrimaryThread()) runnable.run(); else Bukkit.getScheduler().runTask(plugin, runnable);
    }
}
