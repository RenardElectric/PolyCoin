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
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.Util;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import polycube.polycoin.PolyCoin;

import java.math.BigInteger;
import java.util.*;

public final class PolyCoinEconomyAccount implements EconomyAccount {
    private static final Codec<Set<UUID>> UUIDS_CODEC = Codec.list(UUIDUtil.STRING_CODEC).comapFlatMap(
            PolyCoinEconomyAccount::decodeOwners,
            owners -> owners.stream().sorted().toList()
    );
    public static final Codec<PolyCoinEconomyAccount> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            EconomyValidation.ID_CODEC.fieldOf("id").forGetter(PolyCoinEconomyAccount::getId),
            EconomyValidation.ID_CODEC.fieldOf("currency").forGetter(PolyCoinEconomyAccount::currencyId),
            EconomyValidation.MONEY_CODEC.fieldOf("balance").forGetter(PolyCoinEconomyAccount::balance),
            UUIDS_CODEC.fieldOf("owners").forGetter(PolyCoinEconomyAccount::owners),
            EconomyValidation.NAME_CODEC.fieldOf("name").forGetter(PolyCoinEconomyAccount::displayName),
            BuiltInRegistries.ITEM.byNameCodec().fieldOf("icon").forGetter(PolyCoinEconomyAccount::iconItem)
    ).apply(instance, PolyCoinEconomyAccount::new));

    private final String id;
    private final Set<UUID> owners;
    private final String currencyId;
    private BigInteger balance;
    private String name;
    private Item icon;
    private final @Nullable PolyCoinEconomyData data;

    private static DataResult<Set<UUID>> decodeOwners(List<UUID> encodedOwners) {
        if (encodedOwners.isEmpty()) return DataResult.error(() -> "An account must have at least one owner");
        var owners = new TreeSet<>(encodedOwners);
        if (owners.size() != encodedOwners.size()) return DataResult.error(() -> "An account cannot contain duplicate owners");
        return DataResult.success(owners);
    }

    private PolyCoinEconomyAccount(String id, String currencyId, BigInteger balance, Set<UUID> owners, String name, Item icon) {
        this(id, currencyId, balance, owners, name, icon, null);
    }

    private PolyCoinEconomyAccount(
            String id, String currencyId, BigInteger balance,
            Set<UUID> owners, String name, Item icon, @Nullable PolyCoinEconomyData data
    ) {
        this.id = id;
        this.currencyId = currencyId;
        this.balance = balance;
        this.owners = new HashSet<>(owners);
        this.name = name;
        this.icon = icon;
        this.data = data;
    }

    static DataResult<PolyCoinEconomyAccount> create(
            PolyCoinEconomyData data, String id, String currencyId,
            Set<UUID> owners, String name, Item icon
    ) {
        if (owners.isEmpty()) return DataResult.error(() -> "An account must have at least one owner");
        return EconomyValidation.id(id)
                .flatMap(_ -> EconomyValidation.id(currencyId))
                .flatMap(_ -> EconomyValidation.metadata(name, icon))
                .map(_ -> new PolyCoinEconomyAccount(id, currencyId, BigInteger.ZERO, owners, name, icon, data));
    }

    static PolyCoinEconomyAccount defaultAccount(PolyCoinEconomyData data, String id, PolyCoinEconomyCurrency currency, UUID owner) {
        return new PolyCoinEconomyAccount(id, currency.getId(), currency.defaultBalance(), Set.of(owner),
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
            return new PolyCoinEconomyAccount(id, currencyId, balance, owners, name, icon, data);
        }
    }

    void setMetadata(String name, Item icon) {
        synchronized (lock()) {
            var previousName = this.name;
            var previousIcon = this.icon;
            this.name = name;
            this.icon = icon;
            EconomyLog.accountUpdated(this, currencyId, previousName, previousIcon);
        }
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
    public UUID owner() {
        synchronized (lock()) {
            return owners.size() == 1 ? owners.iterator().next() : Util.NIL_UUID;
        }
    }

    public Set<UUID> owners() {
        synchronized (lock()) { return Collections.unmodifiableSet(new LinkedHashSet<>(new TreeSet<>(owners))); }
    }

    boolean isOwnedBy(UUID owner) {
        synchronized (lock()) { return owners.contains(owner); }
    }

    DataResult<Collection<NameAndId>> addOwners(Collection<NameAndId> profiles) {
        synchronized (lock()) {
            if (!isManaged()) return DataResult.error(() -> "Account is no longer available");
            var uniqueProfiles = PolyCoinEconomyAccountData.uniqueProfiles(profiles);
            var addedProfiles = new ArrayList<NameAndId>();
            for (var profile : uniqueProfiles) {
                if (owners.contains(profile.id())) continue;
                owners.add(profile.id());
                addedProfiles.add(profile);
                var managedData = Objects.requireNonNull(data);
                managedData.accountData.ownerAdded(this, profile.id());
                managedData.setDirty();
                EconomyLog.accountOwnerAdded(this, profile.id());
            }
            return DataResult.success(List.copyOf(addedProfiles));
        }
    }

    DataResult<Collection<NameAndId>> removeOwners(Collection<NameAndId> profiles) {
        synchronized (lock()) {
            if (!isManaged()) return DataResult.error(() -> "Account is no longer available");
            var uniqueProfiles = PolyCoinEconomyAccountData.uniqueProfiles(profiles);
            for (var profile : uniqueProfiles) {
                if (!owners.contains(profile.id())) return DataResult.error(() -> "Account does not have owner: " + profile.name());
            }
            if (owners.size() == uniqueProfiles.size()) return DataResult.error(() -> "Cannot remove all owners of the account");
            for (var profile : uniqueProfiles) {
                owners.remove(profile.id());
                var managedData = Objects.requireNonNull(data);
                managedData.accountData.ownerRemoved(this, profile.id());
                managedData.setDirty();
                EconomyLog.accountOwnerRemoved(this, profile.id());
            }
            return DataResult.success(uniqueProfiles);
        }
    }

    @Override
    public Identifier id() { return PolyCoin.id(id); }

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
        synchronized (lock()) {
            return icon == PolyCoinEconomyAccountData.DEFAULT_ACCOUNT_ICON
                    ? PolyCoinEconomyAccountData.DEFAULT_ACCOUNT_ICON_TEMPLATE.create()
                    : icon.getDefaultInstance();
        }
    }
}
