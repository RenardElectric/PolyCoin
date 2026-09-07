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

import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;

public final class PolyCoinEconomyAccount implements EconomyAccount {
    public static final Codec<PolyCoinEconomyAccount> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            EconomyValidation.ID_CODEC.fieldOf("id").forGetter(PolyCoinEconomyAccount::getId),
            EconomyValidation.ID_CODEC.fieldOf("currency").forGetter(PolyCoinEconomyAccount::currencyId),
            EconomyValidation.MONEY_CODEC.fieldOf("balance").forGetter(PolyCoinEconomyAccount::balance),
            UUIDUtil.STRING_CODEC.fieldOf("owner").forGetter(PolyCoinEconomyAccount::owner),
            EconomyValidation.NAME_CODEC.fieldOf("name").forGetter(PolyCoinEconomyAccount::displayName),
            BuiltInRegistries.ITEM.byNameCodec().fieldOf("icon").forGetter(PolyCoinEconomyAccount::iconItem)
    ).apply(instance, PolyCoinEconomyAccount::new));

    private final String id;
    private final UUID owner;
    private final String currencyId;
    private BigInteger balance;
    private String name;
    private Item icon;
    private final @Nullable PolyCoinEconomyData data;

    private PolyCoinEconomyAccount(String id, String currencyId, BigInteger balance, UUID owner, String name, Item icon) {
        this(id, currencyId, balance, owner, name, icon, null);
    }

    private PolyCoinEconomyAccount(
            String id, String currencyId, BigInteger balance,
            UUID owner, String name, Item icon, @Nullable PolyCoinEconomyData data
    ) {
        this.id = id;
        this.currencyId = currencyId;
        this.balance = balance;
        this.owner = owner;
        this.name = name;
        this.icon = icon;
        this.data = data;
    }

    static DataResult<PolyCoinEconomyAccount> create(
            PolyCoinEconomyData data, String id, String currencyId,
            UUID owner, String name, Item icon
    ) {
        return EconomyValidation.id(id)
                .flatMap(_ -> EconomyValidation.id(currencyId))
                .flatMap(_ -> EconomyValidation.metadata(name, icon))
                .map(_ -> new PolyCoinEconomyAccount(id, currencyId, BigInteger.ZERO, owner, name, icon, data));
    }

    static PolyCoinEconomyAccount defaultAccount(PolyCoinEconomyData data, String id, PolyCoinEconomyCurrency currency, UUID owner) {
        return new PolyCoinEconomyAccount(id, currency.getId(), currency.defaultBalance(), owner,
                PolyCoinEconomyAccountData.DEFAULT_ACCOUNT_NAME, PolyCoinEconomyAccountData.DEFAULT_ACCOUNT_ICON, data);
    }

    private Object lock() {
        var data = this.data;
        return data == null ? this : data;
    }

    private boolean isManaged() {
        return data != null && data.accountData.isManaged(this);
    }

    PolyCoinEconomyAccount copy(PolyCoinEconomyData data) {
        synchronized (lock()) {
            return new PolyCoinEconomyAccount(id, currencyId, balance, owner, name, icon, data);
        }
    }

    void setMetadata(String name, Item icon) {
        var previousName = this.name;
        var previousIcon = this.icon;
        this.name = name;
        this.icon = icon;
        EconomyLog.accountUpdated(this, currencyId, previousName, previousIcon);
    }

    boolean usesCurrency(String currencyId) {
        synchronized (lock()) { return this.currencyId.equals(currencyId); }
    }

    public String currencyId() {
        synchronized (lock()) { return currencyId; }
    }

    public String getId() { return id; }

    public String displayName() {
        synchronized (lock()) { return name; }
    }

    public Item iconItem() {
        synchronized (lock()) { return icon; }
    }

    @Override
    public Component name() { return Component.literal(displayName()); }

    @Override
    public UUID owner() { return owner; }

    @Override
    public Identifier id() { return Identifier.fromNamespaceAndPath(PolyCoin.MOD_ID, id); }

    @Override
    public BigInteger balance() {
        synchronized (lock()) { return balance; }
    }

    private EconomyTransaction transaction(BigInteger value, boolean debit, boolean apply) {
        synchronized (lock()) {
            var error = !isManaged() ? "Account is no longer available"
                    : value.signum() < 0 ? "Amount cannot be negative"
                    : debit && balance.compareTo(value) < 0 ? "Insufficient funds" : null;
            if (error != null) {
                return new EconomyTransaction.Simple(false, Component.literal(error), balance, balance, BigInteger.ZERO, this);
            }
            var delta = debit ? value.negate() : value;
            var next = balance.add(delta);
            var result = new EconomyTransaction.Simple(true, Component.literal("Success"), next, balance, delta, this);
            if (apply && delta.signum() != 0) {
                var previous = balance;
                balance = next;
                Objects.requireNonNull(data).setDirty();
                EconomyLog.balanceChanged(this, previous, debit ? "decrease" : "increase");
            }
            return result;
        }
    }

    @Override
    public EconomyTransaction canIncreaseBalance(BigInteger value) { return transaction(value, false, false); }

    @Override
    public EconomyTransaction canDecreaseBalance(BigInteger value) { return transaction(value, true, false); }

    @Override
    public EconomyTransaction increaseBalance(BigInteger value) { return transaction(value, false, true); }

    @Override
    public EconomyTransaction decreaseBalance(BigInteger value) { return transaction(value, true, true); }

    @Override
    public EconomyTransaction decreaseBalance(long value) { return decreaseBalance(BigInteger.valueOf(value)); }

    @Override
    public void setBalance(BigInteger value) {
        trySetBalance(value).getOrThrow(IllegalStateException::new);
    }

    public DataResult<BigInteger> trySetBalance(BigInteger value) {
        synchronized (lock()) {
            if (!isManaged()) return DataResult.error(() -> "Account is no longer available");
            return EconomyValidation.money(value).map(validated -> {
                if (!balance.equals(validated)) {
                    var previous = balance;
                    balance = validated;
                    Objects.requireNonNull(data).setDirty();
                    EconomyLog.balanceChanged(this, previous, "set");
                }
                return balance;
            });
        }
    }

    @Override
    public PolyCoinEconomyProvider provider() { return PolyCoin.INSTANCE; }

    @Override
    public PolyCoinEconomyCurrency currency() {
        return getCurrency().getOrThrow(IllegalStateException::new);
    }

    public DataResult<PolyCoinEconomyCurrency> getCurrency() {
        synchronized (lock()) {
            if (!isManaged()) return DataResult.error(() -> "Account is no longer available");
            return Objects.requireNonNull(data).getCurrency(currencyId);
        }
    }

    @Override
    public Component formattedBalance() {
        synchronized (lock()) {
            return getCurrency().map(currency -> currency.formatValueComponent(balance, false))
                    .mapOrElse(component -> component, error -> Component.literal(error.message()));
        }
    }

    @Override
    public ItemStack accountIcon() {
        return icon == PolyCoinEconomyAccountData.DEFAULT_ACCOUNT_ICON
                ? PolyCoinEconomyAccountData.DEFAULT_ACCOUNT_ICON_TEMPLATE.create()
                : icon.getDefaultInstance();
    }
}
