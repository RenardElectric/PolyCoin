package polycube.polycoin.economy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import polycube.polycoin.util.Helpers;

import java.math.BigInteger;
import java.util.regex.Pattern;

/// Validation shared by persisted records and runtime mutations. IDs are namespace-free paths.
final class EconomyValidation {
    private static final int MAX_ID_LENGTH = 256;
    private static final int MAX_CURRENCY_ID_LENGTH = 128;
    private static final int MAX_NAME_LENGTH = 128;
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9/._-]+");
    static final Codec<String> ID_CODEC = Codec.STRING.validate(EconomyValidation::id);
    static final Codec<String> CURRENCY_ID_CODEC = Codec.STRING.validate(EconomyValidation::currencyId);
    static final Codec<String> NAME_CODEC = Codec.STRING.validate(EconomyValidation::name);
    static final Codec<BigInteger> MONEY_CODEC = Helpers.BIG_INTEGER_CODEC.validate(EconomyValidation::money);

    private EconomyValidation() {}

    static boolean validId(String value) {
        return value.length() <= MAX_ID_LENGTH && ID_PATTERN.matcher(value).matches();
    }

    static DataResult<String> id(String value) {
        if (value.length() > MAX_ID_LENGTH) return DataResult.error(() -> "Economy IDs cannot exceed " + MAX_ID_LENGTH + " characters");
        return validId(value) ? DataResult.success(value) : DataResult.error(() -> "Invalid economy ID: " + value);
    }

    static DataResult<String> currencyId(String value) {
        if (value.length() > MAX_CURRENCY_ID_LENGTH) return DataResult.error(() -> "Currency IDs cannot exceed " + MAX_CURRENCY_ID_LENGTH + " characters");
        return id(value);
    }

    static DataResult<String> name(String value) {
        if (value.length() > MAX_NAME_LENGTH) return DataResult.error(() -> "Names cannot exceed " + MAX_NAME_LENGTH + " characters");
        return value.isBlank() ? DataResult.error(() -> "Name cannot be blank") : DataResult.success(value);
    }

    static DataResult<Item> icon(Item value) {
        return BuiltInRegistries.ITEM.getValue(BuiltInRegistries.ITEM.getKey(value)) == value
                ? DataResult.success(value) : DataResult.error(() -> "Icon must be a registered Minecraft item");
    }

    static DataResult<BigInteger> money(BigInteger value) {
        return value.signum() < 0 ? DataResult.error(() -> "Balance cannot be negative") : DataResult.success(value);
    }

    static DataResult<Item> metadata(String name, Item icon) {
        return name(name).flatMap(_ -> icon(icon));
    }
}
