package polycube.polycoin.util;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import eu.pb4.common.economy.api.EconomyAccount;
import eu.pb4.common.economy.api.EconomyCurrency;
import net.minecraft.resources.Identifier;
import polycube.polycoin.PolyCoin;

import java.math.BigInteger;

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
}
