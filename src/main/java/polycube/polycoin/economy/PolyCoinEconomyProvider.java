package polycube.polycoin.EconomyProvider;

import com.mojang.authlib.GameProfile;
import eu.pb4.common.economy.api.EconomyAccount;
import eu.pb4.common.economy.api.EconomyCurrency;
import eu.pb4.common.economy.api.EconomyProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.SavedData;
import org.jspecify.annotations.Nullable;
import polycube.polycoin.PolyCoin;

import java.util.*;

public class PolyCoinEconomyProvider extends SavedData implements EconomyProvider {

    private final Map<MinecraftServer, PolyCoinEconomyData> serverData = new HashMap<>();

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
        return getData(server).getAccount(profile, accountId);
    }

    @Override
    public Collection<EconomyAccount> getAccounts(MinecraftServer server, GameProfile profile) {
        return List.copyOf(getData(server).getAccounts(profile).values());
    }

    @Override
    public @Nullable EconomyCurrency getCurrency(MinecraftServer server, String currencyId) {
        return getData(server).getCurrency(currencyId);
    }

    @Override
    public Collection<EconomyCurrency> getCurrencies(MinecraftServer server) {
        return List.copyOf(getData(server).getCurrencies().values());
    }

    @Override
    public @Nullable String defaultAccount(MinecraftServer server, GameProfile profile, EconomyCurrency currency) {
        return getData(server).defaultAccount(profile, currency);
    }

    public PolyCoinEconomyData getData(MinecraftServer server) {
        return serverData.get(server);
    }

    public void load(MinecraftServer server) {
        if (serverData.containsKey(server)) {
            PolyCoin.LOGGER.warn("PolyCoin economy provider already loaded for this server, skipping load");
            return;
        }
        PolyCoin.LOGGER.debug("Loading PolyCoin economy provider");
        var polyCoinEconomyProvider = server.getDataStorage().computeIfAbsent(PolyCoinEconomyData.TYPE);
        polyCoinEconomyProvider.server = server;
        serverData.put(server, polyCoinEconomyProvider);
        PolyCoin.LOGGER.debug("Loaded PolyCoin economy provider");
    }

    public void unload(MinecraftServer server) {
        if (!serverData.containsKey(server)) {
            PolyCoin.LOGGER.warn("PolyCoin economy provider not loaded for this server, skipping unload");
            return;
        }
        PolyCoin.LOGGER.debug("Unloading PolyCoin economy provider");
        serverData.remove(server);
        PolyCoin.LOGGER.debug("Unloaded PolyCoin economy provider");
    }
}
