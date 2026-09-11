package polycube.polycoin.util;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import polycube.polycoin.PolyCoin;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;

public final class Helpers {
    private Helpers() {}

    public static final Codec<BigInteger> BIG_INTEGER_CODEC =
            Codec.STRING.comapFlatMap(
                    value -> {
                        try {
                            return DataResult.success(new BigInteger(value));
                        } catch (NumberFormatException exception) {
                            return DataResult.error(() -> "Invalid BigInteger: " + value);
                        }
                    },
                    BigInteger::toString
            );

    public static boolean isPolyCoinIdentifier(Identifier id) {
        return PolyCoin.MOD_ID.equals(id.getNamespace());
    }

    public static Component playerNames(MinecraftServer server, Set<UUID> players) {
        var playerNames = players.stream().sorted().map(id -> Helpers.playerProfile(server, id)).toList();
        return playerNames(playerNames);
    }

    public static Component playerNames(Collection<NameAndId> players) {
        var result = Component.literal("");
        var orderedPlayers = players.stream().sorted(
                Comparator.comparing(NameAndId::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(NameAndId::name)
                        .thenComparing(NameAndId::id)
        ).toList();
        for (int index = 0; index < orderedPlayers.size(); index++) {
            if (index != 0) result.append(", ");
            result.append(orderedPlayers.get(index).name());
        }
        return result;
    }

    public static NameAndId playerProfile(MinecraftServer server, UUID player) {
        var onlinePlayer = server.getPlayerList().getPlayer(player);
        if (onlinePlayer != null) {
            return onlinePlayer.nameAndId();
        }

        return server.services().nameToIdCache().get(player)
                .orElse(new NameAndId(player, player.toString()));
    }
}
