package polycube.polycoin.economy;

import com.mojang.serialization.DataResult;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import polycube.polycoin.PolyCoin;

import java.math.BigInteger;
import java.util.*;

/// Internal currency state. All access is serialized by the owning economy's monitor.
public final class PolyCoinEconomyCurrencyData {
    public static final BigInteger DEFAULT_BALANCE = BigInteger.valueOf(1000_00L);
    public static final Item DEFAULT_CURRENCY_ICON = Items.SUNFLOWER;
    public static final String DEFAULT_CURRENCY_ID = "polycoin";
    public static final String DEFAULT_CURRENCY_NAME = "PolyCoin";
    public static final String DEFAULT_CURRENCY_DENOMINATION = "℗";
    public final static ItemStackTemplate DEFAULT_CURRENCY_ICON_TEMPLATE = new ItemStackTemplate(
            DEFAULT_CURRENCY_ICON,
            DataComponentPatch.builder()
                    .set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath(PolyCoin.MOD_ID, "default_currency"))
                    .set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
                    .build()
    );

    final Map<String, PolyCoinEconomyCurrency> currencies = new TreeMap<>();
    String defaultCurrencyId;
    long revision;

    private final PolyCoinEconomyData data;

    PolyCoinEconomyCurrencyData(
            PolyCoinEconomyData data,
            Map<String, PolyCoinEconomyCurrency> currencies,
            String defaultCurrencyId
    ) {
        this.data = data;
        this.currencies.putAll(currencies);
        this.defaultCurrencyId = defaultCurrencyId;
    }

    DataResult<PolyCoinEconomyCurrency> getCurrency(String currencyId) {
        if (!EconomyValidation.validId(currencyId)) return DataResult.error(() -> "Invalid currency ID: " + currencyId);
        var currency = currencies.get(currencyId);
        return currency == null ? DataResult.error(() -> "Currency not found: " + currencyId) : DataResult.success(currency);
    }

    DataResult<PolyCoinEconomyCurrency> createCurrency(String currencyId, String name, String denomination, Item icon, BigInteger defaultBalance) {
        return PolyCoinEconomyCurrency.create(currencyId, name, denomination, icon, defaultBalance).flatMap(currency -> {
            if (currencies.containsKey(currencyId)) return DataResult.error(() -> "Currency already exists: " + currencyId);
            currencies.put(currencyId, currency);
            revision++;
            EconomyLog.currencyCreated(currency);
            data.accountData.ensureAllAccountsCreated();
            data.setDirty();
            return DataResult.success(currency);
        });
    }

    DataResult<PolyCoinEconomyCurrency> updateCurrency(String currencyId, String name, String denomination, Item icon, BigInteger defaultBalance) {
        return getCurrency(currencyId).flatMap(currency -> PolyCoinEconomyCurrency.create(currencyId, name, denomination, icon, defaultBalance).map(updated -> {
            if (currency.displayName().equals(name) && currency.denomination().equals(denomination)
                    && currency.iconItem() == icon && currency.defaultBalance().equals(defaultBalance)) {
                return currency;
            }
            currencies.put(currencyId, updated);
            data.setDirty();
            EconomyLog.currencyUpdated(currency, updated);
            return updated;
        }));
    }

    public record CurrencyDeletionResult(PolyCoinEconomyCurrency currency, int deletedAccounts) {}

    DataResult<PolyCoinEconomyCurrency> checkCurrencyDeletion(String currencyId) {
        return getCurrency(currencyId).flatMap(currency -> {
            if (defaultCurrencyId.equals(currencyId)) {
                return DataResult.error(() -> "Cannot delete the default currency. Set another default first.");
            }
            for (var entry : data.accountData.defaultAccountIds.entrySet()) {
                if (entry.getValue().containsKey(currencyId)) {
                    return DataResult.error(() -> "Cannot delete currency " + currencyId + " because it has a default account for owner " + entry.getKey());
                }
            }
            return DataResult.success(currency);
        });
    }

    DataResult<CurrencyDeletionResult> deleteCurrency(String currencyId) {
        return checkCurrencyDeletion(currencyId).map(currency -> {
            int deleted = data.accountData.removeCurrency(currencyId);
            currencies.remove(currencyId);
            revision++;
            data.setDirty();
            EconomyLog.currencyDeleted(currency, deleted);
            return new CurrencyDeletionResult(currency, deleted);
        });
    }

    DataResult<PolyCoinEconomyCurrency> setDefaultCurrency(String currencyId) {
        return getCurrency(currencyId).map(currency -> {
            if (!defaultCurrencyId.equals(currencyId)) {
                var previous = defaultCurrencyId;
                defaultCurrencyId = currencyId;
                data.setDirty();
                EconomyLog.defaultCurrencyChanged(previous, currencyId);
            }
            return currency;
        });
    }
}
