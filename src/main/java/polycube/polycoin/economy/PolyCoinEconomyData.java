package polycube.polycoin.EconomyProvider;

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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class PolyCoinEconomyData extends SavedData {

    public static final Codec<Map<String, PolyCoinEconomyCurrency>> CURRENCIES_CODEC =
            Codec.unboundedMap(
                    Codec.STRING,
                    PolyCoinEconomyCurrency.CODEC
            );

    public static final Codec<Map<UUID, Map<String, PolyCoinEconomyAccount>>> ACCOUNTS_CODEC =
            Codec.unboundedMap(
                    UUIDUtil.STRING_CODEC,
                    Codec.unboundedMap(
                            Codec.STRING,
                            PolyCoinEconomyAccount.CODEC
                    )
            );

    public static final Codec<PolyCoinEconomyData> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    CURRENCIES_CODEC
                            .optionalFieldOf("currencies", Map.of())
                            .forGetter(data -> data.currencies),

                    ACCOUNTS_CODEC
                            .optionalFieldOf("accounts", Map.of())
                            .forGetter(data -> data.accounts)
            ).apply(instance, PolyCoinEconomyData::new));

    @SuppressWarnings("DataFlowIssue")
    public static final SavedDataType<PolyCoinEconomyData> TYPE =
            new SavedDataType<>(
                    Identifier.fromNamespaceAndPath(
                            PolyCoin.MOD_ID,
                            "polycoin_economy_provider"
                    ),
                    () -> new PolyCoinEconomyData(Map.of(), Map.of()),
                    CODEC,
                    null
            );

    public static final BigInteger DEFAULT_BALANCE =
            BigInteger.valueOf(100_000L); // 1000.00

    public static final Item MAIN_CURRENCY_ICON = Items.NETHER_STAR;
    public static final Item MAIN_ACCOUNT_ICON = Items.NETHER_STAR;

    public static final Identifier MAIN_CURRENCY_ID =
            Identifier.fromNamespaceAndPath(
                    PolyCoin.MOD_ID,
                    "main_currency"
            );

    public static final Identifier MAIN_ACCOUNT_ID =
            Identifier.fromNamespaceAndPath(
                    PolyCoin.MOD_ID,
                    "main_account"
            );

    public static final String MAIN_CURRENCY_KEY =
            MAIN_CURRENCY_ID.getPath();

    public static final String MAIN_ACCOUNT_KEY =
            MAIN_ACCOUNT_ID.getPath();

    /*
     * Keys are provider-local paths:
     *
     * "main_currency"
     * "usd"
     *
     * NOT:
     *
     * "polycoin:main_currency"
     */
    private final Map<String, PolyCoinEconomyCurrency> currencies;

    /*
     * Outer key = account owner.
     * Inner key = provider-local account path.
     */
    private final Map<UUID, Map<String, PolyCoinEconomyAccount>> accounts;

    public PolyCoinEconomyData(
            Map<String, PolyCoinEconomyCurrency> currencies,
            Map<UUID, Map<String, PolyCoinEconomyAccount>> accounts
    ) {
        Objects.requireNonNull(currencies, "currencies");
        Objects.requireNonNull(accounts, "accounts");

        this.currencies = new HashMap<>();
        this.accounts = new HashMap<>();

        boolean migrated = false;

        /*
         * Normalize currency keys.
         *
         * This also migrates old saves containing:
         *
         * "polycoin:main_currency"
         *
         * into:
         *
         * "main_currency"
         */
        for (var entry : currencies.entrySet()) {
            String serializedKey =
                    Objects.requireNonNull(entry.getKey(), "currency map key");

            PolyCoinEconomyCurrency currency =
                    Objects.requireNonNull(entry.getValue(), "currency");

            String canonicalKey = currency.id().getPath();

            PolyCoinEconomyCurrency previous =
                    this.currencies.putIfAbsent(canonicalKey, currency);

            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate economy currency id: " + currency.id()
                );
            }

            if (!serializedKey.equals(canonicalKey)) {
                migrated = true;
            }
        }

        /*
         * Always guarantee that the main currency exists before
         * accounts are attached, since an account may reference it.
         */
        if (!this.currencies.containsKey(MAIN_CURRENCY_KEY)) {
            this.currencies.put(
                    MAIN_CURRENCY_KEY,
                    new PolyCoinEconomyCurrency(
                            MAIN_CURRENCY_ID,
                            "PolyCoin",
                            MAIN_CURRENCY_ICON,
                            DEFAULT_BALANCE
                    )
            );

            migrated = true;
        }

        /*
         * Deep-copy and normalize account maps.
         */
        for (var ownerEntry : accounts.entrySet()) {
            UUID owner =
                    Objects.requireNonNull(ownerEntry.getKey(), "account owner");

            Map<String, PolyCoinEconomyAccount> serializedAccounts =
                    Objects.requireNonNull(
                            ownerEntry.getValue(),
                            "accounts for " + owner
                    );

            Map<String, PolyCoinEconomyAccount> normalizedAccounts =
                    new HashMap<>();

            for (var accountEntry : serializedAccounts.entrySet()) {
                String serializedKey =
                        Objects.requireNonNull(
                                accountEntry.getKey(),
                                "account map key"
                        );

                PolyCoinEconomyAccount account =
                        Objects.requireNonNull(
                                accountEntry.getValue(),
                                "account"
                        );

                /*
                 * The owner stored inside the account must agree with
                 * the UUID under which the account was persisted.
                 *
                 * Silently accepting this could potentially move money
                 * between owners if the data is corrupted.
                 */
                if (!owner.equals(account.owner())) {
                    throw new IllegalStateException(
                            "Account " + account.id()
                                    + " is stored under owner " + owner
                                    + " but declares owner " + account.owner()
                    );
                }

                String canonicalKey = account.id().getPath();

                PolyCoinEconomyAccount previous =
                        normalizedAccounts.putIfAbsent(
                                canonicalKey,
                                account
                        );

                if (previous != null) {
                    throw new IllegalStateException(
                            "Duplicate economy account id "
                                    + account.id()
                                    + " for owner "
                                    + owner
                    );
                }

                account.attach(this);

                if (!serializedKey.equals(canonicalKey)) {
                    migrated = true;
                }
            }

            this.accounts.put(owner, normalizedAccounts);
        }

        /*
         * Re-save normalized legacy data.
         */
        if (migrated) {
            setDirty();
        }
    }

    public Map<String, PolyCoinEconomyAccount> getAccounts(
            GameProfile profile
    ) {
        Objects.requireNonNull(profile, "profile");

        UUID owner =
                Objects.requireNonNull(profile.id(), "profile id");

        /*
         * Listing a player's accounts should include their main account.
         */
        getMainAccount(owner);

        return Collections.unmodifiableMap(accounts.get(owner));
    }

    public @Nullable PolyCoinEconomyAccount getAccount(
            GameProfile profile,
            String accountId
    ) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(accountId, "accountId");

        UUID owner =
                Objects.requireNonNull(profile.id(), "profile id");

        /*
         * The default account is created lazily.
         */
        if (MAIN_ACCOUNT_KEY.equals(accountId)) {
            return getMainAccount(owner);
        }

        Map<String, PolyCoinEconomyAccount> playerAccounts =
                accounts.get(owner);

        if (playerAccounts == null) {
            return null;
        }

        return playerAccounts.get(accountId);
    }

    public Map<String, PolyCoinEconomyCurrency> getCurrencies() {
        return Collections.unmodifiableMap(currencies);
    }

    public @Nullable PolyCoinEconomyCurrency getCurrency(
            String currencyId
    ) {
        Objects.requireNonNull(currencyId, "currencyId");

        return currencies.get(currencyId);
    }

    public @Nullable PolyCoinEconomyCurrency getCurrency(
            Identifier currencyId
    ) {
        Objects.requireNonNull(currencyId, "currencyId");

        if (!PolyCoin.MOD_ID.equals(currencyId.getNamespace())) {
            return null;
        }

        return getCurrency(currencyId.getPath());
    }

    public @Nullable String defaultAccount(
            GameProfile profile,
            EconomyCurrency currency
    ) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(currency, "currency");

        /*
         * Resolve the canonical PolyCoin currency.
         *
         * This rejects currencies that aren't managed by PolyCoin.
         */
        PolyCoinEconomyCurrency canonicalCurrency =
                getCurrency(currency.id());

        if (canonicalCurrency == null) {
            return null;
        }

        /*
         * Common Economy only needs the provider-local path here,
         * not "polycoin:main_account".
         */
        if (canonicalCurrency == getMainCurrency()) {
            return MAIN_ACCOUNT_KEY;
        }

        Map<String, PolyCoinEconomyAccount> playerAccounts =
                accounts.get(profile.id());

        if (playerAccounts == null) {
            return null;
        }

        /*
         * If there are multiple accounts for this currency,
         * choose deterministically rather than depending on HashMap
         * iteration order.
         */
        String bestAccountId = null;

        for (PolyCoinEconomyAccount account : playerAccounts.values()) {
            if (!account.usesCurrency(canonicalCurrency.id())) {
                continue;
            }

            String id = account.id().getPath();

            if (bestAccountId == null || id.compareTo(bestAccountId) < 0) {
                bestAccountId = id;
            }
        }

        return bestAccountId;
    }

    public PolyCoinEconomyAccount getMainAccount(UUID owner) {
        Objects.requireNonNull(owner, "owner");

        Map<String, PolyCoinEconomyAccount> playerAccounts =
                accounts.get(owner);

        if (playerAccounts == null) {
            playerAccounts = new HashMap<>();
            accounts.put(owner, playerAccounts);
        }

        PolyCoinEconomyAccount existing =
                playerAccounts.get(MAIN_ACCOUNT_KEY);

        if (existing != null) {
            /*
             * Never silently accept a broken main account.
             */
            if (!existing.usesCurrency(MAIN_CURRENCY_ID)) {
                throw new IllegalStateException(
                        "Main account for "
                                + owner
                                + " uses unexpected currency "
                                + existing.currencyId()
                );
            }

            return existing;
        }

        PolyCoinEconomyCurrency currency = getMainCurrency();

        PolyCoinEconomyAccount account =
                new PolyCoinEconomyAccount(
                        MAIN_ACCOUNT_ID,
                        currency.id(),
                        currency.defaultBalance(),
                        owner,
                        "Main Account",
                        MAIN_ACCOUNT_ICON
                );

        account.attach(this);

        playerAccounts.put(MAIN_ACCOUNT_KEY, account);

        setDirty();

        return account;
    }

    public PolyCoinEconomyCurrency getMainCurrency() {
        PolyCoinEconomyCurrency currency =
                currencies.get(MAIN_CURRENCY_KEY);

        if (currency == null) {
            /*
             * Constructor guarantees this invariant.
             */
            throw new IllegalStateException(
                    "PolyCoin main currency is missing"
            );
        }

        return currency;
    }
}