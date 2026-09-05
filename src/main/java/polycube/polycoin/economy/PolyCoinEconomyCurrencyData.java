package polycube.polycoin.economy;

import com.mojang.serialization.DataResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class PolyCoinEconomyCurrencyData {
    public static final BigInteger DEFAULT_BALANCE = BigInteger.valueOf(100_000L); // 1000.00
    public static final Item DEFAULT_CURRENCY_ICON = Items.NETHER_STAR;
    public static final String DEFAULT_CURRENCY_ID = "polycoin";
    public static final String DEFAULT_CURRENCY_NAME = "polycoins";

    final Map<String, PolyCoinEconomyCurrency> currencies;
    volatile String defaultCurrencyId;

    private final PolyCoinEconomyData data;

    public PolyCoinEconomyCurrencyData(
            PolyCoinEconomyData data,
            Map<String, PolyCoinEconomyCurrency> currencies,
            String defaultCurrencyId
    ) {
        this.data = data;
        this.currencies = new ConcurrentHashMap<>(currencies.size());
        this.defaultCurrencyId = defaultCurrencyId;

        // Validate and copy currencies.
        for (var entry : currencies.entrySet()) {
            var currencyId = entry.getKey();
            PolyCoinEconomyCurrency currency = entry.getValue();

            if (!currencyId.equals(currency.getId())) {
                throw new IllegalStateException("Currency key '" + currencyId + "' does not match currency id '" + currency.id() + "'");
            }
        }

        ensureCurrenciesCreated();
    }

    private void ensureCurrenciesCreated() {
        if (currencies.isEmpty()) {
            var defaultCurrency = new PolyCoinEconomyCurrency(
                    DEFAULT_CURRENCY_ID, DEFAULT_CURRENCY_NAME, DEFAULT_CURRENCY_ICON, DEFAULT_BALANCE
            );
            currencies.put(DEFAULT_CURRENCY_ID, defaultCurrency);
            defaultCurrencyId = DEFAULT_CURRENCY_ID;
            data.setDirty();
        } else {
            var defaultCurrency = currencies.get(defaultCurrencyId);
            if (defaultCurrency == null) {
                defaultCurrency = currencies.get(DEFAULT_CURRENCY_ID);
                if (defaultCurrency == null) {
                    defaultCurrency = currencies.values().iterator().next();
                }
                defaultCurrencyId = defaultCurrency.getId();
                data.setDirty();
            }
        }
    }

    private Map<String, PolyCoinEconomyCurrency> getCurrenciesInternal() {
        ensureCurrenciesCreated();
        return currencies;
    }

    public Map<String, PolyCoinEconomyCurrency> getCurrencies() {
        return Collections.unmodifiableMap(getCurrenciesInternal());
    }

    public DataResult<PolyCoinEconomyCurrency> getCurrency(String currencyId) {
        var currency = getCurrenciesInternal().get(currencyId);
        if (currency == null) return DataResult.error(() -> "Currency not found: " + currencyId);
        return DataResult.success(currency);
    }

    public synchronized DataResult<PolyCoinEconomyCurrency> createCurrency(String currencyId, String name, Item icon, BigInteger defaultBalance) {
        PolyCoinEconomyCurrency currency = new PolyCoinEconomyCurrency(currencyId, name, icon, defaultBalance);
        if (currencies.putIfAbsent(currencyId, currency) != null) return DataResult.error(() -> "Currency already exists: " + currencyId);
        data.setDirty();
        return DataResult.success(currency);
    }

    public synchronized DataResult<PolyCoinEconomyCurrency> updateCurrency(String currencyId, String name, Item icon, BigInteger defaultBalance) {
        var currencyResult = getCurrency(currencyId);
        if (currencyResult.isError()) return currencyResult;
        var currency = currencyResult.getOrThrow();
        if (currency.displayName().equals(name) && currency.iconItem() == icon && currency.defaultBalance().equals(defaultBalance)) {
            return DataResult.success(currency);
        }

        PolyCoinEconomyCurrency updated = new PolyCoinEconomyCurrency(currencyId, name, icon, defaultBalance);
        currencies.put(currencyId, updated);
        data.setDirty();
        return DataResult.success(updated);
    }

    public record CurrencyDeletionResult(PolyCoinEconomyCurrency currency, int deletedAccounts) {}

    public synchronized DataResult<CurrencyDeletionResult> deleteCurrency(String currencyId) {
        var currencyResult = getCurrency(currencyId);
        if (currencyResult.isError()) return currencyResult.map(currency -> new CurrencyDeletionResult(currency, 0));

        if (isDefaultCurrency(currencyId)) return DataResult.error(() -> "Cannot delete the default currency. Set another default first.");

        var accounts = data.getAccountsInternal();

        for (var entry : accounts.entrySet()) {
            var account = entry.getValue().get(data.getDefaultAccountId(entry.getKey(), currencyId));
            if (account != null && account.usesCurrency(currencyId)) return DataResult.error(() -> "Cannot delete currency " + currencyId + " because it is used by the default account of owner " + entry.getKey());
        }

        var currency = currencyResult.getOrThrow();

        int deletedAccounts = 0;
        for (var ownerEntry : accounts.entrySet()) {
            var playerAccounts = ownerEntry.getValue();
            for (var accountEntry : playerAccounts.entrySet()) {
                var account = accountEntry.getValue();
                if (account.usesCurrency(currency.getId())
                        && playerAccounts.remove(accountEntry.getKey(), account)) {
                    account.detach();
                    deletedAccounts++;
                    data.setDirty();
                }
            }
            if (playerAccounts.isEmpty()) {
                accounts.remove(ownerEntry.getKey(), playerAccounts);
                data.setDirty();
            }
        }

        if (!currencies.remove(currencyId, currency)) {
            return DataResult.error(() -> "Currency not found: " + currencyId);
        }

        data.setDirty();
        return DataResult.success(new CurrencyDeletionResult(currency, deletedAccounts));
    }

    public String getDefaultCurrency() {
        ensureCurrenciesCreated();
        return defaultCurrencyId;
    }

    public boolean isDefaultCurrency(String currencyId) {
        return defaultCurrencyId.equals(currencyId);
    }

    public synchronized DataResult<PolyCoinEconomyCurrency> setDefaultCurrency(String currencyId) {
        var currencyResult = getCurrency(currencyId);
        if (currencyResult.isError()) return currencyResult;
        if (!defaultCurrencyId.equals(currencyId)) {
            defaultCurrencyId = currencyId;
            data.setDirty();
        }
        return currencyResult;
    }
}
