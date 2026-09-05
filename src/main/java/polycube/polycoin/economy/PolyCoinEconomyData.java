package polycube.polycoin.economy;

import com.mojang.authlib.GameProfile;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import eu.pb4.common.economy.api.EconomyCurrency;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;
import polycube.polycoin.PolyCoin;
import polycube.polycoin.util.Helpers;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class PolyCoinEconomyData extends SavedData {

    public record LeaderboardEntry(
            UUID owner,
            Identifier accountId,
            Component accountName,
            BigInteger balance
    ) {}

    private static final Comparator<LeaderboardEntry> LEADERBOARD_ORDER =
            Comparator.comparing(LeaderboardEntry::balance).reversed()
                    .thenComparing(entry -> entry.owner().toString())
                    .thenComparing(entry -> entry.accountId().toString());

    public static final Codec<Map<String, PolyCoinEconomyCurrency>> CURRENCIES_CODEC = Codec.unboundedMap(Codec.STRING, PolyCoinEconomyCurrency.CODEC);

    public static final Codec<Map<UUID, Map<String, PolyCoinEconomyAccount>>> ACCOUNTS_CODEC = Codec.unboundedMap(
            UUIDUtil.STRING_CODEC,
            Codec.unboundedMap(Codec.STRING, PolyCoinEconomyAccount.CODEC)
    );

    public static final Codec<PolyCoinEconomyData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            CURRENCIES_CODEC.optionalFieldOf("currencies", Map.of()).forGetter(data -> data.currencies),
            ACCOUNTS_CODEC.optionalFieldOf("accounts", Map.of()).forGetter(data -> data.accounts),
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.STRING).fieldOf("default_accounts").forGetter(data -> data.defaultAccounts),
            Codec.STRING.fieldOf("default_currency").forGetter(data -> data.defaultCurrencyId)
    ).apply(instance, PolyCoinEconomyData::new));

    @SuppressWarnings("DataFlowIssue")
    public static final SavedDataType<PolyCoinEconomyData> TYPE =
            new SavedDataType<>(
                    Identifier.fromNamespaceAndPath(PolyCoin.MOD_ID, "polycoin_economy_provider"),
                    PolyCoinEconomyData::new,
                    CODEC,
                    null
            );

    public static final BigInteger DEFAULT_BALANCE = BigInteger.valueOf(100_000L); // 1000.00
    public static final Item MAIN_CURRENCY_ICON = Items.NETHER_STAR;
    public static final Item MAIN_ACCOUNT_ICON = Items.NETHER_STAR;
    public static final Identifier MAIN_CURRENCY_ID = Identifier.fromNamespaceAndPath(PolyCoin.MOD_ID, "polycoin");
    public static final Identifier MAIN_ACCOUNT_ID = Identifier.fromNamespaceAndPath(PolyCoin.MOD_ID, "main_account");
    public static final String MAIN_CURRENCY_KEY = MAIN_CURRENCY_ID.getPath();
    public static final String MAIN_ACCOUNT_KEY = MAIN_ACCOUNT_ID.getPath();

    private final Map<String, PolyCoinEconomyCurrency> currencies;
    private final Map<UUID, Map<String, PolyCoinEconomyAccount>> accounts;
    private final Map<UUID, String> defaultAccounts;
    private volatile String defaultCurrencyId;

    public PolyCoinEconomyData() {
        this(Map.of(MAIN_CURRENCY_KEY, new PolyCoinEconomyCurrency(
                MAIN_CURRENCY_ID, "polycoins", MAIN_CURRENCY_ICON, DEFAULT_BALANCE
        )), Map.of(), Map.of(), MAIN_CURRENCY_KEY);
        setDirty();
    }

    public PolyCoinEconomyData(
            Map<String, PolyCoinEconomyCurrency> currencies,
            Map<UUID, Map<String, PolyCoinEconomyAccount>> accounts,
            Map<UUID, String> defaultAccounts,
            String defaultCurrencyId
    ) {
        Objects.requireNonNull(currencies, "currencies");
        Objects.requireNonNull(accounts, "accounts");

        this.currencies = new ConcurrentHashMap<>(currencies.size() + 1);
        this.accounts = new ConcurrentHashMap<>(accounts.size());
        this.defaultAccounts = new ConcurrentHashMap<>(defaultAccounts);
        this.defaultCurrencyId = Objects.requireNonNull(defaultCurrencyId, "defaultCurrencyId");

        // Validate and copy currencies.
        for (var entry : currencies.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "currency map key");
            PolyCoinEconomyCurrency currency = Objects.requireNonNull(entry.getValue(), "currency");

            if (!key.equals(currency.id().getPath())) {
                throw new IllegalStateException("Currency key '" + key + "' does not match currency id '" + currency.id() + "'");
            }

            if (this.currencies.putIfAbsent(key, currency) != null) {
                throw new IllegalStateException("Duplicate currency id: " + currency.id());
            }
        }

        // Validate saved references without recreating currencies that were deleted.
        getDefaultCurrency();

        // Validate, deep-copy and attach accounts.
        for (var ownerEntry : accounts.entrySet()) {
            UUID owner = Objects.requireNonNull(ownerEntry.getKey(), "account owner");
            Map<String, PolyCoinEconomyAccount> storedAccounts = Objects.requireNonNull(ownerEntry.getValue(), "accounts for " + owner);
            Map<String, PolyCoinEconomyAccount> playerAccounts = new ConcurrentHashMap<>(storedAccounts.size());

            for (var entry : storedAccounts.entrySet()) {
                String key = Objects.requireNonNull(entry.getKey(), "account map key");
                PolyCoinEconomyAccount account = Objects.requireNonNull(entry.getValue(), "account");

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
            if (!this.defaultAccounts.containsKey(owner)) {
                throw new IllegalStateException("Missing default account selection for " + owner);
            }
        }

        for (var entry : this.defaultAccounts.entrySet()) {
            Map<String, PolyCoinEconomyAccount> playerAccounts = this.accounts.get(entry.getKey());
            if (playerAccounts == null || !playerAccounts.containsKey(entry.getValue())) {
                throw new IllegalStateException("Missing default account " + entry.getValue() + " for " + entry.getKey());
            }
        }
    }

    public Map<String, PolyCoinEconomyAccount> getAccounts(GameProfile profile) {
        Objects.requireNonNull(profile, "profile");
        UUID owner = Objects.requireNonNull(profile.id(), "profile id");

        getDefaultAccount(profile);
        return Collections.unmodifiableMap(accounts.get(owner));
    }

    public List<String> getAccountIds(GameProfile profile) {
        Objects.requireNonNull(profile, "profile");
        UUID owner = Objects.requireNonNull(profile.id(), "profile id");
        SortedSet<String> accountIds = new TreeSet<>();
        accountIds.add(getDefaultAccountId(owner));

        Map<String, PolyCoinEconomyAccount> playerAccounts = accounts.get(owner);
        if (playerAccounts != null) {
            accountIds.addAll(playerAccounts.keySet());
        }

        return List.copyOf(accountIds);
    }

    public List<String> getAccountIds(GameProfile profile, EconomyCurrency currency) {
        Objects.requireNonNull(profile, "profile");
        UUID owner = Objects.requireNonNull(profile.id(), "profile id");
        Objects.requireNonNull(currency, "currency");
        PolyCoinEconomyCurrency canonicalCurrency = getCurrency(currency.id());

        if (canonicalCurrency == null || !Helpers.isSameCurrency(canonicalCurrency, currency)) {
            return List.of();
        }

        SortedSet<String> accountIds = new TreeSet<>();
        String defaultId = getDefaultAccountId(owner);
        if (canonicalCurrency == getAccountCurrency(profile, defaultId)) {
            accountIds.add(defaultId);
        }

        Map<String, PolyCoinEconomyAccount> playerAccounts = accounts.get(owner);
        if (playerAccounts != null) {
            for (PolyCoinEconomyAccount account : playerAccounts.values()) {
                if (account.usesCurrency(canonicalCurrency.id())) {
                    accountIds.add(account.id().getPath());
                }
            }
        }

        return List.copyOf(accountIds);
    }

    public @Nullable PolyCoinEconomyCurrency getAccountCurrency(GameProfile profile, String accountId) {
        Objects.requireNonNull(profile, "profile");
        UUID owner = Objects.requireNonNull(profile.id(), "profile id");
        Objects.requireNonNull(accountId, "accountId");

        Map<String, PolyCoinEconomyAccount> playerAccounts = accounts.get(owner);
        PolyCoinEconomyAccount account = playerAccounts == null ? null : playerAccounts.get(accountId);
        if (account != null) return account.currency();
        // Suggestions can inspect an uninitialized player's starter account without creating it.
        return MAIN_ACCOUNT_KEY.equals(accountId) && !defaultAccounts.containsKey(owner)
                ? getDefaultCurrency() : null;
    }

    public List<LeaderboardEntry> getTopAccounts(PolyCoinEconomyCurrency currency, int limit) {
        Objects.requireNonNull(currency, "currency");
        if (limit < 0) throw new IllegalArgumentException("limit cannot be negative: " + limit);
        if (getCurrency(currency.id()) != currency) {
            throw new IllegalArgumentException("Currency is not managed by this economy data: " + currency.id());
        }
        if (limit == 0) return List.of();

        PriorityQueue<LeaderboardEntry> entries = new PriorityQueue<>(limit, LEADERBOARD_ORDER.reversed());
        for (Map<String, PolyCoinEconomyAccount> playerAccounts : accounts.values()) {
            for (PolyCoinEconomyAccount account : playerAccounts.values()) {
                if (account.usesCurrency(currency.id())) {
                    LeaderboardEntry entry = new LeaderboardEntry(account.owner(), account.id(), account.name(), account.balance());
                    if (entries.size() < limit) {
                        entries.add(entry);
                    } else if (LEADERBOARD_ORDER.compare(entry, entries.peek()) < 0) {
                        entries.remove();
                        entries.add(entry);
                    }
                }
            }
        }

        List<LeaderboardEntry> result = new ArrayList<>(entries);
        result.sort(LEADERBOARD_ORDER);
        return List.copyOf(result);
    }

    public synchronized @Nullable PolyCoinEconomyAccount getAccount(GameProfile profile, String accountId) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(accountId, "accountId");
        UUID owner = Objects.requireNonNull(profile.id(), "profile id");

        // The default account is created lazily.
        if (MAIN_ACCOUNT_KEY.equals(accountId) && !defaultAccounts.containsKey(owner)) {
            return getDefaultAccount(profile);
        }

        Map<String, PolyCoinEconomyAccount> playerAccounts = accounts.get(owner);
        if (playerAccounts == null) return null;

        return playerAccounts.get(accountId);
    }

    public synchronized @Nullable PolyCoinEconomyAccount createAccount(
            GameProfile profile,
            Identifier id,
            String name,
            Item icon,
            PolyCoinEconomyCurrency currency
    ) {
        Objects.requireNonNull(profile, "profile");
        UUID owner = Objects.requireNonNull(profile.id(), "profile id");
        Helpers.requirePolyCoinIdentifier(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(icon, "icon");
        Objects.requireNonNull(currency, "currency");

        if (MAIN_ACCOUNT_ID.equals(id)) {
            throw new IllegalArgumentException("The main account id is reserved");
        }
        if (getCurrency(currency.id()) != currency) {
            throw new IllegalArgumentException("Currency is not managed by this economy data: " + currency.id());
        }

        Map<String, PolyCoinEconomyAccount> playerAccounts = accounts.get(owner);
        if (playerAccounts != null && playerAccounts.containsKey(id.getPath())) return null;

        // Secondary accounts start empty. Granting the currency default for every
        // new account would allow unlimited money through create/delete cycles.
        PolyCoinEconomyAccount account = new PolyCoinEconomyAccount(
                id, currency.id(), BigInteger.ZERO, owner, name, icon
        );
        account.attach(this);
        // Initialize the one-time starter account before this player can choose another default.
        getDefaultAccount(profile);
        playerAccounts = accounts.get(owner);
        playerAccounts.put(id.getPath(), account);
        setDirty();
        return account;
    }

    public synchronized @Nullable PolyCoinEconomyAccount updateAccount(
            GameProfile profile,
            Identifier id,
            String name,
            Item icon,
            PolyCoinEconomyCurrency currency
    ) {
        Objects.requireNonNull(profile, "profile");
        UUID owner = Objects.requireNonNull(profile.id(), "profile id");
        Helpers.requirePolyCoinIdentifier(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(icon, "icon");
        Objects.requireNonNull(currency, "currency");

        if (getCurrency(currency.id()) != currency) {
            throw new IllegalArgumentException("Currency is not managed by this economy data: " + currency.id());
        }

        PolyCoinEconomyAccount account = getAccount(profile, id.getPath());
        if (account == null) return null;
        if (!owner.equals(account.owner())) {
            throw new IllegalStateException("Account " + id + " is not owned by " + owner);
        }
        account.updateMetadata(currency.id(), name, icon);
        return account;
    }

    public synchronized @Nullable PolyCoinEconomyAccount deleteAccount(GameProfile profile, Identifier id) {
        Objects.requireNonNull(profile, "profile");
        UUID owner = Objects.requireNonNull(profile.id(), "profile id");
        Helpers.requirePolyCoinIdentifier(id, "id");
        if (isDefaultAccount(owner, id)) {
            throw new IllegalArgumentException("The default account cannot be deleted. Choose another default first.");
        }

        Map<String, PolyCoinEconomyAccount> playerAccounts = accounts.get(owner);
        if (playerAccounts == null) return null;

        PolyCoinEconomyAccount account = playerAccounts.remove(id.getPath());
        if (account == null) return null;
        account.detach(this);
        if (playerAccounts.isEmpty()) accounts.remove(owner, playerAccounts);
        setDirty();
        return account;
    }

    public Map<String, PolyCoinEconomyCurrency> getCurrencies() {
        return Collections.unmodifiableMap(currencies);
    }

    public record TransferResult(boolean successful, Component message) {}

    public synchronized TransferResult transfer(
            PolyCoinEconomyAccount source, PolyCoinEconomyAccount target, BigInteger amount
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() <= 0) {
            return new TransferResult(false, Component.literal("The amount must be greater than zero."));
        }
        if (source == target) {
            return new TransferResult(false, Component.literal("The source and target accounts must be different."));
        }
        if (!isManagedAccount(source) || !isManagedAccount(target)) {
            return new TransferResult(false, Component.literal("An account no longer exists in this economy."));
        }

        // The data lock serializes transfers and account deletion/metadata changes.
        // Account locks also exclude balance changes made through the economy API.
        synchronized (source) {
            synchronized (target) {
                if (!source.usesCurrency(target.currencyId())) {
                    return new TransferResult(false, Component.literal("The source and target accounts use different currencies."));
                }
                var debit = source.canDecreaseBalance(amount);
                if (debit.isFailure()) return new TransferResult(false, debit.message());
                var credit = target.canIncreaseBalance(amount);
                if (credit.isFailure()) return new TransferResult(false, credit.message());

                // Both balances are validated before either changes; the locks keep
                // these final values valid until both writes have completed.
                source.setBalance(debit.finalBalance());
                target.setBalance(credit.finalBalance());
                return new TransferResult(true, Component.literal("Transfer successful."));
            }
        }
    }

    private boolean isManagedAccount(PolyCoinEconomyAccount account) {
        Map<String, PolyCoinEconomyAccount> playerAccounts = accounts.get(account.owner());
        return playerAccounts != null && playerAccounts.get(account.id().getPath()) == account;
    }

    public @Nullable PolyCoinEconomyCurrency getCurrency(String currencyId) {
        Objects.requireNonNull(currencyId, "currencyId");
        return currencies.get(currencyId);
    }

    public @Nullable PolyCoinEconomyCurrency getCurrency(Identifier currencyId) {
        Objects.requireNonNull(currencyId, "currencyId");
        if (!PolyCoin.MOD_ID.equals(currencyId.getNamespace())) return null;
        return getCurrency(currencyId.getPath());
    }

    public synchronized @Nullable PolyCoinEconomyCurrency createCurrency(
            Identifier id, String name, Item icon, BigInteger defaultBalance
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(icon, "icon");
        Objects.requireNonNull(defaultBalance, "defaultBalance");

        PolyCoinEconomyCurrency currency = new PolyCoinEconomyCurrency(id, name, icon, defaultBalance);
        if (currencies.putIfAbsent(id.getPath(), currency) != null) return null;

        setDirty();
        return currency;
    }

    public synchronized @Nullable PolyCoinEconomyCurrency updateCurrency(
            Identifier id, String name, Item icon, BigInteger defaultBalance
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(icon, "icon");
        Objects.requireNonNull(defaultBalance, "defaultBalance");

        PolyCoinEconomyCurrency current = getCurrency(id);
        if (current == null) return null;
        if (current.displayName().equals(name) && current.iconItem() == icon && current.defaultBalance().equals(defaultBalance)) {
            return current;
        }

        PolyCoinEconomyCurrency updated = new PolyCoinEconomyCurrency(id, name, icon, defaultBalance);
        currencies.put(id.getPath(), updated);
        setDirty();
        return updated;
    }

    public int countAccounts(PolyCoinEconomyCurrency currency) {
        Objects.requireNonNull(currency, "currency");
        if (getCurrency(currency.id()) != currency) {
            throw new IllegalArgumentException("Currency is not managed by this economy data: " + currency.id());
        }

        int count = 0;
        for (Map<String, PolyCoinEconomyAccount> playerAccounts : accounts.values()) {
            for (PolyCoinEconomyAccount account : playerAccounts.values()) {
                if (account.usesCurrency(currency.id())) count++;
            }
        }
        return count;
    }

    public synchronized @Nullable CurrencyDeletionResult deleteCurrency(Identifier id) {
        Objects.requireNonNull(id, "id");
        Component deletionError = getCurrencyDeletionError(id);
        if (deletionError != null) throw new IllegalArgumentException(deletionError.getString());

        PolyCoinEconomyCurrency currency = getCurrency(id);
        if (currency == null) return null;

        int deletedAccounts = 0;
        for (var ownerEntry : accounts.entrySet()) {
            Map<String, PolyCoinEconomyAccount> playerAccounts = ownerEntry.getValue();
            for (var accountEntry : playerAccounts.entrySet()) {
                PolyCoinEconomyAccount account = accountEntry.getValue();
                if (account.usesCurrency(currency.id())
                        && playerAccounts.remove(accountEntry.getKey(), account)) {
                    account.detach(this);
                    deletedAccounts++;
                }
            }
            if (playerAccounts.isEmpty()) accounts.remove(ownerEntry.getKey(), playerAccounts);
        }

        if (!currencies.remove(id.getPath(), currency)) {
            throw new IllegalStateException("Currency changed while it was being deleted: " + id);
        }

        setDirty();
        return new CurrencyDeletionResult(currency, deletedAccounts);
    }

    public @Nullable String defaultAccount(GameProfile profile, EconomyCurrency currency) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(profile.id(), "profile id");
        Objects.requireNonNull(currency, "currency");

        PolyCoinEconomyCurrency canonicalCurrency = getCurrency(currency.id());
        if (canonicalCurrency == null || !Helpers.isSameCurrency(canonicalCurrency, currency)) return null;

        String defaultId = getDefaultAccountId(profile.id());
        if (canonicalCurrency == getAccountCurrency(profile, defaultId)) return defaultId;

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

    public String getDefaultAccountId(UUID owner) {
        return defaultAccounts.getOrDefault(Objects.requireNonNull(owner, "owner"), MAIN_ACCOUNT_KEY);
    }

    public boolean isDefaultAccount(UUID owner, Identifier id) {
        return PolyCoin.MOD_ID.equals(id.getNamespace()) && getDefaultAccountId(owner).equals(id.getPath());
    }

    public synchronized PolyCoinEconomyAccount getDefaultAccount(GameProfile profile) {
        UUID owner = Objects.requireNonNull(profile.id(), "profile id");
        Map<String, PolyCoinEconomyAccount> playerAccounts = accounts.computeIfAbsent(owner, _ -> new ConcurrentHashMap<>());
        String accountId = getDefaultAccountId(owner);
        PolyCoinEconomyAccount account = playerAccounts.get(accountId);
        if (account == null && !defaultAccounts.containsKey(owner)) {
            PolyCoinEconomyCurrency currency = getDefaultCurrency();
            PolyCoinEconomyAccount created = new PolyCoinEconomyAccount(
                    MAIN_ACCOUNT_ID, currency.id(), currency.defaultBalance(),
                    owner, "Main Account", MAIN_ACCOUNT_ICON
            );
            created.attach(this);
            playerAccounts.put(MAIN_ACCOUNT_KEY, created);
            account = created;
        }
        if (account == null) {
            throw new IllegalStateException("Missing default account " + accountId + " for " + owner);
        }
        if (defaultAccounts.putIfAbsent(owner, accountId) == null) setDirty();
        return account;
    }

    public synchronized void setDefaultAccount(GameProfile profile, Identifier id) {
        Helpers.requirePolyCoinIdentifier(id, "id");
        if (getAccount(profile, id.getPath()) == null) {
            throw new IllegalArgumentException("Unknown account: " + id);
        }
        if (!id.getPath().equals(defaultAccounts.put(profile.id(), id.getPath()))) setDirty();
    }

    public PolyCoinEconomyCurrency getDefaultCurrency() {
        PolyCoinEconomyCurrency currency = currencies.get(defaultCurrencyId);
        if (currency == null) {
            throw new IllegalStateException("PolyCoin default currency is missing: " + defaultCurrencyId);
        }
        return currency;
    }

    public synchronized void setDefaultCurrency(Identifier id) {
        if (getCurrency(id) == null) throw new IllegalArgumentException("Unknown currency: " + id);
        if (!defaultCurrencyId.equals(id.getPath())) {
            defaultCurrencyId = id.getPath();
            setDirty();
        }
    }

    public synchronized @Nullable Component getCurrencyDeletionError(Identifier id) {
        if (getDefaultCurrency().id().equals(id)) {
            return Component.literal("The default currency cannot be deleted. Choose another default first.");
        }
        for (var entry : accounts.entrySet()) {
            PolyCoinEconomyAccount account = entry.getValue().get(getDefaultAccountId(entry.getKey()));
            if (account != null && account.usesCurrency(id)) {
                return Component.literal("This currency is used by a player's default account. Choose another default account for each affected player first.");
            }
        }
        return null;
    }

    public record CurrencyDeletionResult(PolyCoinEconomyCurrency currency, int deletedAccounts) {}

}
