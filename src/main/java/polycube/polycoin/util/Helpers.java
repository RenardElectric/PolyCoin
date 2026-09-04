package polycube.polycoin.util;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import eu.pb4.common.economy.api.EconomyAccount;
import eu.pb4.common.economy.api.EconomyCurrency;

import java.math.BigInteger;

public final class Helpers {
    private Helpers() {}

    public static boolean isSameCurrency(EconomyCurrency currency1, EconomyCurrency currency2) {
        return currency1.id().equals(currency2.id()) && currency1.provider().id().equals(currency2.provider().id());
    }

    public static boolean isSameAccount(EconomyAccount account1, EconomyAccount account2) {
        return account1.id().equals(account2.id()) && account1.provider().id().equals(account2.provider().id());
    }

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
}
