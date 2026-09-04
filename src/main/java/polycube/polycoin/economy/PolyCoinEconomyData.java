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
    public static final Identifier MAIN_CURRENCY_ID = Identifier.fromNamespaceAndPath(PolyCoin.MOD_ID, "polycoin");
    public static final Identifier MAIN_ACCOUNT_ID = Identifier.fromNamespaceAndPath(PolyCoin.MOD_ID, "main_account");
    public static final String MAIN_CURRENCY_KEY = MAIN_CURRENCY_ID.getPath();
    public static final String MAIN_ACCOUNT_KEY = MAIN_ACCOUNT_ID.getPath();

    private final Map<String, PolyCoinEconomyCurrency> currencies;
    private final Map<UUID, Map<String, PolyCoinEconomyAccount>> accounts;

    public PolyCoinEconomyData(Map<String, PolyCoinEconomyCurrency> currencies, Map<UUID, Map<String, PolyCoinEconomyAccount>> accounts) {
        Objects.requireNonNull(currencies, "currencies");
        Objects.requireNonNull(accounts, "accounts");

        this.currencies = new ConcurrentHashMap<>(currencies.size() + 1);
        this.accounts = new ConcurrentHashMap<>(accounts.size());

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

        // Main currency must exist before accounts are attached.
        if (!this.currencies.containsKey(MAIN_CURRENCY_KEY)) {
            this.currencies.put(
                    MAIN_CURRENCY_KEY,
                    new PolyCoinEconomyCurrency(
                            MAIN_CURRENCY_ID, "polycoins",
                            MAIN_CURRENCY_ICON, DEFAULT_BALANCE
                    )
            );
            setDirty();
        }

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
        }
    }

    public Map<String, PolyCoinEconomyAccount> getAccounts(GameProfile profile) {
        Objects.requireNonNull(profile, "profile");
        UUID owner = Objects.requireNonNull(profile.id(), "profile id");

        // Listing a player's accounts should include their main account.
        getMainAccount(owner);
        return Collections.unmodifiableMap(accounts.get(owner));
    }

    public List<String> getAccountIds(GameProfile profile) {
        Objects.requireNonNull(profile, "profile");
        UUID owner = Objects.requireNonNull(profile.id(), "profile id");
        SortedSet<String> accountIds = new TreeSet<>();
        accountIds.add(MAIN_ACCOUNT_KEY);

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
        if (canonicalCurrency == getMainCurrency()) {
            accountIds.add(MAIN_ACCOUNT_KEY);
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

        if (MAIN_ACCOUNT_KEY.equals(accountId)) {
            return getMainCurrency();
        }

        Map<String, PolyCoinEconomyAccount> playerAccounts = accounts.get(owner);
        if (playerAccounts == null) return null;

        PolyCoinEconomyAccount account = playerAccounts.get(accountId);
        return account == null ? null : account.currency();
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

    public @Nullable PolyCoinEconomyAccount getAccount(GameProfile profile, String accountId) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(accountId, "accountId");
        UUID owner = Objects.requireNonNull(profile.id(), "profile id");

        // The default account is created lazily.
        if (MAIN_ACCOUNT_KEY.equals(accountId)) return getMainAccount(owner);

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

        Map<String, PolyCoinEconomyAccount> playerAccounts =
                accounts.computeIfAbsent(owner, _ -> new ConcurrentHashMap<>());
        if (playerAccounts.containsKey(id.getPath())) return null;

        // Secondary accounts start empty. Granting the currency default for every
        // new account would allow unlimited money through create/delete cycles.
        PolyCoinEconomyAccount account = new PolyCoinEconomyAccount(
                id, currency.id(), BigInteger.ZERO, owner, name, icon
        );
        account.attach(this);
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
        if (MAIN_ACCOUNT_ID.equals(id) && !MAIN_CURRENCY_ID.equals(currency.id())) {
            throw new IllegalArgumentException("The main account currency cannot be changed");
        }

        account.updateMetadata(currency.id(), name, icon);
        return account;
    }

    public synchronized @Nullable PolyCoinEconomyAccount deleteAccount(GameProfile profile, Identifier id) {
        Objects.requireNonNull(profile, "profile");
        UUID owner = Objects.requireNonNull(profile.id(), "profile id");
        Helpers.requirePolyCoinIdentifier(id, "id");
        if (MAIN_ACCOUNT_ID.equals(id)) {
            throw new IllegalArgumentException("The main account cannot be deleted");
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
        if (MAIN_CURRENCY_ID.equals(id)) {
            throw new IllegalArgumentException("The main currency cannot be deleted");
        }

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
        Objects.requireNonNull(owner, "owner");
        Map<String, PolyCoinEconomyAccount> playerAccounts = accounts.computeIfAbsent(owner, _ -> new ConcurrentHashMap<>());

        PolyCoinEconomyAccount account = playerAccounts.computeIfAbsent(MAIN_ACCOUNT_KEY, _ -> {
            PolyCoinEconomyCurrency currency = getMainCurrency();
            PolyCoinEconomyAccount created = new PolyCoinEconomyAccount(
                    MAIN_ACCOUNT_ID, currency.id(), currency.defaultBalance(),
                    owner, "Main Account", MAIN_ACCOUNT_ICON
            );
            created.attach(this);
            setDirty();
            return created;
        });

        if (!account.usesCurrency(MAIN_CURRENCY_ID)) {
            throw new IllegalStateException("Main account for " + owner + " uses unexpected currency " + account.currencyId());
        }
        return account;
    }

    public PolyCoinEconomyCurrency getMainCurrency() {
        PolyCoinEconomyCurrency currency = currencies.get(MAIN_CURRENCY_KEY);
        if (currency == null) {
            throw new IllegalStateException("PolyCoin main currency is missing");
        }
        return currency;
    }

    public record CurrencyDeletionResult(PolyCoinEconomyCurrency currency, int deletedAccounts) {}

}
