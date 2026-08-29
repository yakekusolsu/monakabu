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
        ensurePrimaryThread();
        double rawBalance = economy.getBalance(player);
        return amount.signum() >= 0 && Double.isFinite(rawBalance)
                && BigDecimal.valueOf(rawBalance).compareTo(amount) >= 0;
    }

    public double balance(Player player) { return economy.getBalance(player); }

    public EconomyResponse withdraw(Player player, BigDecimal amount) {
        ensurePrimaryThread();
        double rawAmount = amount.doubleValue();
        double rawBalance = economy.getBalance(player);
        if (amount.signum() < 0 || !Double.isFinite(rawAmount) || !Double.isFinite(rawBalance)
                || BigDecimal.valueOf(rawBalance).compareTo(amount) < 0) {
            return new EconomyResponse(0, rawBalance, EconomyResponse.ResponseType.FAILURE,
                    "Insufficient balance");
        }
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
