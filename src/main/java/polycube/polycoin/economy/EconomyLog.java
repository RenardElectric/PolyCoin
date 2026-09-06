package polycube.polycoin.economy;

import com.google.gson.JsonPrimitive;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;
import polycube.polycoin.PolyCoin;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;

/// Successful state changes only; called under the economy lock, never while decoding snapshots.
final class EconomyLog {
    private EconomyLog() {}

    private static String money(BigInteger value) {
        return new BigDecimal(value, PolyCoinEconomyCurrency.DECIMAL_PLACES).toPlainString();
    }

    // Names can contain newlines/control characters. Keep each event on one unambiguous log line.
    private static String text(@Nullable String value) {
        return value == null ? "null" : new JsonPrimitive(value).toString();
    }

    static void accountCreated(PolyCoinEconomyAccount account) {
        accountLifecycle("account_created", account, "created");
    }

    static void accountDeleted(PolyCoinEconomyAccount account, String reason) {
        accountLifecycle("account_deleted", account, reason);
    }

    private static void accountLifecycle(String event, PolyCoinEconomyAccount account, String reason) {
        PolyCoin.LOGGER.info("[Economy] {} owner={} account={} currency={} name={} icon={} balance={} reason={}",
                event, account.owner(), account.getId(), account.currencyId(), text(account.displayName()),
                BuiltInRegistries.ITEM.getKey(account.iconItem()), money(account.balance()), reason);
    }

    static void accountUpdated(PolyCoinEconomyAccount account, String previousCurrency, String previousName, Item previousIcon) {
        PolyCoin.LOGGER.info("[Economy] account_updated owner={} account={} currency_before={} currency_after={} name_before={} name_after={} icon_before={} icon_after={}",
                account.owner(), account.getId(), previousCurrency, account.currencyId(), text(previousName),
                text(account.displayName()), BuiltInRegistries.ITEM.getKey(previousIcon), BuiltInRegistries.ITEM.getKey(account.iconItem()));
    }

    static void balanceChanged(PolyCoinEconomyAccount account, BigInteger previous, String operation) {
        BigInteger current = account.balance();
        PolyCoin.LOGGER.info("[Economy] balance_changed owner={} account={} currency={} operation={} before={} after={} delta={}",
                account.owner(), account.getId(), account.currencyId(), operation, money(previous), money(current), money(current.subtract(previous)));
    }

    static void transferred(PolyCoinEconomyAccount source, PolyCoinEconomyAccount target, BigInteger amount) {
        PolyCoin.LOGGER.info("[Economy] transfer_completed source_owner={} source_account={} target_owner={} target_account={} currency={} amount={}",
                source.owner(), source.getId(), target.owner(), target.getId(), source.currencyId(), money(amount));
    }

    static void defaultAccountChanged(UUID owner, String currency, @Nullable String previous, @Nullable String current, String reason) {
        PolyCoin.LOGGER.info("[Economy] default_account_changed owner={} currency={} before={} after={} reason={}",
                owner, text(currency), text(previous), text(current), reason);
    }

    static void currencyCreated(PolyCoinEconomyCurrency currency) {
        PolyCoin.LOGGER.info("[Economy] currency_created currency={} name={} icon={} starting_balance={}",
                currency.getId(), text(currency.displayName()), BuiltInRegistries.ITEM.getKey(currency.iconItem()), money(currency.defaultBalance()));
    }

    static void currencyUpdated(PolyCoinEconomyCurrency previous, PolyCoinEconomyCurrency current) {
        PolyCoin.LOGGER.info("[Economy] currency_updated currency={} name_before={} name_after={} icon_before={} icon_after={} starting_balance_before={} starting_balance_after={}",
                current.getId(), text(previous.displayName()), text(current.displayName()),
                BuiltInRegistries.ITEM.getKey(previous.iconItem()), BuiltInRegistries.ITEM.getKey(current.iconItem()),
                money(previous.defaultBalance()), money(current.defaultBalance()));
    }

    static void currencyDeleted(PolyCoinEconomyCurrency currency, int deletedAccounts) {
        PolyCoin.LOGGER.info("[Economy] currency_deleted currency={} name={} icon={} starting_balance={} deleted_accounts={}",
                currency.getId(), text(currency.displayName()), BuiltInRegistries.ITEM.getKey(currency.iconItem()),
                money(currency.defaultBalance()), deletedAccounts);
    }

    static void defaultCurrencyChanged(@Nullable String previous, String current) {
        PolyCoin.LOGGER.info("[Economy] default_currency_changed before={} after={}", text(previous), text(current));
    }
}
