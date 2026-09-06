package polycube.polycoin.economy;

import com.mojang.authlib.GameProfile;
import eu.pb4.common.economy.api.EconomyAccount;
import eu.pb4.common.economy.api.EconomyCurrency;
import eu.pb4.common.economy.api.EconomyProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;
import polycube.polycoin.util.Helpers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class PolyCoinEconomyProvider implements EconomyProvider {

    @Override
    public Component name() {
        return Component.literal("PolyCoin").withStyle(ChatFormatting.GOLD);
    }

    @Override
    public ItemStack icon() {
        return Items.NETHER_STAR.getDefaultInstance();
    }

    @Override
    public @Nullable EconomyAccount getAccount(MinecraftServer server, GameProfile profile, String accountId) {
        return getData(server).getAccount(profile.id(), accountId).mapOrElse(
                account -> account,
                _ -> null
        );
    }

    @Override
    public Collection<EconomyAccount> getAccounts(MinecraftServer server, GameProfile profile) {
        return List.copyOf(getData(server).getAccounts(profile.id()).values());
    }

    @Override
    public Collection<EconomyAccount> getAccounts(MinecraftServer server, GameProfile profile, EconomyCurrency currency) {
        if (!Helpers.isPolyCoinIdentifier(currency.id())) return List.of();
        var data = getData(server);
        synchronized (data) {
            return new ArrayList<>(data.getAccounts(profile.id(), currency.id().getPath()).values());
        }
    }

    @Override
    public @Nullable EconomyCurrency getCurrency(MinecraftServer server, String currencyId) {
        return getData(server).getCurrency(currencyId).mapOrElse(
                currency -> currency,
                _ -> null
        );
    }

    @Override
    public Collection<EconomyCurrency> getCurrencies(MinecraftServer server) {
        return List.copyOf(getData(server).getCurrencies().values());
    }

    @Override
    public @Nullable String defaultAccount(MinecraftServer server, GameProfile profile, EconomyCurrency currency) {
        if (!Helpers.isPolyCoinIdentifier(currency.id())) return null;
        var data = getData(server);
        synchronized (data) {
            return data.defaultAccount(profile.id(), currency.id().getPath());
        }
    }

    public PolyCoinEconomyData getData(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(PolyCoinEconomyData.TYPE);
    }
}
