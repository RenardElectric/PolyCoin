package polycube.polycoin.economy;

import com.mojang.authlib.GameProfile;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import eu.pb4.common.economy.api.EconomyCurrency;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;
import polycube.polycoin.PolyCoin;

import java.math.BigInteger;
import java.util.*;

public final class PolyCoinEconomyData extends SavedData {

    public static final Codec<Map<String, PolyCoinEconomyCurrency>> CURRENCIES_CODEC = Codec.unboundedMap(Codec.STRING, PolyCoinEconomyCurrency.CODEC);

    public static final Codec<Map<UUID, Map<String, PolyCoinEconomyAccount>>> ACCOUNTS_CODEC = Codec.unboundedMap(
            UUIDUtil.STRING_CODEC,
            Codec.unboundedMap(Codec.STRING, PolyCoinEconomyAccount.CODEC)
    );

    public static final Codec<PolyCoinEconomyData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            CURRENCIES_CODEC.optionalFieldOf("currencies", Map.of()).forGetter(data -> data.currencies),
            ACCOUNTS_CODEC.optionalFieldOf("accounts", Map.of()).forGetter(data -> data.accounts)
    ).apply(instance, PolyCoinEconomyData::new));

    @SuppressWarnings("DataFlowIssue")
    public static final SavedDataType<PolyCoinEconomyData> TYPE =
            new SavedDataType<>(
                    Identifier.fromNamespaceAndPath(PolyCoin.MOD_ID, "polycoin_economy_provider"),
                    () -> new PolyCoinEconomyData(Map.of(), Map.of()),
                    CODEC,
                    null
            );

    public static final BigInteger DEFAULT_BALANCE = BigInteger.valueOf(100_000L); // 1000.00
    public static final Item MAIN_CURRENCY_ICON = Items.NETHER_STAR;
    public static final Item MAIN_ACCOUNT_ICON = Items.NETHER_STAR;
    public static final Identifier MAIN_CURRENCY_ID = Identifier.fromNamespaceAndPath(PolyCoin.MOD_ID, "main_currency");
    public static final Identifier MAIN_ACCOUNT_ID = Identifier.fromNamespaceAndPath(PolyCoin.MOD_ID, "main_account");
    public static final String MAIN_CURRENCY_KEY = MAIN_CURRENCY_ID.getPath();
    public static final String MAIN_ACCOUNT_KEY = MAIN_ACCOUNT_ID.getPath();

    private final Map<String, PolyCoinEconomyCurrency> currencies;
    private final Map<UUID, Map<String, PolyCoinEconomyAccount>> accounts;

    public PolyCoinEconomyData(Map<String, PolyCoinEconomyCurrency> currencies, Map<UUID, Map<String, PolyCoinEconomyAccount>> accounts) {
        this.currencies = new HashMap<>(currencies.size() + 1);
        this.accounts = new HashMap<>(accounts.size());

        // Validate and copy currencies.
        for (var entry : currencies.entrySet()) {
            String key = entry.getKey();
            PolyCoinEconomyCurrency currency = entry.getValue();

            if (!key.equals(currency.id().getPath())) {
                throw new IllegalStateException("Currency key '" + key + "' does not match currency id '" + currency.id() + "'");
            }

            if (this.currencies.putIfAbsent(key, currency) != null) {
                throw new IllegalStateException("Duplicate currency id: " + currency.id());
            }
        }

        // Main currency must exist before accounts are attached.
        this.currencies.computeIfAbsent(
                MAIN_CURRENCY_KEY,
                _ -> {
                    setDirty();
                    return new PolyCoinEconomyCurrency(
                            MAIN_CURRENCY_ID, "polycoins",
                            MAIN_CURRENCY_ICON, DEFAULT_BALANCE
                    );
                }
        );

        // Validate, deep-copy and attach accounts.
        for (var ownerEntry : accounts.entrySet()) {
            UUID owner = ownerEntry.getKey();
            Map<String, PolyCoinEconomyAccount> storedAccounts = ownerEntry.getValue();
            Map<String, PolyCoinEconomyAccount> playerAccounts = new HashMap<>(storedAccounts.size());

            for (var entry : storedAccounts.entrySet()) {
                String key = entry.getKey();
                PolyCoinEconomyAccount account = entry.getValue();

                if (!owner.equals(account.owner())) {
                    throw new IllegalStateException("Account " + account.id() + " belongs to " + account.owner() + " but is stored under " + owner);
                }

                if (!key.equals(account.id().getPath())) {
                    throw new IllegalStateException("Account key '" + key + "' does not match account id '" + account.id() + "'");
                }

                account.attach(this);

                if (playerAccounts.putIfAbsent(key, account) != null) {
                    throw new IllegalStateException("Duplicate account id " + account.id() + " for owner " + owner);
                }
            }

            this.accounts.put(owner, playerAccounts);
        }
    }

    public Map<String, PolyCoinEconomyAccount> getAccounts(GameProfile profile) {
        // Listing a player's accounts should include their main account.
        getMainAccount(profile.id());
        return Collections.unmodifiableMap(accounts.get(profile.id()));
    }

    public @Nullable PolyCoinEconomyAccount getAccount(GameProfile profile, String accountId) {
        // The default account is created lazily.
        if (MAIN_ACCOUNT_KEY.equals(accountId)) return getMainAccount(profile.id());

        Map<String, PolyCoinEconomyAccount> playerAccounts = accounts.get(profile.id());
        if (playerAccounts == null) return null;

        return playerAccounts.get(accountId);
    }

    public Map<String, PolyCoinEconomyCurrency> getCurrencies() {
        return Collections.unmodifiableMap(currencies);
    }

    public @Nullable PolyCoinEconomyCurrency getCurrency(String currencyId) {
        return currencies.get(currencyId);
    }

    public @Nullable PolyCoinEconomyCurrency getCurrency(Identifier currencyId) {
        if (!PolyCoin.MOD_ID.equals(currencyId.getNamespace())) return null;
        return getCurrency(currencyId.getPath());
    }

    public @Nullable String defaultAccount(GameProfile profile, EconomyCurrency currency) {
        PolyCoinEconomyCurrency canonicalCurrency = getCurrency(currency.id());
        if (canonicalCurrency == null) return null;

        if (canonicalCurrency == getMainCurrency()) return MAIN_ACCOUNT_KEY;

        Map<String, PolyCoinEconomyAccount> playerAccounts = accounts.get(profile.id());
        if (playerAccounts == null) return null;

        String bestAccountId = null;
        for (PolyCoinEconomyAccount account : playerAccounts.values()) {
            if (!account.usesCurrency(canonicalCurrency.id())) continue;
            String id = account.id().getPath();
            if (bestAccountId == null || id.compareTo(bestAccountId) < 0) {
                bestAccountId = id;
            }
        }

        return bestAccountId;
    }

    public PolyCoinEconomyAccount getMainAccount(UUID owner) {
        Map<String, PolyCoinEconomyAccount> playerAccounts = accounts.computeIfAbsent(owner, _ -> new HashMap<>());

        PolyCoinEconomyAccount existing = playerAccounts.get(MAIN_ACCOUNT_KEY);

        if (existing != null) {
            if (!existing.usesCurrency(MAIN_CURRENCY_ID)) {
                throw new IllegalStateException("Main account for " + owner + " uses unexpected currency " + existing.currencyId());
            }
            return existing;
        }

        PolyCoinEconomyCurrency currency = getMainCurrency();

        PolyCoinEconomyAccount account = new PolyCoinEconomyAccount(
                MAIN_ACCOUNT_ID, currency.id(), currency.defaultBalance(),
                owner, "Main Account", MAIN_ACCOUNT_ICON
        );

        account.attach(this);
        playerAccounts.put(MAIN_ACCOUNT_KEY, account);
        setDirty();

        return account;
    }

    public PolyCoinEconomyCurrency getMainCurrency() {
        PolyCoinEconomyCurrency currency = currencies.get(MAIN_CURRENCY_KEY);
        if (currency == null) {
            throw new IllegalStateException("PolyCoin main currency is missing");
        }
        return currency;
    }
}