package jp.monakaserver.monakabu.economy;

import java.math.BigDecimal;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class EconomyService {
    private final Economy economy;

    public EconomyService(JavaPlugin plugin) {
        RegisteredServiceProvider<Economy> registration = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (registration == null || registration.getProvider() == null) throw new IllegalStateException("Vault economy provider was not found");
        economy = registration.getProvider();
        plugin.getLogger().info("Vault economy: " + economy.getName());
    }

    public boolean has(Player player, BigDecimal amount) {
        return economy.has(player, amount.doubleValue());
    }

    public double balance(Player player) { return economy.getBalance(player); }

    public EconomyResponse withdraw(Player player, BigDecimal amount) {
        ensurePrimaryThread();
        return economy.withdrawPlayer(player, amount.doubleValue());
    }

    public EconomyResponse deposit(OfflinePlayer player, BigDecimal amount) {
        ensurePrimaryThread();
        return economy.depositPlayer(player, amount.doubleValue());
    }

    public String format(BigDecimal amount) { return economy.format(amount.doubleValue()); }

    private void ensurePrimaryThread() {
        if (!org.bukkit.Bukkit.isPrimaryThread()) throw new IllegalStateException("Vault operation must run on the primary thread");
    }
}
