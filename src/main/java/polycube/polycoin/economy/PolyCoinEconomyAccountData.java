package polycube.polycoin.economy;

import com.mojang.serialization.DataResult;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;
import polycube.polycoin.PolyCoin;

import java.math.BigInteger;
import java.util.*;

/// Internal account state. All access is serialized by the owning economy's monitor.
public final class PolyCoinEconomyAccountData {
    public record LeaderboardEntry(UUID owner, Identifier accountId, Component accountName, BigInteger balance) {}
    public record CurrencyStatistics(int accountCount, BigInteger totalBalance) {}

    private static final Comparator<LeaderboardEntry> LEADERBOARD_ORDER =
            Comparator.comparing(LeaderboardEntry::balance).reversed()
                    .thenComparing(entry -> entry.owner().toString())
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

    final Map<UUID, Map<String, PolyCoinEconomyAccount>> accounts = new HashMap<>();
    final Map<UUID, Map<String, String>> defaultAccountIds = new HashMap<>();
    private final Map<UUID, Long> initializedAt = new HashMap<>();
    private final PolyCoinEconomyData data;

    PolyCoinEconomyAccountData(
            PolyCoinEconomyData data,
            Map<UUID, Map<String, PolyCoinEconomyAccount>> accounts,
            Map<UUID, Map<String, String>> defaultAccountIds
    ) {
        this.data = data;
        for (var ownerEntry : accounts.entrySet()) {
            var owner = ownerEntry.getKey();
            var playerAccounts = new TreeMap<String, PolyCoinEconomyAccount>();
            for (var entry : ownerEntry.getValue().entrySet()) {
                var account = entry.getValue().copy(data);
                playerAccounts.put(entry.getKey(), account);
            }
            this.accounts.put(owner, playerAccounts);
        }
        defaultAccountIds.forEach((owner, ids) -> this.defaultAccountIds.put(owner, new HashMap<>(ids)));
    }

    boolean isManaged(PolyCoinEconomyAccount account) {
        var playerAccounts = accounts.get(account.owner());
        return playerAccounts != null && playerAccounts.get(account.getId()) == account;
    }

    void ensureAllAccountsCreated() {
        var players = new HashSet<>(accounts.keySet());
        players.addAll(defaultAccountIds.keySet());
        players.forEach(this::ensureAccountsCreated);
    }

    private Map<String, PolyCoinEconomyAccount> getPlayerAccounts(UUID uuid) {
        ensureAccountsCreated(uuid);
        return accounts.get(uuid);
    }

    Map<String, PolyCoinEconomyAccount> getAccounts(UUID uuid) {
        return Collections.unmodifiableMap(new TreeMap<>(getPlayerAccounts(uuid)));
    }

    Map<String, PolyCoinEconomyAccount> getAccounts(UUID uuid, String currencyId) {
        if (!data.currencyData.currencies.containsKey(currencyId)) return Collections.emptyMap();
        return getPlayerAccounts(uuid).values().stream().filter(account -> account.usesCurrency(currencyId))
                .collect(TreeMap::new, (map, account) -> map.put(account.getId(), account), TreeMap::putAll);
    }

    DataResult<List<LeaderboardEntry>> getTopAccounts(String currencyId, int limit) {
        if (limit < 0) return DataResult.error(() -> "Limit must be non-negative");
        return data.getCurrency(currencyId).map(_ -> {
            if (limit == 0) return List.of();
            var entries = new PriorityQueue<>(Math.min(limit, 64), LEADERBOARD_ORDER.reversed());
            for (var playerAccounts : accounts.values()) {
                for (var account : playerAccounts.values()) {
                    if (!account.usesCurrency(currencyId)) continue;
                    var entry = new LeaderboardEntry(account.owner(), account.id(), account.name(), account.balance());
                    if (entries.size() < limit) entries.add(entry);
                    else if (LEADERBOARD_ORDER.compare(entry, entries.peek()) < 0) {
                        entries.remove();
                        entries.add(entry);
                    }
                }
            }
            var result = new ArrayList<>(entries);
            result.sort(LEADERBOARD_ORDER);
            return List.copyOf(result);
        });
    }

    DataResult<PolyCoinEconomyAccount> getAccount(UUID uuid, String accountId) {
        if (!EconomyValidation.validId(accountId)) return DataResult.error(() -> "Invalid account ID: " + accountId);
        var account = getPlayerAccounts(uuid).get(accountId);
        return account == null ? DataResult.error(() -> "Account not found: " + accountId) : DataResult.success(account);
    }

    DataResult<PolyCoinEconomyAccount> createAccount(UUID uuid, String accountId, String name, Item icon, String currencyId) {
        return PolyCoinEconomyAccount.create(data, accountId, currencyId, uuid, name, icon)
                .flatMap(account -> data.getCurrency(currencyId).map(_ -> account))
                .flatMap(account -> {
                    var playerAccounts = getPlayerAccounts(uuid);
                    if (playerAccounts.containsKey(accountId)) return DataResult.error(() -> "Account already exists: " + accountId);
                    playerAccounts.put(accountId, account);
                    data.setDirty();
                    EconomyLog.accountCreated(account);
                    return DataResult.success(account);
                });
    }

