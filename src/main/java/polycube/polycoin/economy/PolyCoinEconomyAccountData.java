package polycube.polycoin.economy;

import com.mojang.serialization.DataResult;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.SavedData;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class PolyCoinEconomyAccountData extends SavedData {

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

    public static final Item DEFAULT_ACCOUNT_ICON = Items.NETHER_STAR;
    public static final String DEFAULT_ACCOUNT_ID = "main_account";
    public static final String DEFAULT_ACCOUNT_NAME = "Main Account";

    final Map<UUID, Map<String, PolyCoinEconomyAccount>> accounts;
    final Map<UUID, Map<String, String>> defaultAccountIds;

    private final PolyCoinEconomyData data;

    public PolyCoinEconomyAccountData(
            PolyCoinEconomyData data,
            Map<UUID, Map<String, PolyCoinEconomyAccount>> accounts,
            Map<UUID, Map<String, String>> defaultAccountIds
    ) {
        this.data = data;
        this.accounts = new ConcurrentHashMap<>(accounts.size());
        this.defaultAccountIds = new ConcurrentHashMap<>(defaultAccountIds.size());

        // Validate, deep-copy and attach accounts.
        for (var ownerEntry : accounts.entrySet()) {
            var owner = ownerEntry.getKey();
            var storedAccounts = ownerEntry.getValue();
            var playerAccounts = new ConcurrentHashMap<String, PolyCoinEconomyAccount>(storedAccounts.size());

            for (var entry : storedAccounts.entrySet()) {
                var accountId = entry.getKey();
                var account = entry.getValue();

                if (!owner.equals(account.owner())) {
                    throw new IllegalStateException("Account " + account.id() + " belongs to " + account.owner() + " but is stored under " + owner);
                }

                if (!accountId.equals(account.getId())) {
                    throw new IllegalStateException("Account key '" + accountId + "' does not match account id '" + account.id() + "'");
                }

                var currency = data.getCurrency(account.currencyId());
                if (currency.isError()) {
                    throw new IllegalStateException("Account " + account.id() + " uses unknown currency " + account.currencyId());
                }

                account.attach(data);

                playerAccounts.put(accountId, account);
            }

            accounts.put(owner, playerAccounts);
        }

        var players = accounts.keySet();
        players.addAll(defaultAccountIds.keySet());

        for (var player : players) {
            ensureAccountsCreated(player);
        }

        for (var entries : defaultAccountIds.entrySet()) {
            var player = entries.getKey();
            for (var values : entries.getValue().entrySet()) {
                var accountId = values.getValue();
                var currencyId = values.getKey();
                var playerAccounts = this.accounts.get(player);
                if (playerAccounts == null || !playerAccounts.containsKey(accountId)) {
                    throw new IllegalStateException("Default account " + accountId + " for player " + player + " and currency " + currencyId + " does not exist");
                }
                var account = playerAccounts.get(accountId);
                if (!currencyId.equals(account.currencyId())) {
                    throw new IllegalStateException("Default account " + accountId + " for player " + player + " and currency " + currencyId + " does not use that currency");
                }
            }
        }
    }

    Map<UUID, Map<String, PolyCoinEconomyAccount>> getAccountsInternal() {
        return accounts;
    }

    private Map<String, PolyCoinEconomyAccount> getPlayerAccountsInternal(UUID uuid) {
        ensureAccountsCreated(uuid);
        return getAccountsInternal().get(uuid);
    }

    Map<String, PolyCoinEconomyAccount> getAccounts(UUID uuid) {
        return Collections.unmodifiableMap(getPlayerAccountsInternal(uuid));
    }

    List<String> getAccountIds(UUID uuid) {
        var playerAccounts = getPlayerAccountsInternal(uuid);
        return List.copyOf(playerAccounts.keySet());
    }

    List<String> getAccountIds(UUID uuid, String currencyId) {
        var accountIds = new TreeSet<String>();
        var playerAccounts = getPlayerAccountsInternal(uuid);
        for (PolyCoinEconomyAccount account : playerAccounts.values()) {
            if (account.usesCurrency(currencyId)) {
                accountIds.add(account.getId());
            }
        }
        return List.copyOf(accountIds);
    }

    DataResult<PolyCoinEconomyCurrency> getAccountCurrency(UUID uuid, String accountId) {
        var playerAccounts = getPlayerAccountsInternal(uuid);
        var account = playerAccounts.get(accountId);
        if (account == null) return DataResult.error(() -> "Account not found: " + accountId);
        return data.getCurrency(account.currencyId());
    }

    DataResult<List<LeaderboardEntry>> getTopAccounts(String currencyId, int limit) {
        if (limit < 0) return DataResult.error(() -> "Limit must be non-negative");
        if (limit == 0) return DataResult.success(List.of());

        var entries = new PriorityQueue<>(limit, LEADERBOARD_ORDER.reversed());
        for (Map<String, PolyCoinEconomyAccount> playerAccounts : getAccountsInternal().values()) {
            for (PolyCoinEconomyAccount account : playerAccounts.values()) {
                if (account.usesCurrency(currencyId)) {
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
        return DataResult.success(List.copyOf(result));
    }

    synchronized DataResult<PolyCoinEconomyAccount> getAccount(UUID uuid, String accountId) {
        var playerAccounts = getPlayerAccountsInternal(uuid);
        var account = playerAccounts.get(accountId);
        if (account != null) return DataResult.success(account);
        return DataResult.error(() -> "Account not found: " + accountId);
    }

    synchronized DataResult<PolyCoinEconomyAccount> createAccount(
            UUID uuid, String accountId, String name,
            Item icon, String currencyId
    ) {
        var playerAccounts = getPlayerAccountsInternal(uuid);
        if (playerAccounts.containsKey(accountId)) return DataResult.error(() -> "Account already exists: " + accountId);

        var account = new PolyCoinEconomyAccount(accountId, currencyId, BigInteger.ZERO, uuid, name, icon);
        account.attach(data);
        playerAccounts.put(accountId, account);
        setDirty();
        return DataResult.success(account);
    }

    synchronized DataResult<PolyCoinEconomyAccount> updateAccount(
            UUID uuid, String accountId, String name,
            Item icon, String currencyId
    ) {
        var accountResult = getAccount(uuid, accountId);
        return accountResult.flatMap(account -> account.updateMetadata(currencyId, name, icon));
    }

    synchronized DataResult<PolyCoinEconomyAccount> deleteAccount(UUID uuid, String accountId) {
        if (isDefaultAccount(uuid, accountId)) return DataResult.error(() -> "Cannot delete the default account. Set another default first.");

        var playerAccounts = getPlayerAccountsInternal(uuid);
        var account = playerAccounts.remove(accountId);
        if (account == null) return DataResult.error(() -> "Account not found: " + accountId);
        account.detach();
        setDirty();
        return DataResult.success(account);
    }

    synchronized DataResult<BigInteger> transfer(UUID sourceUuid, String sourceAccountId, UUID targetUuid, String targetAccountId, BigInteger amount) {
        if (amount.signum() <= 0) return DataResult.error(() -> "Transfer amount must be positive");
        if (sourceUuid.equals(targetUuid) && sourceAccountId.equals(targetAccountId)) return DataResult.error(() -> "The source and target accounts must be different.");

        var sourceResult = getAccount(sourceUuid, sourceAccountId);
        if (sourceResult.isError()) return sourceResult.map(_ -> BigInteger.ZERO);
        var targetResult = getAccount(targetUuid, targetAccountId);
        if (targetResult.isError()) return targetResult.map(_ -> BigInteger.ZERO);

        var source = sourceResult.getOrThrow();
        var target = targetResult.getOrThrow();

        // The data lock serializes transfers and account deletion/metadata changes.
        // Account locks also exclude balance changes made through the economy API.
        synchronized (source) {
            synchronized (target) {
                if (!source.usesCurrency(target.currencyId())) return DataResult.error(() -> "The source and target accounts use different currencies.");

                var debit = source.canDecreaseBalance(amount);
                if (debit.isFailure()) return DataResult.error(() -> debit.message().tryCollapseToString());
                var credit = target.canIncreaseBalance(amount);
                if (credit.isFailure()) return DataResult.error(() -> credit.message().tryCollapseToString());

                // Both balances are validated before either changes; the locks keep
                // these final values valid until both writes have completed.
                source.setBalance(debit.finalBalance());
                target.setBalance(credit.finalBalance());
                return DataResult.success(credit.finalBalance());
            }
        }
    }

    DataResult<Integer> countAccounts(String currencyId) {
        int count = 0;
        for (Map<String, PolyCoinEconomyAccount> playerAccounts : accounts.values()) {
            for (PolyCoinEconomyAccount account : playerAccounts.values()) {
                if (account.usesCurrency(currencyId)) count++;
            }
        }
        return DataResult.success(count);
    }

    String defaultAccount(UUID uuid, String currencyId) {
        return getDefaultAccountId(uuid, currencyId);
    }

    private synchronized void ensureAccountsCreated(UUID uuid) {
        var accounts = this.accounts.computeIfAbsent(uuid, _ -> new ConcurrentHashMap<>());
        var defaultAccountIds = this.defaultAccountIds.computeIfAbsent(uuid, _ -> new ConcurrentHashMap<>());

        for (var currency : data.getCurrencies().values()) {
            var currencyId = currency.getId();
            var defaultAccountId = defaultAccountIds.get(currencyId);
            var defaultAccount = defaultAccountId == null ? null : accounts.get(defaultAccountId);
            if (defaultAccount != null && defaultAccount.usesCurrency(currencyId)) continue;

            defaultAccount = accounts.values().stream()
                    .filter(account -> account.usesCurrency(currencyId))
                    .min(Comparator.comparing(PolyCoinEconomyAccount::getId))
                    .orElse(null);

            if (defaultAccount == null) {
                // Reuse a missing saved ID, but never overwrite another currency's account.
                if (defaultAccountId == null || accounts.containsKey(defaultAccountId)) {
                    var baseId = DEFAULT_ACCOUNT_ID + "_" + currencyId;
                    defaultAccountId = baseId;
                    for (int suffix = 1; accounts.containsKey(defaultAccountId); suffix++) {
                        defaultAccountId = baseId + "_" + suffix;
                    }
                }
                defaultAccount = new PolyCoinEconomyAccount(
                        defaultAccountId, currencyId, currency.defaultBalance(),
                        uuid, DEFAULT_ACCOUNT_NAME, DEFAULT_ACCOUNT_ICON
                );
                defaultAccount.attach(data);
                accounts.put(defaultAccountId, defaultAccount);
            }

            defaultAccountIds.put(currencyId, defaultAccount.getId());
            data.setDirty();
        }
    }

    String getDefaultAccountId(UUID uuid, String currencyId) {
        ensureAccountsCreated(uuid);
        return defaultAccountIds.get(uuid).get(currencyId);
    }

    boolean isDefaultAccount(UUID uuid, String accountId) {
        ensureAccountsCreated(uuid);
        for (var entry : defaultAccountIds.get(uuid).entrySet()) {
            if (accountId.equals(entry.getValue())) return true;
        }
        return false;
    }

    synchronized DataResult<PolyCoinEconomyAccount> setDefaultAccount(UUID uuid, String accountId) {
        var accountResult = getAccount(uuid, accountId);
        if (accountResult.isError()) return accountResult;
        var account = accountResult.getOrThrow();
        if (isDefaultAccount(uuid, accountId)) return DataResult.success(account);
        defaultAccountIds.computeIfAbsent(uuid, _ -> new ConcurrentHashMap<>()).put(account.currencyId(), accountId);
        setDirty();
        return DataResult.success(account);
    }
}
