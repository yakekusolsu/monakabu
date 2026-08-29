package jp.monakaserver.monakabu.economy;

import jp.monakaserver.monakabu.database.DatabaseManager;
import jp.monakaserver.monakabu.database.repository.PaymentRepository;
import jp.monakaserver.monakabu.message.MessageService;
import jp.monakaserver.monakabu.util.MainThread;
import jp.monakaserver.monakabu.util.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaymentService {
    private final JavaPlugin plugin;
    private final DatabaseManager database;
    private final PaymentRepository repository;
    private final EconomyService economy;
    private final MessageService messages;

    public PaymentService(JavaPlugin plugin, DatabaseManager database, PaymentRepository repository, EconomyService economy, MessageService messages) {
        this.plugin = plugin; this.database = database; this.repository = repository; this.economy = economy; this.messages = messages;
    }

    public CompletableFuture<BigDecimal> payPending(UUID uuid) {
        return database.transaction(connection -> repository.claimForPlayer(connection, uuid, 100))
                .thenCompose(this::payClaims);
    }

    private CompletableFuture<BigDecimal> payClaims(List<PaymentRepository.Payment> payments) {
        CompletableFuture<BigDecimal> chain = CompletableFuture.completedFuture(Money.ZERO);
        for (PaymentRepository.Payment payment : payments) {
            chain = chain.thenCompose(total -> payOne(payment).thenApply(paid -> total.add(paid)));
        }
        return chain.thenApply(Money::normalize);
    }

    private CompletableFuture<BigDecimal> payOne(PaymentRepository.Payment payment) {
        return MainThread.call(plugin, () -> {
            if (payment.amount().signum() == 0) return new PayAttempt(true, false, "zero payment");
            OfflinePlayer player = Bukkit.getOfflinePlayer(payment.uuid());
            try {
                EconomyResponse response = economy.deposit(player, payment.amount());
                return new PayAttempt(response.transactionSuccess(), false, response.errorMessage);
            } catch (Throwable throwable) {
                plugin.getLogger().log(Level.SEVERE, "Vault payment result is ambiguous; automatic retry disabled: " + payment.id(), throwable);
                return new PayAttempt(false, true, throwable.toString());
            }
        }).thenCompose(attempt -> {
            if (attempt.success()) {
                return database.transaction(connection -> { repository.paid(connection, payment.id()); return payment.amount(); });
            }
            if (attempt.ambiguous()) {
                return database.transaction(connection -> { repository.reviewRequired(connection, payment.id(), attempt.message()); return Money.ZERO; });
            }
            return database.transaction(connection -> { repository.release(connection, payment.id(), attempt.message()); return Money.ZERO; });
        });
    }

    public void payAndNotify(Player player) {
        payPending(player.getUniqueId()).thenAccept(total -> {
            if (total.signum() > 0) MainThread.run(plugin, () -> messages.send(player, "pending-paid", Map.of("amount", Money.format(total))));
        }).exceptionally(error -> { plugin.getLogger().log(Level.SEVERE, "Pending payment failed for " + player.getUniqueId(), error); return null; });
    }

    private record PayAttempt(boolean success, boolean ambiguous, String message) {}
}
