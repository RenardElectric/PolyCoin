package polycube.polycoin.economy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import polycube.polycoin.PolyCoin;
import polycube.polycoin.util.Helpers;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.regex.Pattern;

public final class PolyCoinEconomyCurrency
        implements eu.pb4.common.economy.api.EconomyCurrency {

    public static final int DECIMAL_PLACES = 2;

    //Allows: 10 10.5 10.50 .50 -10.50 +10.50
    // Rejects: 1e10 NaN Infinity 1,000
    private static final Pattern VALUE_PATTERN = Pattern.compile("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)");

    private static final Codec<Identifier> POLYCOIN_IDENTIFIER_CODEC =
            Identifier.CODEC.validate(id -> {
                if (PolyCoin.MOD_ID.equals(id.getNamespace())) return DataResult.success(id);
                return DataResult.error(() -> "Expected PolyCoin identifier, got: " + id);
            });

    private static final Codec<BigInteger> DEFAULT_BALANCE_CODEC =
            Helpers.BIG_INTEGER_CODEC.validate(value -> {
                if (value.signum() >= 0) return DataResult.success(value);
                return DataResult.error(() -> "Default balance cannot be negative: " + value);
            });

    public static final Codec<PolyCoinEconomyCurrency> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    POLYCOIN_IDENTIFIER_CODEC.fieldOf("id").forGetter(currency -> currency.id),
                    Codec.STRING.fieldOf("name").forGetter(currency -> currency.name),
                    BuiltInRegistries.ITEM.byNameCodec().fieldOf("icon").forGetter(currency -> currency.icon),
                    DEFAULT_BALANCE_CODEC.fieldOf("default_balance").forGetter(currency -> currency.defaultBalance)
            ).apply(instance, PolyCoinEconomyCurrency::new));

    private final Identifier id;
    private final String name;
    private final Item icon;
    private final BigInteger defaultBalance;

    public PolyCoinEconomyCurrency(Identifier id, String name, Item icon, BigInteger defaultBalance) {
        this.id = requirePolyCoinIdentifier(id);
        this.name = Objects.requireNonNull(name, "name");
        this.icon = Objects.requireNonNull(icon, "icon");
        this.defaultBalance = requireNonNegative(defaultBalance, "defaultBalance");
    }

    @Override
    public Identifier id() {
        return id;
    }

    @Override
    public Component name() {
        return Component.literal(name);
    }

    public String displayName() {
        return name;
    }

    @Override
    public ItemStack icon() {
        return icon.getDefaultInstance();
    }

    public Item iconItem() {
        return icon;
    }

    @Override
    public PolyCoinEconomyProvider provider() {
        return PolyCoin.INSTANCE;
    }

    public BigInteger defaultBalance() {
        return defaultBalance;
    }

    @Override
    public Component formatValueComponent(BigInteger value, boolean precise) {
        return Component.literal(formatValue(value, precise) + " ").append(name());
    }

    @Override
    public String formatValue(BigInteger value, boolean precise) {
        Objects.requireNonNull(value, "value");
        // We currently display the exact two-decimal representation in both modes.
        // Raw: 123456
        // Displayed: 1234.56
        return new BigDecimal(value, DECIMAL_PLACES).toPlainString();
    }

    @Override
    public BigInteger parseValue(String value) throws NumberFormatException {
        return parseAmount(value);
    }

    public static BigInteger parseAmount(String value) throws NumberFormatException {
        Objects.requireNonNull(value, "value");
        String input = value.strip();

        // BigDecimal itself accepts scientific notation. For a player
        // economy, accepting "1e20" accidentally is usually undesirable.
        if (!VALUE_PATTERN.matcher(input).matches()) {
            throw new NumberFormatException("Invalid monetary value: " + value);
        }

        try {
            // UNNECESSARY means we never silently round money.
            // "1.234" -> rejected
            // "1.2300" -> accepted, because no precision is lost
            return new BigDecimal(input).setScale(DECIMAL_PLACES, RoundingMode.UNNECESSARY).movePointRight(DECIMAL_PLACES).toBigIntegerExact();
        } catch (ArithmeticException exception) {
            NumberFormatException result = new NumberFormatException("Value has more than " + DECIMAL_PLACES + " decimal places of precision: " + value);
            result.initCause(exception);
            throw result;
        }
    }

    private static Identifier requirePolyCoinIdentifier(Identifier id) {
        Objects.requireNonNull(id, "id");
        if (!PolyCoin.MOD_ID.equals(id.getNamespace())) {
            throw new IllegalArgumentException("Currency id must use namespace '" + PolyCoin.MOD_ID + "': " + id);
        }
        return id;
    }

    private static BigInteger requireNonNegative(BigInteger value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " cannot be negative: " + value);
        }
        return value;
    }
}