    DataResult<PolyCoinEconomyAccount> updateAccount(UUID uuid, String accountId, String name, Item icon, String currencyId) {
        return EconomyValidation.metadata(name, icon).flatMap(_ -> data.getCurrency(currencyId))
                .flatMap(_ -> getAccount(uuid, accountId)).flatMap(account -> {
                    if (!account.usesCurrency(currencyId)) {
                        if (account.balance().signum() != 0) return DataResult.error(() -> "An account balance must be zero before its currency can be changed");
                        if (isDefaultAccount(uuid, accountId)) return DataResult.error(() -> "Set another default account before changing this account's currency");
                        return PolyCoinEconomyAccount.create(data, accountId, currencyId, uuid, name, icon).map(replacement -> {
                            accounts.get(uuid).put(accountId, replacement);
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

    DataResult<PolyCoinEconomyAccount> deleteAccount(UUID uuid, String accountId) {
        return getAccount(uuid, accountId).flatMap(account -> {
            if (isDefaultAccount(uuid, accountId)) return DataResult.error(() -> "Cannot delete the default account. Set another default first.");
            accounts.get(uuid).remove(accountId);
            data.setDirty();
            EconomyLog.accountDeleted(account, "deleted");
            return DataResult.success(account);
        });
    }

    DataResult<BigInteger> transfer(UUID sourceUuid, String sourceAccountId, UUID targetUuid, String targetAccountId, BigInteger amount) {
        if (amount.signum() <= 0) return DataResult.error(() -> "Transfer amount must be positive");
        if (!EconomyValidation.validId(sourceAccountId) || !EconomyValidation.validId(targetAccountId)) {
            return DataResult.error(() -> "Invalid account ID");
        }
        if (sourceUuid.equals(targetUuid) && sourceAccountId.equals(targetAccountId)) {
            return DataResult.error(() -> "The source and target accounts must be different.");
        }
        return getAccount(sourceUuid, sourceAccountId).flatMap(source ->
                getAccount(targetUuid, targetAccountId).flatMap(target -> {
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
        for (var playerAccounts : accounts.values()) {
            for (var account : playerAccounts.values()) {
                if (account.usesCurrency(currencyId)) {
                    count++;
                    total = total.add(account.balance());
                }
            }
        }
        return new CurrencyStatistics(count, total);
    }

    int countAccounts(String currencyId) {
        int count = 0;
        for (var playerAccounts : accounts.values()) {
            for (var account : playerAccounts.values()) if (account.usesCurrency(currencyId)) count++;
        }
        return count;
    }

    int removeCurrency(String currencyId) {
        int removed = 0;
        for (var playerAccounts : accounts.values()) {
            var iterator = playerAccounts.values().iterator();
            while (iterator.hasNext()) {
                var account = iterator.next();
                if (account.usesCurrency(currencyId)) {
                    iterator.remove();
                    removed++;
                    EconomyLog.accountDeleted(account, "currency_deleted");
                }
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
        var playerAccounts = accounts.computeIfAbsent(uuid, _ -> new TreeMap<>());
        var selections = defaultAccountIds.computeIfAbsent(uuid, _ -> new HashMap<>());
        var selectionIterator = selections.entrySet().iterator();
        while (selectionIterator.hasNext()) {
            var entry = selectionIterator.next();
            if (!data.currencyData.currencies.containsKey(entry.getKey())) {
                selectionIterator.remove();
                data.setDirty();
                EconomyLog.defaultAccountChanged(uuid, entry.getKey(), entry.getValue(), null, "unknown_currency");
            }
        }

        for (var currency : data.currencyData.currencies.values()) {
            var currencyId = currency.getId();
            var accountId = selections.get(currencyId);
            var account = accountId == null ? null : playerAccounts.get(accountId);
            if (account != null && account.usesCurrency(currencyId)) continue;
            account = playerAccounts.values().stream().filter(candidate -> candidate.usesCurrency(currencyId)).findFirst().orElse(null);
            if (account == null) {
                if (accountId == null || !EconomyValidation.validId(accountId) || playerAccounts.containsKey(accountId)) {
                    var baseId = DEFAULT_ACCOUNT_ID + "_" + currencyId;
                    accountId = baseId;
                    for (int suffix = 1; playerAccounts.containsKey(accountId); suffix++) accountId = baseId + "_" + suffix;
                }
                account = PolyCoinEconomyAccount.defaultAccount(data, accountId, currency, uuid);
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
        return defaultAccountIds.get(uuid).containsValue(accountId);
    }

    DataResult<PolyCoinEconomyAccount> setDefaultAccount(UUID uuid, String accountId) {
        return getAccount(uuid, accountId).map(account -> {
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
