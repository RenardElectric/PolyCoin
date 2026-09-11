package polycube.polycoin.economy;

import com.mojang.serialization.DataResult;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;
import polycube.polycoin.PolyCoin;

import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/// Internal account state. All access is serialized by the owning economy's monitor.
public final class PolyCoinEconomyAccountData {
    public record LeaderboardEntry(Set<UUID> owners, Identifier accountId, Component accountName, BigInteger balance) {}
    public record CurrencyStatistics(int accountCount, BigInteger totalBalance) {}

    private static final Comparator<LeaderboardEntry> LEADERBOARD_ORDER =
            Comparator.comparing(LeaderboardEntry::balance).reversed()
                    .thenComparing(entry -> entry.accountId().toString());

    public static final Item DEFAULT_ACCOUNT_ICON = Items.BUNDLE;
    public static final String DEFAULT_ACCOUNT_ID = "main_account";
    public static final String DEFAULT_ACCOUNT_NAME = "Main Account";
    public final static ItemStackTemplate DEFAULT_ACCOUNT_ICON_TEMPLATE = new ItemStackTemplate(
            DEFAULT_ACCOUNT_ICON,
            DataComponentPatch.builder()
                    .set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath(PolyCoin.MOD_ID, "default_account"))
                    .set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
                    .build()
    );

    final Map<String, PolyCoinEconomyAccount> accounts = new TreeMap<>();
    /// Derived lookup cache. Account ownership is authoritative; this index is rebuilt on load and is never persisted.
    private final Map<UUID, NavigableSet<String>> accountIdsByOwner = new HashMap<>();
    final Map<UUID, Map<String, String>> defaultAccountIds = new TreeMap<>();
    private final Map<UUID, Long> initializedAt = new HashMap<>();
    private final PolyCoinEconomyData data;

    PolyCoinEconomyAccountData(
            PolyCoinEconomyData data,
            Map<String, PolyCoinEconomyAccount> accounts,
            Map<UUID, Map<String, String>> defaultAccountIds
    ) {
        this.data = data;
        for (var accountEntry : accounts.entrySet()) {
            var accountId = accountEntry.getKey();
            var account = accountEntry.getValue().copy(data);
            this.accounts.put(accountId, account);
            indexAccount(account);
        }
        defaultAccountIds.forEach((owner, ids) -> this.defaultAccountIds.put(owner, new TreeMap<>(ids)));
    }

    static List<NameAndId> uniqueProfiles(Collection<NameAndId> profiles) {
        return List.copyOf(profiles.stream().collect(Collectors.toMap(
                NameAndId::id,
                Function.identity(),
                (first, _) -> first,
                TreeMap::new
        )).values());
    }

    private void indexAccount(PolyCoinEconomyAccount account) {
        for (var owner : account.owners()) ownerAdded(account, owner);
    }

    private void unindexAccount(PolyCoinEconomyAccount account) {
        for (var owner : account.owners()) ownerRemoved(account, owner);
    }

    void ownerAdded(PolyCoinEconomyAccount account, UUID owner) {
        if (!isManaged(account)) return;
        accountIdsByOwner.computeIfAbsent(owner, _ -> new TreeSet<>()).add(account.getId());
    }

    void ownerRemoved(PolyCoinEconomyAccount account, UUID owner) {
        if (!isManaged(account)) return;
        var accountIds = accountIdsByOwner.get(owner);
        if (accountIds == null) return;
        accountIds.remove(account.getId());
        if (accountIds.isEmpty()) accountIdsByOwner.remove(owner);
    }

    boolean isManaged(PolyCoinEconomyAccount account) {
        return account == accounts.get(account.getId());
    }

    void ensureAllAccountsCreated() {
        var players = new HashSet<>(accountIdsByOwner.keySet());
        players.addAll(defaultAccountIds.keySet());
        players.forEach(this::ensureAccountsCreated);
    }

    private Map<String, PolyCoinEconomyAccount> getPlayerAccounts(UUID uuid) {
        var accountIds = accountIdsByOwner.get(uuid);
        if (accountIds == null) return new TreeMap<>();
        var playerAccounts = new TreeMap<String, PolyCoinEconomyAccount>();
        for (var accountId : accountIds) {
            var account = accounts.get(accountId);
            if (account != null) playerAccounts.put(accountId, account);
        }
        return playerAccounts;
    }

    Map<String, PolyCoinEconomyAccount> getAccounts(UUID uuid) {
        ensureAccountsCreated(uuid);
        return Collections.unmodifiableMap(new TreeMap<>(getPlayerAccounts(uuid)));
    }

    Map<String, PolyCoinEconomyAccount> getAccounts(UUID uuid, String currencyId) {
        if (!data.currencyData.currencies.containsKey(currencyId)) return Collections.emptyMap();
        ensureAccountsCreated(uuid);
        return getPlayerAccounts(uuid).values().stream().filter(account -> account.usesCurrency(currencyId))
                .collect(TreeMap::new, (map, account) -> map.put(account.getId(), account), TreeMap::putAll);
    }

    DataResult<List<LeaderboardEntry>> getTopAccounts(String currencyId, int limit) {
        if (limit < 0) return DataResult.error(() -> "Limit must be non-negative");
        return data.getCurrency(currencyId).map(_ -> {
            if (limit == 0) return List.of();
            var entries = new PriorityQueue<>(Math.min(limit, 64), LEADERBOARD_ORDER.reversed());
            for (var account : accounts.values()) {
                if (!account.usesCurrency(currencyId)) continue;
                var entry = new LeaderboardEntry(account.owners(), account.id(), account.name(), account.balance());
                if (entries.size() < limit) entries.add(entry);
                else if (LEADERBOARD_ORDER.compare(entry, entries.peek()) < 0) {
                    entries.remove();
                    entries.add(entry);
                }
            }
            var result = new ArrayList<>(entries);
            result.sort(LEADERBOARD_ORDER);
            return List.copyOf(result);
        });
    }

    DataResult<PolyCoinEconomyAccount> getAccount(String accountId) {
        if (!EconomyValidation.validId(accountId)) return DataResult.error(() -> "Invalid account ID: " + accountId);
        var account = accounts.get(accountId);
        return account == null ? DataResult.error(() -> "Account not found: " + accountId) : DataResult.success(account);
    }

    DataResult<PolyCoinEconomyAccount> getAccount(UUID uuid, String accountId) {
        if (!EconomyValidation.validId(accountId)) return DataResult.error(() -> "Invalid account ID: " + accountId);
        var account = accounts.get(accountId);
        if (account == null) return DataResult.error(() -> "Account not found: " + accountId);
        return account.isOwnedBy(uuid) ? DataResult.success(account) : DataResult.error(() -> "Account not found: " + accountId);
    }

    DataResult<PolyCoinEconomyAccount> createAccount(Set<UUID> owners, String accountId, String name, Item icon, String currencyId) {
        return PolyCoinEconomyAccount.create(data, accountId, currencyId, owners, name, icon)
                .flatMap(account -> data.getCurrency(currencyId).map(_ -> account))
                .flatMap(account -> {
                    if (accounts.containsKey(accountId)) return DataResult.error(() -> "Account already exists: " + accountId);
                    for (var owner : account.owners()) ensureAccountsCreated(owner);
                    if (accounts.containsKey(accountId)) return DataResult.error(() -> "Account already exists: " + accountId);
                    accounts.put(accountId, account);
                    indexAccount(account);
                    data.setDirty();
                    EconomyLog.accountCreated(account);
                    return DataResult.success(account);
                });
    }

    DataResult<PolyCoinEconomyAccount> updateAccount(String accountId, String name, Item icon, String currencyId) {
        return EconomyValidation.metadata(name, icon).flatMap(_ -> data.getCurrency(currencyId))
                .flatMap(_ -> getAccount(accountId)).flatMap(account -> {
                    if (!account.usesCurrency(currencyId)) {
                        if (account.balance().signum() != 0) return DataResult.error(() -> "An account balance must be zero before its currency can be changed");
                        if (isDefaultAccount(accountId)) return DataResult.error(() -> "Set another default account before changing this account's currency");
                        return PolyCoinEconomyAccount.create(data, accountId, currencyId, account.owners(), name, icon).map(replacement -> {
                            accounts.put(accountId, replacement);
                            data.setDirty();
                            EconomyLog.accountUpdated(replacement, account.currencyId(), account.displayName(), account.iconItem());
                            return replacement;
                        });
                    }
                    if (!account.displayName().equals(name) || account.iconItem() != icon) {
                        account.setMetadata(name, icon);
                        data.setDirty();
                    }
                    return DataResult.success(account);
                });
    }

    DataResult<PolyCoinEconomyAccount> addAccountOwners(String accountId, Collection<NameAndId> profiles) {
        return getAccount(accountId).flatMap(account -> {
            var uniqueProfiles = uniqueProfiles(profiles);
            var currentOwners = account.owners();
            for (var profile : uniqueProfiles) {
                if (!currentOwners.contains(profile.id())) ensureAccountsCreated(profile.id());
            }
            return account.addOwners(uniqueProfiles).map(_ -> account);
        });
    }

    DataResult<PolyCoinEconomyAccount> removeAccountOwners(String accountId, Collection<NameAndId> profiles) {
        return getAccount(accountId).flatMap(account -> {
            var uniqueProfiles = uniqueProfiles(profiles);
            for (var owner : uniqueProfiles) {
                if (defaultAccountIds.getOrDefault(owner.id(), Map.of()).containsValue(accountId)) {
                    return DataResult.error(() -> "Cannot remove an owner from an account that is their default for a currency. Set another default first.");
                }
            }
            return account.removeOwners(uniqueProfiles).map(_ -> account);
        });
    }

    DataResult<PolyCoinEconomyAccount> deleteAccount(String accountId) {
        return getAccount(accountId).flatMap(account -> {
            if (isDefaultAccount(accountId)) return DataResult.error(() -> "Cannot delete the default account. Set another default first.");
            unindexAccount(account);
            accounts.remove(accountId);
            data.setDirty();
            EconomyLog.accountDeleted(account, "deleted");
            return DataResult.success(account);
        });
    }

    DataResult<BigInteger> transfer(String sourceAccountId, String targetAccountId, BigInteger amount) {
        if (amount.signum() <= 0) return DataResult.error(() -> "Transfer amount must be positive");
        if (!EconomyValidation.validId(sourceAccountId) || !EconomyValidation.validId(targetAccountId)) {
            return DataResult.error(() -> "Invalid account ID");
        }
        if (sourceAccountId.equals(targetAccountId)) {
            return DataResult.error(() -> "The source and target accounts must be different.");
        }
        return getAccount(sourceAccountId).flatMap(source ->
                getAccount(targetAccountId).flatMap(target -> {
                    if (!source.usesCurrency(target.currencyId())) return DataResult.error(() -> "The source and target accounts use different currencies.");
                    var debit = source.canDecreaseBalance(amount);
                    if (debit.isFailure()) return DataResult.error(() -> debit.message().getString());
                    var credit = target.canIncreaseBalance(amount);
                    if (credit.isFailure()) return DataResult.error(() -> credit.message().getString());
                    return source.trySetBalance(debit.finalBalance()).flatMap(_ -> target.trySetBalance(credit.finalBalance()))
                            .map(balance -> {
                                EconomyLog.transferred(source, target, amount);
                                return balance;
                            });
                }));
    }

    CurrencyStatistics getCurrencyStatistics(String currencyId) {
        int count = 0;
        BigInteger total = BigInteger.ZERO;
        for (var account : accounts.values()) {
            if (account.usesCurrency(currencyId)) {
                count++;
                total = total.add(account.balance());
            }
        }
        return new CurrencyStatistics(count, total);
    }

    int countAccounts(String currencyId) {
        int count = 0;
        for (var account : accounts.values()) if (account.usesCurrency(currencyId)) count++;
        return count;
    }

    int removeCurrency(String currencyId) {
        int removed = 0;
        var iterator = accounts.values().iterator();
        while (iterator.hasNext()) {
            var account = iterator.next();
            if (account.usesCurrency(currencyId)) {
                unindexAccount(account);
                iterator.remove();
                removed++;
                EconomyLog.accountDeleted(account, "currency_deleted");
            }
        }
        defaultAccountIds.forEach((owner, ids) -> {
            var previous = ids.remove(currencyId);
            if (previous != null) EconomyLog.defaultAccountChanged(owner, currencyId, previous, null, "currency_deleted");
        });
        return removed;
    }

    private void ensureAccountsCreated(UUID uuid) {
        long revision = data.currencyData.revision;
        if (Objects.equals(initializedAt.get(uuid), revision)) return;
        var selections = defaultAccountIds.computeIfAbsent(uuid, _ -> new TreeMap<>());
        var selectionIterator = selections.entrySet().iterator();
        while (selectionIterator.hasNext()) {
            var entry = selectionIterator.next();
            if (!data.currencyData.currencies.containsKey(entry.getKey())) {
                selectionIterator.remove();
                data.setDirty();
                EconomyLog.defaultAccountChanged(uuid, entry.getKey(), entry.getValue(), null, "unknown_currency");
            }
        }

        var playerAccounts = getPlayerAccounts(uuid);
        for (var currency : data.currencyData.currencies.values()) {
            var currencyId = currency.getId();
            var accountId = selections.get(currencyId);
            var account = accountId == null ? null : accounts.get(accountId);
            if (account != null && account.usesCurrency(currencyId) && account.isOwnedBy(uuid)) continue;
            account = playerAccounts.values().stream().filter(candidate -> candidate.usesCurrency(currencyId)).findFirst().orElse(null);
            if (account == null) {
                if (accountId == null || !EconomyValidation.validId(accountId) || accounts.containsKey(accountId)) {
                    var baseId = DEFAULT_ACCOUNT_ID + "_" + currencyId;
                    accountId = baseId;
                    for (int suffix = 1; accounts.containsKey(accountId); suffix++) accountId = baseId + "_" + suffix;
                }
                account = PolyCoinEconomyAccount.defaultAccount(data, accountId, currency, uuid);
                accounts.put(accountId, account);
                indexAccount(account);
                playerAccounts.put(accountId, account);
                EconomyLog.accountCreated(account);
            }
            var previous = selections.put(currencyId, account.getId());
            data.setDirty();
            if (!account.getId().equals(previous)) {
                EconomyLog.defaultAccountChanged(uuid, currencyId, previous, account.getId(), "automatic");
            }
        }
        initializedAt.put(uuid, revision);
    }

    @Nullable String getDefaultAccountId(UUID uuid, String currencyId) {
        if (!EconomyValidation.validId(currencyId) || !data.currencyData.currencies.containsKey(currencyId)) return null;
        ensureAccountsCreated(uuid);
        return defaultAccountIds.get(uuid).get(currencyId);
    }

    boolean isDefaultAccount(UUID uuid, String accountId) {
        if (!EconomyValidation.validId(accountId)) return false;
        ensureAccountsCreated(uuid);
        return defaultAccountIds.getOrDefault(uuid, Map.of()).containsValue(accountId);
    }

    boolean isDefaultAccount(String accountId) {
        if (!EconomyValidation.validId(accountId)) return false;
        return defaultAccountIds.values().stream().anyMatch(ids -> ids.containsValue(accountId));
    }

    DataResult<PolyCoinEconomyAccount> setDefaultAccount(UUID uuid, String accountId) {
        return getAccount(uuid, accountId).map(account -> {
            ensureAccountsCreated(uuid);
            var ids = defaultAccountIds.get(uuid);
            var previous = ids.put(account.currencyId(), accountId);
            if (!accountId.equals(previous)) {
                data.setDirty();
                EconomyLog.defaultAccountChanged(uuid, account.currencyId(), previous, accountId, "selected");
            }
            return account;
        });
    }
}
