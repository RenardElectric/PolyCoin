package polycube.polycoin.economy;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.Util;
import net.minecraft.world.item.Items;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class EconomyAccountGameTests {
    @GameTest
    public void accountCreationEnforcesIdentityOwnershipAndUniqueness(GameTestHelper helper) {
        var data = new PolyCoinEconomyData();
        var owner = UUID.randomUUID();
        var stranger = UUID.randomUUID();
        var created = success(data.createAccount(Set.of(owner), "savings", "Savings", Items.CHEST, "polycoin"));

        helper.assertTrue(created.balance().equals(BigInteger.ZERO),
                "Manually created accounts must start empty");
        helper.assertTrue(data.getAccount(owner, "savings").result().orElseThrow() == created,
                "An owner must be able to retrieve their account");
        helper.assertTrue(data.getAccount(stranger, "savings").error().isPresent(),
                "Account lookup must not disclose another player's account");
        helper.assertTrue(data.createAccount(Set.of(owner), "savings", "Duplicate", Items.BARREL, "polycoin")
                        .error().isPresent(),
                "Account IDs must remain globally unique");
        helper.assertTrue(data.createAccount(Set.of(), "orphan", "Orphan", Items.CHEST, "polycoin")
                        .error().isPresent(),
                "Accounts without owners must be rejected");
        helper.succeed();
    }

    @GameTest
    public void sharedAccountOwnershipKeepsIndexesAndCommonApiOwnerConsistent(GameTestHelper helper) {
        var data = new PolyCoinEconomyData();
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        var shared = success(data.createAccount(Set.of(first), "shared", "Shared", Items.CHEST, "polycoin"));

        success(data.addAccountOwners("shared", List.of(
                new NameAndId(second, "Second"),
                new NameAndId(second, "Second duplicate")
        )));
        helper.assertTrue(shared.owners().equals(Set.of(first, second)) && shared.owner().equals(Util.NIL_UUID),
                "A shared Common Economy account must expose all owners and no fabricated primary owner");
        helper.assertTrue(data.getAccounts(second).containsKey("shared"),
                "Adding an owner must update owner-based account lookup");

        success(data.removeAccountOwners("shared", List.of(new NameAndId(second, "Second"))));
        helper.assertTrue(!data.getAccounts(second).containsKey("shared") && shared.owner().equals(first),
                "Removing an owner must remove the derived lookup entry and restore the sole owner");
        helper.assertTrue(data.removeAccountOwners("shared", List.of(new NameAndId(first, "First"))).error().isPresent(),
                "The final owner must never be removable");
        helper.succeed();
    }

    @GameTest
    public void defaultSelectionProtectsAccountsButAllowsReplacingAndDeletingOldDefaults(GameTestHelper helper) {
        var data = new PolyCoinEconomyData();
        var owner = UUID.randomUUID();
        var originalId = data.getDefaultAccountId(owner, "polycoin");
        success(data.createAccount(Set.of(owner), "alternate", "Alternate", Items.BARREL, "polycoin"));

        success(data.setDefaultAccount(owner, "alternate"));
        helper.assertTrue(data.getDefaultAccountId(owner, "polycoin").equals("alternate"),
                "Selecting an owned account must replace that currency's default");
        helper.assertTrue(data.deleteAccount("alternate").error().isPresent(),
                "A selected default account must not be deletable");
        helper.assertTrue(data.removeAccountOwners("alternate", List.of(new NameAndId(owner, "Owner"))).error().isPresent(),
                "An owner must not be removed from their selected default account");
        helper.assertTrue(data.deleteAccount(originalId).result().isPresent()
                        && data.getAccount(originalId).error().isPresent(),
                "The old default must become deletable after replacement");
        helper.succeed();
    }

    @GameTest
    public void balanceTransactionsReportExactSnapshotsAndDoNotMutateDuringChecks(GameTestHelper helper) {
        var data = new PolyCoinEconomyData();
        var owner = UUID.randomUUID();
        var account = data.getAccounts(owner).values().iterator().next();

        var preview = account.canDecreaseBalance(BigInteger.valueOf(2_500L));
        helper.assertTrue(preview.isSuccessful()
                        && preview.previousBalance().equals(BigInteger.valueOf(100_000L))
                        && preview.finalBalance().equals(BigInteger.valueOf(97_500L))
                        && preview.transactionAmount().equals(BigInteger.valueOf(-2_500L)),
                "A debit preview must report exact before, after, and signed delta values");
        helper.assertTrue(account.balance().equals(BigInteger.valueOf(100_000L)),
                "A balance check must not mutate the account");

        var applied = account.decreaseBalance(BigInteger.valueOf(2_500L));
        helper.assertTrue(applied.isSuccessful() && account.balance().equals(BigInteger.valueOf(97_500L)),
                "Applying a valid debit must commit exactly once");
        helper.assertTrue(account.decreaseBalance(BigInteger.valueOf(200_000L)).isFailure()
                        && account.balance().equals(BigInteger.valueOf(97_500L)),
                "Insufficient-funds debits must be atomic");
        helper.assertTrue(account.increaseBalance(BigInteger.valueOf(-1L)).isFailure()
                        && account.balance().equals(BigInteger.valueOf(97_500L)),
                "Negative Common Economy transactions must fail without mutation");
        helper.succeed();
    }

    @GameTest
    public void transfersAreExactAndEveryFailureLeavesBothAccountsUntouched(GameTestHelper helper) {
        var data = new PolyCoinEconomyData();
        var owner = UUID.randomUUID();
        var source = data.getAccounts(owner).values().iterator().next();
        var target = success(data.createAccount(Set.of(owner), "target", "Target", Items.CHEST, "polycoin"));

        success(data.transfer(source.getId(), target.getId(), BigInteger.valueOf(12_345L)));
        helper.assertTrue(source.balance().equals(BigInteger.valueOf(87_655L))
                        && target.balance().equals(BigInteger.valueOf(12_345L)),
                "A transfer must debit and credit the exact same amount");

        var sourceBefore = source.balance();
        var targetBefore = target.balance();
        helper.assertTrue(data.transfer(source.getId(), source.getId(), BigInteger.ONE).error().isPresent(),
                "Self-transfers must be rejected");
        helper.assertTrue(data.transfer(source.getId(), target.getId(), BigInteger.ZERO).error().isPresent(),
                "Non-positive transfers must be rejected");
        helper.assertTrue(data.transfer(source.getId(), target.getId(), BigInteger.valueOf(999_999L)).error().isPresent(),
                "Transfers exceeding the source balance must be rejected");
        helper.assertTrue(source.balance().equals(sourceBefore) && target.balance().equals(targetBefore),
                "Every rejected transfer must leave both accounts unchanged");
        helper.succeed();
    }

    @GameTest
    public void crossCurrencyTransfersAndUnsafeCurrencyChangesFailAtomically(GameTestHelper helper) {
        var data = new PolyCoinEconomyData();
        var owner = UUID.randomUUID();
        var polycoin = data.getAccounts(owner).values().iterator().next();
        success(data.createCurrency("gems", "Gems", "G", Items.EMERALD, BigInteger.ZERO));
        var gems = data.getAccounts(owner, "gems").values().iterator().next();
        var movable = success(data.createAccount(Set.of(owner), "movable", "Movable", Items.CHEST, "polycoin"));
        success(movable.trySetBalance(BigInteger.ONE));

        helper.assertTrue(data.transfer(polycoin.getId(), gems.getId(), BigInteger.ONE).error().isPresent(),
                "Accounts using different currencies must not transfer");
        helper.assertTrue(data.updateAccount("movable", "Movable", Items.CHEST, "gems").error().isPresent(),
                "An account with funds must not change currencies");
        success(movable.trySetBalance(BigInteger.ZERO));
        var updated = success(data.updateAccount("movable", "Moved", Items.BARREL, "gems"));
        helper.assertTrue(updated.currencyId().equals("gems") && updated.displayName().equals("Moved"),
                "A zero-balance non-default account may change currency atomically");
        helper.succeed();
    }

    @GameTest
    public void deletedAndReplacedAccountsInvalidateStaleReferences(GameTestHelper helper) {
        var data = new PolyCoinEconomyData();
        var owner = UUID.randomUUID();
        var stale = success(data.createAccount(Set.of(owner), "temporary", "Old", Items.CHEST, "polycoin"));
        success(data.deleteAccount("temporary"));
        var replacement = success(data.createAccount(Set.of(owner), "temporary", "New", Items.BARREL, "polycoin"));

        helper.assertTrue(stale.increaseBalance(BigInteger.ONE).isFailure()
                        && stale.trySetBalance(BigInteger.ONE).error().isPresent(),
                "A deleted object must stay invalid even after its ID is reused");
        helper.assertTrue(replacement.balance().equals(BigInteger.ZERO),
                "A stale reference must not mutate the replacement account");
        helper.succeed();
    }

    @GameTest
    public void leaderboardAndStatisticsAreExactBoundedAndDeterministic(GameTestHelper helper) {
        var data = new PolyCoinEconomyData();
        var owner = UUID.randomUUID();
        var main = data.getAccounts(owner).values().iterator().next();
        var alpha = success(data.createAccount(Set.of(owner), "alpha", "Alpha", Items.CHEST, "polycoin"));
        var beta = success(data.createAccount(Set.of(owner), "beta", "Beta", Items.BARREL, "polycoin"));
        success(main.trySetBalance(BigInteger.valueOf(100L)));
        success(alpha.trySetBalance(BigInteger.valueOf(500L)));
        success(beta.trySetBalance(BigInteger.valueOf(500L)));

        var top = success(data.getTopAccounts("polycoin", 2));
        helper.assertTrue(top.size() == 2
                        && top.get(0).accountId().getPath().equals("alpha")
                        && top.get(1).accountId().getPath().equals("beta"),
                "Tied leaderboard entries must use account ID as a stable tiebreaker");
        var statistics = success(data.getCurrencyStatistics("polycoin"));
        helper.assertTrue(statistics.accountCount() == 3
                        && statistics.totalBalance().equals(BigInteger.valueOf(1_100L)),
                "Currency statistics must exactly cover every account and balance");
        helper.assertTrue(success(data.getTopAccounts("polycoin", 0)).isEmpty()
                        && data.getTopAccounts("polycoin", -1).error().isPresent(),
                "Leaderboard limits must be bounded without accidental allocation or results");
        helper.succeed();
    }

    @GameTest
    public void concurrentBalanceCreditsAreSerializedWithoutLostUpdates(GameTestHelper helper) {
        var data = new PolyCoinEconomyData();
        var account = data.getAccounts(UUID.randomUUID()).values().iterator().next();
        success(account.trySetBalance(BigInteger.ZERO));
        var failure = new AtomicReference<Throwable>();
        var threads = new java.util.ArrayList<Thread>();

        for (int worker = 0; worker < 4; worker++) {
            var thread = new Thread(() -> {
                try {
                    for (int operation = 0; operation < 5; operation++) {
                        if (account.increaseBalance(BigInteger.valueOf(5L)).isFailure()) {
                            throw new AssertionError("Concurrent credit failed");
                        }
                    }
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                }
            }, "polycoin-test-credit-" + worker);
            threads.add(thread);
            thread.start();
        }
        for (var thread : threads) {
            try {
                thread.join(5_000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                helper.fail("Interrupted while awaiting concurrent economy operations");
            }
            helper.assertTrue(!thread.isAlive(), "Concurrent economy operations must complete without deadlock");
        }
        helper.assertTrue(failure.get() == null,
                "Concurrent economy operations must not fail: " + failure.get());
        helper.assertTrue(account.balance().equals(BigInteger.valueOf(100L)),
                "Every concurrent credit must be preserved exactly once");
        helper.succeed();
    }

    private static <T> T success(com.mojang.serialization.DataResult<T> result) {
        return result.getOrThrow(error -> new AssertionError(error));
    }
}
