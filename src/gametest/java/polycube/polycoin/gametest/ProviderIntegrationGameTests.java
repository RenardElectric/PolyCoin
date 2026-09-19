package polycube.polycoin.gametest;

import com.mojang.authlib.GameProfile;
import eu.pb4.common.economy.api.CommonEconomy;
import eu.pb4.common.economy.api.EconomyCurrency;
import eu.pb4.common.economy.api.EconomyProvider;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import polycube.polycoin.PolyCoin;

import java.math.BigInteger;

public final class ProviderIntegrationGameTests {
    @GameTest
    public void modRegistersItsProviderAndCanonicalCurrencyWithCommonEconomy(GameTestHelper helper) {
        var server = helper.getLevel().getServer();

        helper.assertTrue(CommonEconomy.getProvider(PolyCoin.MOD_ID) == PolyCoin.INSTANCE,
                "Initialization must register the PolyCoin provider under its mod ID");
        helper.assertTrue(CommonEconomy.providers().contains(PolyCoin.INSTANCE),
                "The provider must be discoverable through the Common Economy registry");
        var currency = CommonEconomy.getCurrency(server, PolyCoin.id("polycoin"));
        helper.assertTrue(currency != null && currency.provider() == PolyCoin.INSTANCE,
                "The canonical currency must be discoverable through Common Economy");
        helper.succeed();
    }

    @GameTest
    public void providerLazilyCreatesAndReusesServerSavedData(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var first = PolyCoin.INSTANCE.getData(server);
        var second = PolyCoin.INSTANCE.getData(server);

        helper.assertTrue(first == second,
                "Provider access must reuse the server's SavedData instance");
        helper.assertTrue(first.isDirty(),
                "A newly created economy must be marked dirty for its first save");
        helper.succeed();
    }

    @GameTest
    public void providerCreatesDefaultsAndEnforcesOwnerAccess(GameTestHelper helper) {
        var player = GameTestSupport.player(helper);
        var server = helper.getLevel().getServer();
        var profile = player.getGameProfile();
        var currency = PolyCoin.INSTANCE.getCurrency(server, "polycoin");
        var accounts = PolyCoin.INSTANCE.getAccounts(server, profile, currency);

        helper.assertTrue(accounts.size() == 1 && currency != null,
                "Provider access must expose the canonical currency and initialize one account for it");
        var account = accounts.iterator().next();
        helper.assertTrue(PolyCoin.INSTANCE.defaultAccount(server, profile, currency).equals(account.id().getPath()),
                "The provider's default account must name the initialized account");
        var stranger = new GameProfile(java.util.UUID.randomUUID(), "Stranger");
        helper.assertTrue(PolyCoin.INSTANCE.getAccount(server, stranger, account.id().getPath()) == null,
                "The provider must not return an account to a non-owner");
        player.discard();
        helper.succeed();
    }

    @GameTest
    public void providerRejectsCurrenciesFromOtherNamespaces(GameTestHelper helper) {
        var player = GameTestSupport.player(helper);
        var server = helper.getLevel().getServer();
        var foreign = new ForeignCurrency();

        helper.assertTrue(PolyCoin.INSTANCE.getAccounts(server, player.getGameProfile(), foreign).isEmpty(),
                "Currency-filtered lookup must reject currencies owned by another namespace");
        helper.assertTrue(PolyCoin.INSTANCE.defaultAccount(server, player.getGameProfile(), foreign) == null,
                "A foreign currency must never resolve a PolyCoin default account");
        player.discard();
        helper.succeed();
    }

    private static final class ForeignCurrency implements EconomyCurrency {
        @Override
        public Component name() { return Component.literal("Foreign"); }

        @Override
        public Identifier id() { return Identifier.fromNamespaceAndPath("foreign", "credits"); }

        @Override
        public String formatValue(BigInteger value, boolean precise) { return value.toString(); }

        @Override
        public BigInteger parseValue(String value) { return new BigInteger(value); }

        @Override
        public EconomyProvider provider() { return PolyCoin.INSTANCE; }
    }
}
