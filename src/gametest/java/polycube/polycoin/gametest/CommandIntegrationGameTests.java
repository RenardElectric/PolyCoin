package polycube.polycoin.gametest;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import polycube.polycoin.PolyCoin;
import polycube.polycoin.commands.commandArguments.PolyCoinIdentifierArgument;

import java.math.BigInteger;

public final class CommandIntegrationGameTests {
    @GameTest
    public void publicCommandSurfaceAndLeaderboardAliasAreRegistered(GameTestHelper helper)
            throws CommandSyntaxException {
        var player = GameTestSupport.player(helper);

        helper.assertTrue(GameTestSupport.execute(helper, player, "polycoin") == 1,
                "The base PolyCoin command must be registered");
        helper.assertTrue(GameTestSupport.execute(helper, player, "polycoin account list") == 1,
                "Players must be able to list their accounts");
        helper.assertTrue(GameTestSupport.execute(helper, player, "polycoin balance") == 1,
                "Players must be able to inspect their balance");
        helper.assertTrue(GameTestSupport.execute(helper, player, "polycoin currency list") == 1,
                "Players must be able to list currencies");
        helper.assertTrue(GameTestSupport.execute(helper, player, "polycoin balancetop") == 1
                        && GameTestSupport.execute(helper, player, "polycoin baltop 1") == 1,
                "Both leaderboard command names must execute");
        player.discard();
        helper.succeed();
    }

    @GameTest
    public void administrativeBranchesAreUnavailableToOrdinaryPlayers(GameTestHelper helper) {
        var player = GameTestSupport.player(helper);

        assertRejected(helper, player, "polycoin balance set 1.00",
                "Ordinary players must not set balances");
        assertRejected(helper, player, "polycoin currency create credits Credits C minecraft:emerald 0.00",
                "Ordinary players must not create currencies");
        assertRejected(helper, player, "polycoin as " + player.getGameProfile().name() + " balance",
                "Ordinary players must not act as another profile");
        assertRejected(helper, player, "polycoin pnj",
                "Ordinary players must not spawn the leaderboard NPC");
        player.discard();
        helper.succeed();
    }

    @GameTest
    public void accountCommandsCreateModifySelectAndDeleteWithoutBypassingRules(GameTestHelper helper)
            throws CommandSyntaxException {
        var player = GameTestSupport.player(helper);
        var data = PolyCoin.INSTANCE.getData(helper.getLevel().getServer());
        var savingsId = uniqueId("savings", player);

        helper.assertTrue(GameTestSupport.execute(helper, player,
                        "polycoin account create " + savingsId + " Savings minecraft:chest") == 1,
                "Players must be able to create an account");
        helper.assertTrue(GameTestSupport.execute(helper, player,
                        "polycoin account modify " + savingsId + " name Reserve") == 1,
                "Players must be able to rename an owned account");
        helper.assertTrue(GameTestSupport.execute(helper, player,
                        "polycoin account default " + savingsId) == 1,
                "Players must be able to select an owned default account");

        var savings = data.getAccount(player.getUUID(), savingsId).result().orElseThrow();
        helper.assertTrue(savings.displayName().equals("Reserve")
                        && data.getDefaultAccountId(player.getUUID(), "polycoin").equals(savingsId),
                "Account commands must commit metadata and default selection changes");
        assertRejected(helper, player, "polycoin account delete " + savingsId + " confirm",
                "The command must refuse to delete a selected default account");

        var original = data.getAccounts(player.getUUID()).keySet().stream()
                .filter(id -> !id.equals(savingsId))
                .filter(id -> data.getAccount(id).result().orElseThrow().currencyId().equals("polycoin"))
                .findFirst().orElseThrow();
        helper.assertTrue(GameTestSupport.execute(helper, player,
                        "polycoin account default " + original) == 1
                        && GameTestSupport.execute(helper, player,
                        "polycoin account delete " + savingsId + " confirm") == 1,
                "Replacing the default must make the old selection deletable");
        player.discard();
        helper.succeed();
    }

    @GameTest
    public void administratorBalanceCommandsApplyExactAmountsAndRejectOverdrafts(GameTestHelper helper)
            throws CommandSyntaxException {
        var player = GameTestSupport.player(helper);
        var data = PolyCoin.INSTANCE.getData(helper.getLevel().getServer());

        helper.assertTrue(GameTestSupport.executeAsAdmin(helper, player, "polycoin balance set 25.00") == 1
                        && GameTestSupport.executeAsAdmin(helper, player, "polycoin balance add 2.50") == 1
                        && GameTestSupport.executeAsAdmin(helper, player, "polycoin balance remove 1.25") == 1,
                "Administrative balance mutations must execute");
        var defaultId = data.getDefaultAccountId(player.getUUID(), "polycoin");
        var account = data.getAccount(player.getUUID(), defaultId).result().orElseThrow();
        helper.assertTrue(account.balance().equals(BigInteger.valueOf(2_625L)),
                "Set, add, and remove commands must preserve exact two-decimal arithmetic");
        helper.assertTrue(GameTestSupport.executeAsAdmin(helper, player, "polycoin balance remove 100.00") == 0
                        && account.balance().equals(BigInteger.valueOf(2_625L)),
                "An overdraft command must fail without mutating the balance");
        player.discard();
        helper.succeed();
    }

