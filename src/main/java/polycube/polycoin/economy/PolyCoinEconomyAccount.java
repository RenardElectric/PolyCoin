package polycube.polycoin.economy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import eu.pb4.common.economy.api.EconomyAccount;
import eu.pb4.common.economy.api.EconomyTransaction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import polycube.polycoin.PolyCoin;
import polycube.polycoin.util.Helpers;

import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;

public final class PolyCoinEconomyAccount implements EconomyAccount {

    private static final Codec<Identifier> POLYCOIN_IDENTIFIER_CODEC =
            Identifier.CODEC.validate(id -> {
                if (PolyCoin.MOD_ID.equals(id.getNamespace())) return DataResult.success(id);
                return DataResult.error(() -> "Expected PolyCoin identifier, got: " + id);
            });

    private static final Codec<BigInteger> BALANCE_CODEC =
            Helpers.BIG_INTEGER_CODEC.validate(value -> {
                if (value.signum() >= 0) return DataResult.success(value);
                return DataResult.error(() -> "Account balance cannot be negative: " + value);
            });

    public static final Codec<PolyCoinEconomyAccount> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    POLYCOIN_IDENTIFIER_CODEC.fieldOf("id").forGetter(account -> account.id),
                    POLYCOIN_IDENTIFIER_CODEC.fieldOf("currency").forGetter(account -> account.currencyId),
                    BALANCE_CODEC.fieldOf("balance").forGetter(PolyCoinEconomyAccount::balance),
                    UUIDUtil.STRING_CODEC.fieldOf("owner").forGetter(account -> account.owner),
                    Codec.STRING.fieldOf("name").forGetter(account -> account.name),
                    BuiltInRegistries.ITEM.byNameCodec().fieldOf("icon").forGetter(account -> account.icon)
            ).apply(instance, PolyCoinEconomyAccount::new));

    private final Identifier id;
    private volatile Identifier currencyId;

    private BigInteger balance;

    private final UUID owner;
    private volatile String name;
    private volatile Item icon;

    private volatile @Nullable PolyCoinEconomyData data;

    public PolyCoinEconomyAccount(
            Identifier id, Identifier currencyId, BigInteger balance,
            UUID owner, String name, Item icon
    ) {
        this.id = Helpers.requirePolyCoinIdentifier(id, "id");
        this.currencyId = Helpers.requirePolyCoinIdentifier(currencyId, "currencyId");

        this.balance = requireNonNegative(balance, "balance");

        this.owner = Objects.requireNonNull(owner, "owner");
        this.name = requireNonBlank(name, "name");
        this.icon = Objects.requireNonNull(icon, "icon");
    }

    synchronized void attach(PolyCoinEconomyData data) {
        Objects.requireNonNull(data, "data");

        if (this.data != null && this.data != data) {
            throw new IllegalStateException("Account " + id + " is already attached to another economy data instance");
        }
        if (data.getCurrency(currencyId) == null) {
            throw new IllegalStateException("Account " + id + " references unknown currency " + currencyId);
        }
        this.data = data;
    }

    synchronized void detach(PolyCoinEconomyData data) {
        if (this.data != data) {
            throw new IllegalStateException("Account " + id + " is not attached to the expected economy data instance");
        }
        this.data = null;
    }

    synchronized void updateMetadata(Identifier currencyId, String name, Item icon) {
        PolyCoinEconomyData data = requireData();
        Identifier validatedCurrencyId = Helpers.requirePolyCoinIdentifier(currencyId, "currencyId");
        String validatedName = requireNonBlank(name, "name");
        Item validatedIcon = Objects.requireNonNull(icon, "icon");

        if (data.getCurrency(validatedCurrencyId) == null) {
            throw new IllegalArgumentException("Unknown currency: " + validatedCurrencyId);
        }
        if (!this.currencyId.equals(validatedCurrencyId) && balance.signum() != 0) {
            throw new IllegalStateException("An account balance must be zero before its currency can be changed");
        }
        if (this.currencyId.equals(validatedCurrencyId)
                && this.name.equals(validatedName)
                && this.icon == validatedIcon) {
            return;
        }

        this.currencyId = validatedCurrencyId;
        this.name = validatedName;
        this.icon = validatedIcon;
        data.setDirty();
    }

    private PolyCoinEconomyData requireData() {
        PolyCoinEconomyData data = this.data;
        if (data == null) {
            throw new IllegalStateException("Economy account " + id + " is not attached to PolyCoinEconomyData");
        }
        return data;
    }

    boolean usesCurrency(Identifier currencyId) {
        return this.currencyId.equals(currencyId);
    }

    public Identifier currencyId() {
        return currencyId;
    }

    public String displayName() {
        return name;
    }

    public Item iconItem() {
        return icon;
    }

    @Override
    public Component name() {
        return Component.literal(name);
    }

    @Override
    public UUID owner() {
        return owner;
    }

    @Override
    public Identifier id() {
        return id;
    }

    @Override
    public synchronized BigInteger balance() {
        return balance;
    }

    @Override
    public synchronized EconomyTransaction canIncreaseBalance(BigInteger value) {
        Objects.requireNonNull(value, "value");
        BigInteger current = balance;

        if (value.signum() < 0) {
            return new EconomyTransaction.Simple(
                    false, Component.literal("Amount cannot be negative"),
                    current, current, BigInteger.ZERO, this
            );
        }

        return new EconomyTransaction.Simple(
                true, Component.literal("Success"),
                current.add(value), current, value, this
        );
    }

    @Override
    public synchronized EconomyTransaction canDecreaseBalance(BigInteger value) {
        Objects.requireNonNull(value, "value");
        BigInteger current = balance;

        if (value.signum() < 0) {
            return new EconomyTransaction.Simple(
                    false, Component.literal("Amount cannot be negative"),
                    current, current, BigInteger.ZERO, this
            );
        }

        if (current.compareTo(value) < 0) {
            return new EconomyTransaction.Simple(
                    false, Component.literal("Insufficient funds"),
                    current, current, value.negate(), this
            );
        }

        return new EconomyTransaction.Simple(
                true, Component.literal("Success"),
                current.subtract(value), current, value.negate(), this
        );
    }

    @Override
    public synchronized EconomyTransaction increaseBalance(BigInteger value) {
        EconomyTransaction transaction = canIncreaseBalance(value);
        if (transaction.isSuccessful()) {
            setBalance(transaction.finalBalance());
        }
        return transaction;
    }

    @Override
    public synchronized EconomyTransaction decreaseBalance(BigInteger value) {
        EconomyTransaction transaction = canDecreaseBalance(value);
        if (transaction.isSuccessful()) {
            setBalance(transaction.finalBalance());
        }
        return transaction;
    }

    @Override
    public EconomyTransaction decreaseBalance(long value) {
        return decreaseBalance(BigInteger.valueOf(value));
    }

    @Override
    public synchronized void setBalance(BigInteger value) {
        requireNonNegative(value, "value");

        // An account that isn't attached cannot be persisted safely.
        // Check this before mutating anything.
        PolyCoinEconomyData data = requireData();
        if (balance.equals(value)) return;
        balance = value;
        data.setDirty();
    }

    @Override
    public PolyCoinEconomyProvider provider() {
        return PolyCoin.INSTANCE;
    }

    @Override
    public PolyCoinEconomyCurrency currency() {
        PolyCoinEconomyCurrency currency = requireData().getCurrency(currencyId);
        if (currency == null) {
            throw new IllegalStateException("Currency " + currencyId + " for account " + id + " no longer exists");
        }
        return currency;
    }

    @Override
    public ItemStack accountIcon() {
        return icon.getDefaultInstance();
    }

    private static BigInteger requireNonNegative(BigInteger value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " cannot be negative: " + value);
        }
        return value;
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " cannot be blank");
        return value;
    }
}
