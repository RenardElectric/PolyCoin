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

    private static final Codec<BigInteger> BALANCE_CODEC =
            Helpers.BIG_INTEGER_CODEC.validate(value -> {
                if (value.signum() >= 0) return DataResult.success(value);
                return DataResult.error(() -> "Account balance cannot be negative: " + value);
            });

    public static final Codec<PolyCoinEconomyAccount> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    Codec.STRING.fieldOf("id").forGetter(account -> account.id),
                    Codec.STRING.fieldOf("currency").forGetter(account -> account.currencyId),
                    BALANCE_CODEC.fieldOf("balance").forGetter(PolyCoinEconomyAccount::balance),
                    UUIDUtil.STRING_CODEC.fieldOf("owner").forGetter(account -> account.owner),
                    Codec.STRING.fieldOf("name").forGetter(account -> account.name),
                    BuiltInRegistries.ITEM.byNameCodec().fieldOf("icon").forGetter(account -> account.icon)
            ).apply(instance, PolyCoinEconomyAccount::new));

    private final String id;
    private volatile String currencyId;

    private BigInteger balance;

    private final UUID owner;
    private volatile String name;
    private volatile Item icon;

    private volatile @Nullable PolyCoinEconomyData data;

    PolyCoinEconomyAccount(
            String id, String currencyId, BigInteger balance,
            UUID owner, String name, Item icon
    ) {
        this.id = id;
        this.currencyId = currencyId;
        this.balance = balance;
        this.owner = owner;
        this.name = name;
        this.icon = icon;
    }

    synchronized void attach(PolyCoinEconomyData data) {
        this.data = data;
    }

    synchronized void detach() {
        this.data = null;
    }

    synchronized DataResult<PolyCoinEconomyAccount> updateMetadata(String currencyId, String name, Item icon) {
        var data = this.data;
        if (data == null) return DataResult.error(() -> "Account is not attached to any economy data");
        var currency = data.getCurrency(currencyId);
        if (currency.isError()) return currency.map(_ -> this);
        if (!this.currencyId.equals(currencyId) && balance.signum() != 0) return DataResult.error(() -> "An account balance must be zero before its currency can be changed");
        if (name.isBlank()) return DataResult.error(() -> "Account name cannot be blank");

        if (this.currencyId.equals(currencyId)
                && this.name.equals(name)
                && this.icon == icon) {
            return DataResult.success(this);
        }

        this.currencyId = currencyId;
        this.name = name;
        this.icon = icon;
        data.setDirty();
        return DataResult.success(this);
    }

    boolean usesCurrency(String currencyId) {
        return this.currencyId.equals(currencyId);
    }

    public String currencyId() {
        return currencyId;
    }

    public String getId() {
        return id;
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
        return Identifier.fromNamespaceAndPath(PolyCoin.MOD_ID, id);
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
        Objects.requireNonNull(value, "value");
        Helpers.requireNonNegative(value);
        PolyCoinEconomyData data = this.data;
        if (data == null) throw new IllegalStateException("Account is not attached to any economy data");
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
        var data = this.data;
        if (data == null) throw new IllegalStateException("Account is not attached to any economy data");
        var currencyResult = data.getCurrency(currencyId);
        if (currencyResult.isError()) throw new IllegalStateException("Currency " + currencyId + " for account " + id + " no longer exists");
        return currencyResult.getOrThrow();
    }

    @Override
    public ItemStack accountIcon() {
        return icon.getDefaultInstance();
    }
}
