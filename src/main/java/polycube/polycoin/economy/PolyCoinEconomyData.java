package polycube.polycoin.economy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import polycube.polycoin.PolyCoin;

import java.math.BigInteger;
import java.util.*;

public final class PolyCoinEconomyData extends SavedData {

    public static final Codec<Map<String, PolyCoinEconomyCurrency>> CURRENCIES_CODEC = Codec.unboundedMap(Codec.STRING, PolyCoinEconomyCurrency.CODEC);

    public static final Codec<Map<UUID, Map<String, PolyCoinEconomyAccount>>> ACCOUNTS_CODEC = Codec.unboundedMap(
            UUIDUtil.STRING_CODEC,
            Codec.unboundedMap(Codec.STRING, PolyCoinEconomyAccount.CODEC)
    );

    public static final Codec<Map<UUID, Map<String, String>>> DEFAULT_ACCOUNTS_CODEC = Codec.unboundedMap(
            UUIDUtil.STRING_CODEC,
            Codec.unboundedMap(Codec.STRING, Codec.STRING)
    );

    public static final Codec<PolyCoinEconomyData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            CURRENCIES_CODEC.optionalFieldOf("currencies", Map.of()).forGetter(data -> data.currencyData.currencies),
            Codec.STRING.fieldOf("default_currency").forGetter(data -> data.currencyData.defaultCurrencyId),
            ACCOUNTS_CODEC.optionalFieldOf("accounts", Map.of()).forGetter(data -> data.accountData.accounts),
            DEFAULT_ACCOUNTS_CODEC.fieldOf("default_accounts").forGetter(data -> data.accountData.defaultAccountIds)
    ).apply(instance, PolyCoinEconomyData::new));

    @SuppressWarnings("DataFlowIssue")
    public static final SavedDataType<PolyCoinEconomyData> TYPE =
            new SavedDataType<>(
                    Identifier.fromNamespaceAndPath(PolyCoin.MOD_ID, "polycoin_economy_data"),
                    PolyCoinEconomyData::new,
                    CODEC,
                    null
            );

    final PolyCoinEconomyCurrencyData currencyData;
    final PolyCoinEconomyAccountData accountData;

    public PolyCoinEconomyData() {
        this(
                Map.of(
                        PolyCoinEconomyCurrencyData.DEFAULT_CURRENCY_ID,
                        new PolyCoinEconomyCurrency(
                                PolyCoinEconomyCurrencyData.DEFAULT_CURRENCY_ID, PolyCoinEconomyCurrencyData.DEFAULT_CURRENCY_NAME,
                                PolyCoinEconomyCurrencyData.DEFAULT_CURRENCY_ICON, PolyCoinEconomyCurrencyData.DEFAULT_BALANCE
                        )
                ), PolyCoinEconomyCurrencyData.DEFAULT_CURRENCY_ID,
                Map.of(), Map.of()
        );
        setDirty();
    }

    public PolyCoinEconomyData(
            Map<String, PolyCoinEconomyCurrency> currencies,
            String defaultCurrencyId,
            Map<UUID, Map<String, PolyCoinEconomyAccount>> accounts,
            Map<UUID, Map<String, String>> defaultAccountIds
    ) {
        this.currencyData = new PolyCoinEconomyCurrencyData(this, currencies, defaultCurrencyId);
        this.accountData = new PolyCoinEconomyAccountData(this, accounts, defaultAccountIds);
    }

    Map<UUID, Map<String, PolyCoinEconomyAccount>> getAccountsInternal() {
        return accountData.getAccountsInternal();
    }

    public Map<String, PolyCoinEconomyAccount> getAccounts(UUID uuid) {
        return accountData.getAccounts(uuid);
    }

    public List<String> getAccountIds(UUID uuid) {
        return accountData.getAccountIds(uuid);
    }

    public List<String> getAccountIds(UUID uuid, PolyCoinEconomyCurrency currency) {
        return accountData.getAccountIds(uuid, currency.getId());
    }

    public DataResult<PolyCoinEconomyCurrency> getAccountCurrency(UUID uuid, String accountId) {
        return accountData.getAccountCurrency(uuid, accountId);
    }

    public DataResult<List<PolyCoinEconomyAccountData.LeaderboardEntry>> getTopAccounts(String currency, int limit) {
        return accountData.getTopAccounts(currency, limit);
    }

    public synchronized DataResult<PolyCoinEconomyAccount> getAccount(UUID uuid, String accountId) {
        return accountData.getAccount(uuid, accountId);
    }

    public synchronized DataResult<PolyCoinEconomyAccount> createAccount(
            UUID uuid, String id, String name,
            Item icon, String currency
    ) {
        return accountData.createAccount(uuid, id, name, icon, currency);
    }

    public synchronized DataResult<PolyCoinEconomyAccount> updateAccount(
            UUID uuid, String id, String name,
            Item icon, String currency
    ) {
        return accountData.updateAccount(uuid, id, name, icon, currency);
    }

    public synchronized DataResult<PolyCoinEconomyAccount> deleteAccount(UUID uuid, String id) {
        return accountData.deleteAccount(uuid, id);
    }

    public synchronized DataResult<BigInteger> transfer(UUID sourceUuid, String sourceAccountId, UUID targetUuid, String targetAccountId, BigInteger amount) {
        return accountData.transfer(sourceUuid, sourceAccountId, targetUuid, targetAccountId, amount);
    }

    public DataResult<Integer> countAccounts(String currencyId) {
        return accountData.countAccounts(currencyId);
    }

    public String defaultAccount(UUID uuid, String currencyId) {
        return accountData.defaultAccount(uuid, currencyId);
    }

    public String getDefaultAccountId(UUID uuid, String currencyId) {
        return accountData.getDefaultAccountId(uuid, currencyId);
    }

    public boolean isDefaultAccount(UUID uuid, String id) {
        return accountData.isDefaultAccount(uuid, id);
    }

    public synchronized DataResult<PolyCoinEconomyAccount> setDefaultAccount(UUID uuid, String id) {
        return accountData.setDefaultAccount(uuid, id);
    }

    public Map<String, PolyCoinEconomyCurrency> getCurrencies() {
        return currencyData.getCurrencies();
    }

//    public DataResult<PolyCoinEconomyCurrency> getCurrency(String currencyId) {
//        return currencyData.getCurrency(currencyId);
//    }

    public DataResult<PolyCoinEconomyCurrency> getCurrency(String currencyId) {
        return currencyData.getCurrency(currencyId);
    }

    public synchronized DataResult<PolyCoinEconomyCurrency> createCurrency(String id, String name, Item icon, BigInteger defaultBalance) {
        return currencyData.createCurrency(id, name, icon, defaultBalance);
    }

    public synchronized DataResult<PolyCoinEconomyCurrency> updateCurrency(String id, String name, Item icon, BigInteger defaultBalance) {
        return currencyData.updateCurrency(id, name, icon, defaultBalance);
    }

    public synchronized DataResult<PolyCoinEconomyCurrencyData.CurrencyDeletionResult> deleteCurrency(String id) {
        return currencyData.deleteCurrency(id);
    }

    public String getDefaultCurrency() {
        return currencyData.getDefaultCurrency();
    }

    public boolean isDefaultCurrency(String id) {
        return currencyData.defaultCurrencyId.equals(id);
    }

    public synchronized DataResult<PolyCoinEconomyCurrency> setDefaultCurrency(String id) {
        return currencyData.setDefaultCurrency(id);
    }
}
