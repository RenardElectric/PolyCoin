package polycube.polycoin.economy;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;
import polycube.polycoin.PolyCoin;

import java.math.BigInteger;
import java.util.*;

/// Economy interface and sole synchronization monitor for internal stores and attached accounts.
public final class PolyCoinEconomyData extends SavedData {
    public static final Identifier DATA_ID = PolyCoin.id("polycoin_economy_data");
    public static final Codec<Map<String, PolyCoinEconomyCurrency>> CURRENCIES_CODEC = Codec.unboundedMap(EconomyValidation.ID_CODEC, PolyCoinEconomyCurrency.CODEC);
    public static final Codec<Map<String, PolyCoinEconomyAccount>> ACCOUNTS_CODEC = Codec.unboundedMap(EconomyValidation.ID_CODEC, PolyCoinEconomyAccount.CODEC);
    public static final Codec<Map<UUID, Map<String, String>>> DEFAULT_ACCOUNTS_CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.unboundedMap(EconomyValidation.ID_CODEC, EconomyValidation.ID_CODEC));

    private record StoredData(Map<String, PolyCoinEconomyCurrency> currencies, String defaultCurrencyId,
                              Map<String, PolyCoinEconomyAccount> accounts,
                              Map<UUID, Map<String, String>> defaultAccountIds) {
        private static final Codec<StoredData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                CURRENCIES_CODEC.optionalFieldOf("currencies", Map.of()).forGetter(StoredData::currencies),
                EconomyValidation.ID_CODEC.fieldOf("default_currency").forGetter(StoredData::defaultCurrencyId),
                ACCOUNTS_CODEC.optionalFieldOf("accounts", Map.of()).forGetter(StoredData::accounts),
                DEFAULT_ACCOUNTS_CODEC.fieldOf("default_accounts").forGetter(StoredData::defaultAccountIds)
        ).apply(instance, StoredData::new));

        private DataResult<PolyCoinEconomyData> create() {
            return PolyCoinEconomyData.create(currencies, defaultCurrencyId, accounts, defaultAccountIds);
        }
    }

    static final Codec<PolyCoinEconomyData> CODEC = new Codec<>() {
        @Override
        public <T> DataResult<T> encode(PolyCoinEconomyData data, DynamicOps<T> ops, T prefix) {
            synchronized (data) {
                var result = StoredData.CODEC.encode(new StoredData(data.currencyData.currencies, data.currencyData.defaultCurrencyId,
                        data.accountData.accounts, data.accountData.defaultAccountIds), ops, prefix);
                if (result.isSuccess()) data.changedAfterEncoding = false;
                return result;
            }
        }

        @Override
        public <T> DataResult<Pair<PolyCoinEconomyData, T>> decode(DynamicOps<T> ops, T input) {
            var decoded = StoredData.CODEC.decode(ops, input);
            var error = decoded.error();
            // Minecraft accepts partial codec results. Explicitly discard those so corruption
            // resets the entire dataset rather than silently retaining a subset of its entries.
            return error.<DataResult<Pair<PolyCoinEconomyData, T>>>map(pairError -> DataResult.error(pairError::message))
                    .orElseGet(() -> decoded.flatMap(pair -> pair.getFirst().create().map(data -> Pair.of(data, pair.getSecond()))));
        }
    };

    @SuppressWarnings("DataFlowIssue")
    public static final SavedDataType<PolyCoinEconomyData> TYPE = new SavedDataType<>(DATA_ID, PolyCoinEconomyData::new, CODEC, null);

    final PolyCoinEconomyCurrencyData currencyData;
    final PolyCoinEconomyAccountData accountData;
    private @Nullable Boolean changedAfterEncoding;

    public PolyCoinEconomyData() {
        this(
                Map.of(PolyCoinEconomyCurrencyData.DEFAULT_CURRENCY_ID, PolyCoinEconomyCurrency.defaultCurrency()),
                PolyCoinEconomyCurrencyData.DEFAULT_CURRENCY_ID, Map.of(), Map.of()
        );
        setDirty();
        EconomyLog.currencyCreated(currencyData.currencies.get(currencyData.defaultCurrencyId));
        EconomyLog.defaultCurrencyChanged(null, currencyData.defaultCurrencyId);
    }

    private PolyCoinEconomyData(
            Map<String, PolyCoinEconomyCurrency> currencies,
            String defaultCurrencyId,
            Map<String, PolyCoinEconomyAccount> accounts,
            Map<UUID, Map<String, String>> defaultAccountIds
    ) {
        this.currencyData = new PolyCoinEconomyCurrencyData(this, currencies, defaultCurrencyId);
        this.accountData = new PolyCoinEconomyAccountData(this, accounts, defaultAccountIds);
        accountData.ensureAllAccountsCreated();
    }

    /// Validate an entire loaded/imported dataset before constructing or attaching any accounts.
    public static DataResult<PolyCoinEconomyData> create(
            Map<String, PolyCoinEconomyCurrency> currencies,
            String defaultCurrencyId,
            Map<String, PolyCoinEconomyAccount> accounts,
            Map<UUID, Map<String, String>> defaultAccountIds
    ) {
        if (currencies.isEmpty()) return DataResult.error(() -> "Economy must contain at least one currency");
        if (!currencies.containsKey(defaultCurrencyId)) return DataResult.error(() -> "Default currency not found: " + defaultCurrencyId);
        for (var entry : currencies.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().getId())) {
                return DataResult.error(() -> "Currency key does not match ID: " + entry.getKey());
            }
        }

        var owners = new HashSet<UUID>();
        for (var entry : accounts.entrySet()) {
            var account = entry.getValue();
            if (!entry.getKey().equals(account.getId())) {
                return DataResult.error(() -> "Account key does not match: " + entry.getKey());
            }
            if (!currencies.containsKey(account.currencyId())) {
                return DataResult.error(() -> "Unknown account currency: " + account.currencyId());
            }
            if (account.owners().isEmpty()) {
                return DataResult.error(() -> "Account must have at least one owner: " + account.getId());
            }
            owners.addAll(account.owners());
        }

        for (var owner : owners) {
            var selections = defaultAccountIds.get(owner);
            if (selections == null) return DataResult.error(() -> "Default accounts missing for owner " + owner);
            for (var currencyId : selections.keySet()) {
                if (!currencies.containsKey(currencyId)) return DataResult.error(() -> "Unknown default account currency: " + currencyId);
            }
            for (var currencyId : currencies.keySet()) {
                var id = selections.get(currencyId);
                var account = id == null ? null : accounts.get(id);
                if (account == null || !account.usesCurrency(currencyId) || !account.isOwnedBy(owner)) {
                    return DataResult.error(() -> "Invalid default account for owner " + owner + " and currency " + currencyId);
                }
            }
        }
        for (var entry : defaultAccountIds.entrySet()) {
            if (!owners.contains(entry.getKey())) return DataResult.error(() -> "Accounts missing for owner " + entry.getKey());
        }
        return DataResult.success(new PolyCoinEconomyData(currencies, defaultCurrencyId, accounts, defaultAccountIds));
    }

    @Override
    public synchronized boolean isDirty() { return super.isDirty(); }

    @Override
    public synchronized void setDirty(boolean dirty) {
        if (dirty) {
            if (changedAfterEncoding != null) changedAfterEncoding = true;
            super.setDirty(true);
        } else {
            // Vanilla clears dirty after encoding, outside our lock. Preserve intervening API writes.
            super.setDirty(Boolean.TRUE.equals(changedAfterEncoding));
            changedAfterEncoding = null;
        }
    }

    public synchronized Map<String, PolyCoinEconomyAccount> getAccounts(UUID uuid) {
        return accountData.getAccounts(uuid);
    }

    public synchronized Map<String, PolyCoinEconomyAccount> getAccounts(UUID uuid, String currencyId) {
        return accountData.getAccounts(uuid, currencyId);
    }

    synchronized boolean isManagedCurrency(PolyCoinEconomyCurrency currency) {
        return currencyData.currencies.get(currency.getId()) == currency;
    }

    public synchronized DataResult<PolyCoinEconomyCurrency> getAccountCurrency(String accountId) {
        return accountData.getAccount(accountId).flatMap(account -> currencyData.getCurrency(account.currencyId()));
    }

    public synchronized DataResult<List<PolyCoinEconomyAccountData.LeaderboardEntry>> getTopAccounts(String currency, int limit) {
        return accountData.getTopAccounts(currency, limit);
    }

    public synchronized DataResult<PolyCoinEconomyAccount> getAccount(String accountId) {
        return accountData.getAccount(accountId);
    }

    public synchronized DataResult<PolyCoinEconomyAccount> getAccount(UUID uuid, String accountId) {
        return accountData.getAccount(uuid, accountId);
    }

    public synchronized DataResult<PolyCoinEconomyAccount> createAccount(Set<UUID> owners, String id, String name, Item icon, String currency) {
        return accountData.createAccount(owners, id, name, icon, currency);
    }

    public synchronized DataResult<PolyCoinEconomyAccount> updateAccount(String id, String name, Item icon, String currency) {
        return accountData.updateAccount(id, name, icon, currency);
    }

    public synchronized DataResult<PolyCoinEconomyAccount> addAccountOwners(String accountId, Collection<NameAndId> profiles) {
        return accountData.addAccountOwners(accountId, profiles);
    }

    public synchronized DataResult<PolyCoinEconomyAccount> removeAccountOwners(String accountId, Collection<NameAndId> profiles) {
        return accountData.removeAccountOwners(accountId, profiles);
    }

    public synchronized DataResult<PolyCoinEconomyAccount> deleteAccount(String id) {
        return accountData.deleteAccount(id);
    }

    public synchronized DataResult<BigInteger> transfer(String sourceId, String targetId, BigInteger amount) {
        return accountData.transfer(sourceId, targetId, amount);
    }

    public synchronized DataResult<Integer> countAccounts(String currencyId) {
        return currencyData.getCurrency(currencyId).map(_ -> accountData.countAccounts(currencyId));
    }

    public synchronized DataResult<PolyCoinEconomyAccountData.CurrencyStatistics> getCurrencyStatistics(String currencyId) {
        return currencyData.getCurrency(currencyId).map(_ -> accountData.getCurrencyStatistics(currencyId));
    }

    public synchronized @Nullable String defaultAccount(UUID uuid, String currencyId) {
        return getDefaultAccountId(uuid, currencyId);
    }

    public synchronized @Nullable String getDefaultAccountId(UUID uuid, String currencyId) {
        return accountData.getDefaultAccountId(uuid, currencyId);
    }

    public synchronized boolean isDefaultAccount(UUID uuid, String id) {
        return accountData.isDefaultAccount(uuid, id);
    }

    public synchronized boolean isDefaultAccount(String id) {
        return accountData.isDefaultAccount(id);
    }

    public synchronized DataResult<PolyCoinEconomyAccount> setDefaultAccount(UUID uuid, String id) {
        return accountData.setDefaultAccount(uuid, id);
    }

    public synchronized Map<String, PolyCoinEconomyCurrency> getCurrencies() {
        return Collections.unmodifiableMap(new TreeMap<>(currencyData.currencies));
    }

    public synchronized DataResult<PolyCoinEconomyCurrency> getCurrency(String currencyId) {
        return currencyData.getCurrency(currencyId);
    }

    public synchronized DataResult<PolyCoinEconomyCurrency> createCurrency(String id, String name, String denomination, Item icon, BigInteger defaultBalance) {
        return currencyData.createCurrency(id, name, denomination, icon, defaultBalance);
    }

    public synchronized DataResult<PolyCoinEconomyCurrency> updateCurrency(String id, String name, String denomination, Item icon, BigInteger defaultBalance) {
        return currencyData.updateCurrency(id, name, denomination, icon, defaultBalance);
    }

    public synchronized DataResult<PolyCoinEconomyCurrency> checkCurrencyDeletion(String currencyId) {
        return currencyData.checkCurrencyDeletion(currencyId);
    }

    public synchronized DataResult<PolyCoinEconomyCurrencyData.CurrencyDeletionResult> deleteCurrency(String id) {
        return currencyData.deleteCurrency(id);
    }

    public synchronized String getDefaultCurrency() {
        return currencyData.defaultCurrencyId;
    }

    public synchronized boolean isDefaultCurrency(String id) {
        return currencyData.defaultCurrencyId.equals(id);
    }

    public synchronized DataResult<PolyCoinEconomyCurrency> setDefaultCurrency(String id) {
        return currencyData.setDefaultCurrency(id);
    }
}
