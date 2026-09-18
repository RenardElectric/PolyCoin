package polycube.polycoin.commands.commandArguments;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import polycube.polycoin.PolyCoin;
import polycube.polycoin.economy.PolyCoinEconomyAccount;
import polycube.polycoin.economy.PolyCoinEconomyData;
import polycube.polycore.commands.CommandResult;
import polycube.polycore.text.TextComponents;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class AccountArgument {
    public static final String OWNER_ARGUMENT = "asPlayer";
    private static final SimpleCommandExceptionType SINGLE_OWNER_REQUIRED = new SimpleCommandExceptionType(
            TextComponents.error("Select exactly one account owner.")
    );
    private static final DynamicCommandExceptionType INVALID_ACCOUNT = new DynamicCommandExceptionType(
            id -> TextComponents.error("Invalid account id: " + id)
    );
    private static final DynamicCommandExceptionType MISSING_DEFAULT = new DynamicCommandExceptionType(
            currency -> TextComponents.error("No default account for currency: " + currency)
    );

    private AccountArgument() {}

    public static boolean isActingAs(CommandContext<CommandSourceStack> context) {
        return PolyCoinIdentifierArgument.hasArgument(context, OWNER_ARGUMENT);
    }

    public static GameProfile getOwner(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        if (!isActingAs(context)) return context.getSource().getPlayerOrException().getGameProfile();

        var owners = GameProfileArgument.getGameProfiles(context, OWNER_ARGUMENT);
        if (owners.size() != 1) throw SINGLE_OWNER_REQUIRED.create();
        var owner = owners.iterator().next();
        return new GameProfile(owner.id(), owner.name());
    }

    public static String parseId(String rawId) throws CommandSyntaxException {
        Identifier id = PolyCoinIdentifierArgument.parse(rawId);
        if (id == null) throw INVALID_ACCOUNT.create(rawId);
        return id.getPath();
    }

    public static PolyCoinEconomyAccount getAccount(
            PolyCoinEconomyData data, UUID owner, @Nullable String rawId
    ) throws CommandSyntaxException {
        return rawId == null
                ? getDefaultAccount(data, owner, data.getDefaultCurrency())
                : CommandResult.require(data.getAccount(owner, parseId(rawId)));
    }

    private static PolyCoinEconomyAccount getDefaultAccount(
            PolyCoinEconomyData data, UUID owner, String currencyId
    ) throws CommandSyntaxException {
        String id = data.getDefaultAccountId(owner, currencyId);
        if (id == null) throw MISSING_DEFAULT.create(currencyId);
        return CommandResult.require(data.getAccount(owner, id));
    }

    public record TransferAccounts(PolyCoinEconomyAccount source, PolyCoinEconomyAccount target) {}

    /// An explicit account selects the currency for the omitted side.
    /// If both are omitted, use each owner's default for the default currency.
    public static TransferAccounts getTransferAccounts(
            PolyCoinEconomyData data, UUID sourceOwner, @Nullable String sourceId,
            UUID targetOwner, @Nullable String targetId
    ) throws CommandSyntaxException {
        var source = sourceId == null ? null : getAccount(data, sourceOwner, sourceId);
        var target = targetId == null ? null : getAccount(data, targetOwner, targetId);
        if (source == null) {
            source = getDefaultAccount(data, sourceOwner, target == null ? data.getDefaultCurrency() : target.currencyId());
        }
        if (target == null) target = getDefaultAccount(data, targetOwner, source.currencyId());
        return new TransferAccounts(source, target);
    }

    /// Shared vanilla-client-compatible amount/from/to tree, in either argument order.
    public static RequiredArgumentBuilder<CommandSourceStack, String> transferArguments(
            @Nullable String playerKey, Command<CommandSourceStack> execute
    ) {
        var amount = Commands.argument("amount", StringArgumentType.word()).executes(execute);
        for (String side : new String[]{"from", "to"}) {
            String other = side.equals("from") ? "to" : "from";
            amount.then(Commands.literal(side).then(
                    Commands.argument(side, StringArgumentType.string())
                            .suggests((context, builder) -> suggestTransferAccounts(context, builder, playerKey, side, other))
                            .executes(execute)
                            .then(Commands.literal(other).then(
                                    Commands.argument(other, StringArgumentType.string())
                                            .suggests((context, builder) -> suggestTransferAccounts(context, builder, playerKey, other, side))
                                            .executes(execute)
                            ))
            ));
        }
        return amount;
    }

    public static CompletableFuture<Suggestions> suggestAccounts(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder, String... literalSiblings
    ) {
        try {
            var data = PolyCoin.INSTANCE.getData(context.getSource().getServer());
            return PolyCoinIdentifierArgument.suggestIds(data.getAccounts(getOwner(context).id()).keySet(), builder, literalSiblings);
        } catch (CommandSyntaxException exception) {
            return builder.buildFuture();
        }
    }

    private static CompletableFuture<Suggestions> suggestTransferAccounts(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder,
            @Nullable String playerKey, String side, String otherSide
    ) {
        try {
            UUID sourceOwner = getOwner(context).id();
            UUID targetOwner = playerKey == null ? sourceOwner : EntityArgument.getPlayer(context, playerKey).getUUID();
            UUID suggestedOwner = side.equals("from") ? sourceOwner : targetOwner;
            UUID otherOwner = side.equals("from") ? targetOwner : sourceOwner;
            var data = PolyCoin.INSTANCE.getData(context.getSource().getServer());
            String otherId = PolyCoinIdentifierArgument.getOptionalId(context, otherSide);
            // The first explicit account may select any currency; the second must match it.
            if (otherId == null) {
                return PolyCoinIdentifierArgument.suggestIds(data.getAccounts(suggestedOwner).keySet(), builder);
            }
            var other = getAccount(data, otherOwner, otherId);
            var ids = data.getAccounts(suggestedOwner, other.currencyId()).keySet().stream()
                    .filter(id -> !suggestedOwner.equals(otherOwner) || !id.equals(other.getId()))
                    .toList();
            return PolyCoinIdentifierArgument.suggestIds(ids, builder);
        } catch (CommandSyntaxException exception) {
            return builder.buildFuture();
        }
    }
}