    @GameTest
    public void administratorCurrencyCommandsExposeSafeQueriesAndRejectDuplicateCreation(GameTestHelper helper)
            throws CommandSyntaxException {
        var player = GameTestSupport.player(helper);
        var data = PolyCoin.INSTANCE.getData(helper.getLevel().getServer());
        var currency = data.getCurrency("polycoin").result().orElseThrow();

        helper.assertTrue(GameTestSupport.executeAsAdmin(helper, player,
                        "polycoin currency info polycoin") == 1,
                "Administrators must be able to inspect a currency");
        helper.assertTrue(GameTestSupport.executeAsAdmin(helper, player,
                        "polycoin currency modify polycoin icon minecraft:sunflower") == 1,
                "A safe administrative metadata update must execute");
        assertAdminRejected(helper, player,
                "polycoin currency create polycoin Duplicate D minecraft:diamond 0.00",
                "The command must reject creation of an existing currency ID");
        helper.assertTrue(data.getCurrency("polycoin").result().orElseThrow() == currency,
                "A rejected duplicate creation must not replace the registered currency");
        player.discard();
        helper.succeed();
    }

    @GameTest
    public void paymentRejectsSelfAndAdministrativeActingUsesTheTargetProfile(GameTestHelper helper)
            throws CommandSyntaxException {
        var player = GameTestSupport.player(helper);

        helper.assertTrue(GameTestSupport.executeAsAdmin(helper, player,
                        "polycoin pay @s 1.00") == 0,
                "The pay command must reject paying the same player");
        helper.assertTrue(GameTestSupport.executeAsAdmin(helper, player,
                        "polycoin as " + player.getGameProfile().name() + " balance") == 1,
                "The administrative acting command must execute a player command for one profile");
        player.discard();
        helper.succeed();
    }

    @GameTest
    public void paymentMovesMoneyBetweenTheSelectedPlayersDefaults(GameTestHelper helper)
            throws CommandSyntaxException {
        var sender = GameTestSupport.player(helper);
        var target = GameTestSupport.player(helper);
        var targetTag = "polycoin_target_" + target.getUUID().toString().replace("-", "");
        target.addTag(targetTag);
        var data = PolyCoin.INSTANCE.getData(helper.getLevel().getServer());
        var senderAccount = data.getAccount(sender.getUUID(), data.getDefaultAccountId(sender.getUUID(), "polycoin"))
                .result().orElseThrow();
        var targetAccount = data.getAccount(target.getUUID(), data.getDefaultAccountId(target.getUUID(), "polycoin"))
                .result().orElseThrow();
        var senderBefore = senderAccount.balance();
        var targetBefore = targetAccount.balance();

        helper.assertTrue(GameTestSupport.executeAsAdmin(helper, sender,
                        "polycoin pay @a[tag=" + targetTag + ",limit=1] 12.34") == 1,
                "A payment to one selected online player must succeed");
        helper.assertTrue(senderAccount.balance().equals(senderBefore.subtract(BigInteger.valueOf(1_234L)))
                        && targetAccount.balance().equals(targetBefore.add(BigInteger.valueOf(1_234L))),
                "Payment must debit and credit the exact requested amount");
        sender.discard();
        target.discard();
        helper.succeed();
    }

    @GameTest
    public void administratorCanSpawnTheLeaderboardNpc(GameTestHelper helper)
            throws CommandSyntaxException {
        var player = GameTestSupport.player(helper);
        var commands = new String[]{
                "polycoin pnj",
                "polycoin pnj polycoin",
                "polycoin pnj 1",
                "polycoin pnj 1 polycoin",
                "polycoin pnj 1 polycoin ~ ~ ~ 0 0"
        };
        for (var command : commands) {
            helper.assertTrue(GameTestSupport.executeAsAdmin(helper, player, command) == 1,
                    "The administrator NPC command must support its documented forms: " + command);
        }
        helper.assertTrue(helper.getEntities(EntityTypes.MANNEQUIN).size() == commands.length
                        && helper.getEntities(EntityTypes.TEXT_DISPLAY).size() == commands.length,
                "Each NPC command must materialize one mannequin with one visible label");
        player.discard();
        helper.succeed();
    }

    @GameTest
    public void identifiersAcceptLocalAndQualifiedPolyCoinPathsOnly(GameTestHelper helper) {
        helper.assertTrue(PolyCoinIdentifierArgument.parse("savings").equals(PolyCoin.id("savings")),
                "A local economy ID must resolve inside the PolyCoin namespace");
        helper.assertTrue(PolyCoinIdentifierArgument.parse("polycoin:savings").equals(PolyCoin.id("savings")),
                "A qualified PolyCoin ID must be accepted");
        helper.assertTrue(PolyCoinIdentifierArgument.parse("other:savings") == null
                        && PolyCoinIdentifierArgument.parse("Invalid ID") == null,
                "Foreign namespaces and invalid resource paths must be rejected");
        helper.succeed();
    }

    private static void assertRejected(
            GameTestHelper helper, net.minecraft.server.level.ServerPlayer player,
            String command, String message
    ) {
        try {
            helper.assertTrue(GameTestSupport.execute(helper, player, command) == 0, message);
        } catch (CommandSyntaxException expected) {
            // A permission-pruned Brigadier branch is also a correct rejection.
        }
    }

    private static void assertAdminRejected(
            GameTestHelper helper, net.minecraft.server.level.ServerPlayer player,
            String command, String message
    ) {
        try {
            helper.assertTrue(GameTestSupport.executeAsAdmin(helper, player, command) == 0, message);
        } catch (CommandSyntaxException expected) {
            // Domain validation failures are surfaced as command syntax failures.
        }
    }

    private static String uniqueId(String prefix, net.minecraft.server.level.ServerPlayer player) {
        return prefix + "_" + player.getUUID().toString().replace("-", "");
    }
}
