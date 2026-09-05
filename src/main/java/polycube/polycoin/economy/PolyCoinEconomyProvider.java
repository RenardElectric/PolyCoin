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

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

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
        if (server == null) return null;
        if (profile == null) return null;
        if (accountId == null) return null;
        return getData(server).getAccount(profile.id(), accountId).mapOrElse(
                account -> account,
                _ -> null
        );
    }

    @Override
    public Collection<EconomyAccount> getAccounts(MinecraftServer server, GameProfile profile) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(profile, "profile");
        return List.copyOf(getData(server).getAccounts(profile.id()).values());
    }

    @Override
    public @Nullable EconomyCurrency getCurrency(MinecraftServer server, String currencyId) {
        if (server == null) return null;
        if (currencyId == null) return null;
        return getData(server).getCurrency(currencyId).mapOrElse(
                currency -> currency,
                _ -> null
        );
    }

    @Override
    public Collection<EconomyCurrency> getCurrencies(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        return List.copyOf(getData(server).getCurrencies().values());
    }

    @Override
    public @Nullable String defaultAccount(MinecraftServer server, GameProfile profile, EconomyCurrency currency) {
        if (server == null) return null;
        if (profile == null) return null;
        if (currency == null) return null;
        if (!(currency instanceof PolyCoinEconomyCurrency polyCoinEconomyCurrency)) return null;
        return getData(server).defaultAccount(profile.id(), polyCoinEconomyCurrency.getId());
    }

    public PolyCoinEconomyData getData(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(PolyCoinEconomyData.TYPE);
    }
}
