package polycube.polycoin.economy;

import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;

import java.math.BigInteger;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class EconomyDomainGameTests {
    @GameTest
    public void freshEconomyCreatesCanonicalDefaultsForPlayers(GameTestHelper helper) {
        var data = new PolyCoinEconomyData();
        var owner = UUID.randomUUID();

        helper.assertTrue(data.getCurrencies().keySet().equals(java.util.Set.of("polycoin")),
                "A fresh economy must expose only the canonical PolyCoin currency");
        helper.assertTrue(data.getDefaultCurrency().equals("polycoin"),
                "PolyCoin must be the default currency");

        var accounts = data.getAccounts(owner);
        helper.assertTrue(accounts.size() == 1,
                "First access must create exactly one default account");
        var account = accounts.values().iterator().next();
        helper.assertTrue(account.getId().equals("main_account_polycoin"),
                "The initial account must use the stable per-currency ID");
        helper.assertTrue(account.balance().equals(BigInteger.valueOf(100_000L)),
                "The initial account must receive the configured 1000.00 balance");
        helper.assertTrue(account.owners().equals(java.util.Set.of(owner))
                        && data.getDefaultAccountId(owner, "polycoin").equals(account.getId()),
                "The automatic account must belong to and be selected for the requesting player");
        helper.succeed();
    }

    @GameTest
    public void monetaryParsingIsExactAndRejectsAmbiguousInput(GameTestHelper helper) {
        assertAmount(helper, "10", 1_000L);
        assertAmount(helper, "10.5", 1_050L);
        assertAmount(helper, ".50", 50L);
        assertAmount(helper, "+10.500", 1_050L);
        assertAmount(helper, "-0.01", -1L);

        for (var invalid : new String[]{"", "1e2", "NaN", "1,000", "1.234"}) {
            helper.assertTrue(PolyCoinEconomyCurrency.tryParseAmount(invalid).error().isPresent(),
                    "Monetary input must reject: " + invalid);
        }
        helper.assertTrue(PolyCoinEconomyCurrency.defaultCurrency()
                        .formatValue(BigInteger.valueOf(123_456L), false).equals("1234.56"),
                "Money formatting must retain exactly two decimal places");
        helper.succeed();
    }

    @GameTest
    public void currencyMutationsValidateEveryPersistedField(GameTestHelper helper) {
        var data = new PolyCoinEconomyData();
        helper.assertTrue(data.createCurrency("Upper", "Credits", "C", Items.EMERALD, BigInteger.ZERO).error().isPresent(),
                "Currency IDs must use namespace-free lowercase paths");
        helper.assertTrue(data.createCurrency("credits", " ", "C", Items.EMERALD, BigInteger.ZERO).error().isPresent(),
                "Currency names must not be blank");
        helper.assertTrue(data.createCurrency("credits", "Credits", " ", Items.EMERALD, BigInteger.ZERO).error().isPresent(),
                "Currency denominations must not be blank");
        helper.assertTrue(data.createCurrency("credits", "Credits", "C", Items.EMERALD, BigInteger.valueOf(-1)).error().isPresent(),
                "Starting balances must not be negative");

        helper.assertTrue(data.createCurrency("credits", "Credits", "C", Items.EMERALD, BigInteger.TEN).result().isPresent(),
                "A valid currency must be creatable");
        helper.assertTrue(data.createCurrency("credits", "Other", "O", Items.DIAMOND, BigInteger.ZERO).error().isPresent(),
                "Currency IDs must be unique");
        helper.succeed();
    }

    @GameTest
    public void newCurrenciesProvisionDefaultsWithoutRewritingExistingBalances(GameTestHelper helper) {
        var data = new PolyCoinEconomyData();
        var existingOwner = UUID.randomUUID();
        data.getAccounts(existingOwner);

        requireSuccess(data.createCurrency("gems", "Gems", "G", Items.EMERALD, BigInteger.valueOf(2_500L)));
        var existingAccount = data.getAccounts(existingOwner, "gems").values().iterator().next();
        helper.assertTrue(existingAccount.balance().equals(BigInteger.valueOf(2_500L)),
                "Existing owners must receive the new currency's configured starting balance");

        requireSuccess(data.updateCurrency("gems", "Gems", "◆", Items.DIAMOND, BigInteger.valueOf(9_999L)));
        helper.assertTrue(existingAccount.balance().equals(BigInteger.valueOf(2_500L)),
                "Changing a starting balance must not rewrite an existing account");
        var laterOwner = UUID.randomUUID();
        var laterAccount = data.getAccounts(laterOwner, "gems").values().iterator().next();
        helper.assertTrue(laterAccount.balance().equals(BigInteger.valueOf(9_999L)),
                "Players initialized later must receive the updated starting balance");
        helper.assertTrue(data.getDefaultAccountId(existingOwner, "gems").equals(existingAccount.getId())
                        && data.getDefaultAccountId(laterOwner, "gems").equals(laterAccount.getId()),
                "Each owner must receive a valid default selection for every currency");
        helper.succeed();
    }

    @GameTest
    public void currencyDeletionProtectsTheDefaultAndRemovesUnusedCurrencies(GameTestHelper helper) {
        var data = new PolyCoinEconomyData();
        requireSuccess(data.createCurrency("temporary", "Temporary", "T", Items.PAPER, BigInteger.ZERO));

        helper.assertTrue(data.deleteCurrency("polycoin").error().isPresent(),
                "The active default currency must not be deletable");
        var deleted = requireSuccess(data.deleteCurrency("temporary"));
        helper.assertTrue(deleted.deletedAccounts() == 0 && data.getCurrency("temporary").error().isPresent(),
                "Deleting an unused non-default currency must remove it completely");
        helper.succeed();
    }

    @GameTest
    public void importedDatasetsRejectBrokenCrossReferences(GameTestHelper helper) {
        var source = new PolyCoinEconomyData();
        var owner = UUID.randomUUID();
        source.getAccounts(owner);
        var currencies = source.getCurrencies();
        var accounts = Map.copyOf(source.accountData.accounts);
        var defaults = copyDefaults(source);

        helper.assertTrue(PolyCoinEconomyData.create(currencies, "polycoin", accounts, defaults).result().isPresent(),
                "A complete exported dataset must validate");
        helper.assertTrue(PolyCoinEconomyData.create(Map.of(), "polycoin", Map.of(), Map.of()).error().isPresent(),
                "A dataset must contain at least one currency");
        helper.assertTrue(PolyCoinEconomyData.create(currencies, "missing", accounts, defaults).error().isPresent(),
                "A dataset's default currency must exist");
        helper.assertTrue(PolyCoinEconomyData.create(Map.of("wrong", currencies.get("polycoin")), "wrong", Map.of(), Map.of())
                        .error().isPresent(),
                "Persisted currency keys must match their value IDs");
        helper.assertTrue(PolyCoinEconomyData.create(currencies, "polycoin", accounts, Map.of()).error().isPresent(),
                "Every account owner must have complete default selections");
        helper.succeed();
    }

    @GameTest
    public void economyCodecRoundTripsCurrenciesAccountsOwnersAndDefaults(GameTestHelper helper) {
        var original = new PolyCoinEconomyData();
        var firstOwner = UUID.randomUUID();
        var secondOwner = UUID.randomUUID();
        requireSuccess(original.createCurrency("gems", "Gems", "◆", Items.EMERALD, BigInteger.valueOf(500L)));
        original.getAccounts(firstOwner);
        var shared = requireSuccess(original.createAccount(Set.of(firstOwner), "shared", "Shared", Items.CHEST, "gems"));
        requireSuccess(original.addAccountOwners("shared", java.util.List.of(
                new net.minecraft.server.players.NameAndId(secondOwner, "Second")
        )));
        requireSuccess(shared.trySetBalance(BigInteger.valueOf(12_345L)));
        requireSuccess(original.setDefaultAccount(firstOwner, "shared"));

        var encoded = PolyCoinEconomyData.CODEC.encodeStart(JsonOps.INSTANCE, original)
                .getOrThrow(error -> new AssertionError("Could not encode economy: " + error));
        var decoded = PolyCoinEconomyData.CODEC.parse(JsonOps.INSTANCE, encoded)
                .getOrThrow(error -> new AssertionError("Could not decode economy: " + error));

        var decodedShared = requireSuccess(decoded.getAccount("shared"));
        helper.assertTrue(decodedShared.balance().equals(BigInteger.valueOf(12_345L))
                        && decodedShared.owners().equals(Set.of(firstOwner, secondOwner)),
                "Balances and all owners must survive persistence");
        helper.assertTrue(decoded.getCurrency("gems").result().isPresent()
                        && decoded.getDefaultAccountId(firstOwner, "gems").equals("shared"),
                "Currencies and per-owner defaults must survive persistence");
        helper.succeed();
    }

    @GameTest
    public void economyCodecRejectsCorruptedDatasetsInsteadOfKeepingPartials(GameTestHelper helper) {
        var original = new PolyCoinEconomyData();
        var encoded = PolyCoinEconomyData.CODEC.encodeStart(JsonOps.INSTANCE, original)
                .getOrThrow(error -> new AssertionError("Could not encode economy: " + error));
        encoded.getAsJsonObject().addProperty("default_currency", "missing");

        helper.assertTrue(PolyCoinEconomyData.CODEC.parse(JsonOps.INSTANCE, encoded).error().isPresent(),
                "A corrupted cross-reference must reject the entire saved dataset");
        helper.succeed();
    }

    private static void assertAmount(GameTestHelper helper, String input, long expected) {
        var parsed = requireSuccess(PolyCoinEconomyCurrency.tryParseAmount(input));
        helper.assertTrue(parsed.equals(BigInteger.valueOf(expected)),
                input + " must parse to the exact smallest-unit value " + expected);
    }

    private static Map<UUID, Map<String, String>> copyDefaults(PolyCoinEconomyData data) {
        var result = new java.util.TreeMap<UUID, Map<String, String>>();
        data.accountData.defaultAccountIds.forEach((owner, defaults) -> result.put(owner, Map.copyOf(defaults)));
        return result;
    }

    private static <T> T requireSuccess(com.mojang.serialization.DataResult<T> result) {
        return result.getOrThrow(error -> new AssertionError(error));
    }
}
