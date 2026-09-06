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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.regex.Pattern;

public final class PolyCoinEconomyCurrency
        implements eu.pb4.common.economy.api.EconomyCurrency {

    public static final int DECIMAL_PLACES = 2;

    //Allows: 10 10.5 10.50 .50 -10.50 +10.50
    // Rejects: 1e10 NaN Infinity 1,000
    private static final Pattern VALUE_PATTERN = Pattern.compile("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)");

    public static final Codec<PolyCoinEconomyCurrency> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    EconomyValidation.CURRENCY_ID_CODEC.fieldOf("id").forGetter(currency -> currency.id),
                    EconomyValidation.NAME_CODEC.fieldOf("name").forGetter(currency -> currency.name),
                    BuiltInRegistries.ITEM.byNameCodec().fieldOf("icon").forGetter(currency -> currency.icon),
                    EconomyValidation.MONEY_CODEC.fieldOf("default_balance").forGetter(currency -> currency.defaultBalance)
            ).apply(instance, PolyCoinEconomyCurrency::new));

    private final String id;
    private final String name;
    private final Item icon;
    private final BigInteger defaultBalance;

    private PolyCoinEconomyCurrency(String id, String name, Item icon, BigInteger defaultBalance) {
        this.id = id;
        this.name = name;
        this.icon = icon;
        this.defaultBalance = defaultBalance;
    }

    static DataResult<PolyCoinEconomyCurrency> create(String id, String name, Item icon, BigInteger defaultBalance) {
        return EconomyValidation.currencyId(id)
                .flatMap(_ -> EconomyValidation.metadata(name, icon))
                .flatMap(_ -> EconomyValidation.money(defaultBalance))
                .map(_ -> new PolyCoinEconomyCurrency(id, name, icon, defaultBalance));
    }

    static PolyCoinEconomyCurrency defaultCurrency() {
        return new PolyCoinEconomyCurrency(PolyCoinEconomyCurrencyData.DEFAULT_CURRENCY_ID,
                PolyCoinEconomyCurrencyData.DEFAULT_CURRENCY_NAME, PolyCoinEconomyCurrencyData.DEFAULT_CURRENCY_ICON,
                PolyCoinEconomyCurrencyData.DEFAULT_BALANCE);
    }

    public String getId() {
        return id;
    }

    @Override
    public Identifier id() {
        return Identifier.fromNamespaceAndPath(PolyCoin.MOD_ID, id);
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
        // We currently display the exact two-decimal representation in both modes.
        // Raw: 123456
        // Displayed: 1234.56
        return new BigDecimal(value, DECIMAL_PLACES).toPlainString();
    }

    @Override
    public BigInteger parseValue(String value) throws NumberFormatException {
        return tryParseAmount(value).getOrThrow(NumberFormatException::new);
    }

    public static DataResult<BigInteger> tryParseAmount(String value) {
        String input = value.strip();

        // BigDecimal itself accepts scientific notation. For a player
        // economy, accepting "1e20" accidentally is usually undesirable.
        if (!VALUE_PATTERN.matcher(input).matches()) {
            return DataResult.error(() -> "Invalid monetary value: " + value);
        }

        try {
            // UNNECESSARY means we never silently round money.
            // "1.234" -> rejected
            // "1.2300" -> accepted, because no precision is lost
            return DataResult.success(new BigDecimal(input).setScale(DECIMAL_PLACES, RoundingMode.UNNECESSARY).movePointRight(DECIMAL_PLACES).toBigIntegerExact());
        } catch (ArithmeticException | NumberFormatException exception) {
            return DataResult.error(() -> "Value cannot be represented exactly with " + DECIMAL_PLACES + " decimal places: " + value);
        }
    }
}
