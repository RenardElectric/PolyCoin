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
import polycube.polycore.commands.PolyCommand;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public final class AccountArgument {
    public static final String OWNER_ARGUMENT = "asPlayer";
    private static final Function<PolyCommand, SimpleCommandExceptionType> SINGLE_OWNER_REQUIRED =
            command -> new SimpleCommandExceptionType(
                    command.textComponents.error("Select exactly one account owner.")
            );
    private static final Function<PolyCommand, DynamicCommandExceptionType> INVALID_ACCOUNT =
            command -> new DynamicCommandExceptionType(
                    id -> command.textComponents.error("Invalid account id: " + id)
            );
    private static final Function<PolyCommand, DynamicCommandExceptionType> MISSING_DEFAULT =
            command -> new DynamicCommandExceptionType(
                    currency -> command.textComponents.error("No default account for currency: " + currency)
            );

    private AccountArgument() {}

    public static boolean isActingAs(CommandContext<CommandSourceStack> context) {
        return PolyCoinIdentifierArgument.hasArgument(context, OWNER_ARGUMENT);
    }

    public static GameProfile getOwner(PolyCommand command, CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        if (!isActingAs(context)) return context.getSource().getPlayerOrException().getGameProfile();

        var owners = GameProfileArgument.getGameProfiles(context, OWNER_ARGUMENT);
        if (owners.size() != 1) throw SINGLE_OWNER_REQUIRED.apply(command).create();
        var owner = owners.iterator().next();
        return new GameProfile(owner.id(), owner.name());
    }

    public static String parseId(PolyCommand command, String rawId) throws CommandSyntaxException {
        Identifier id = PolyCoinIdentifierArgument.parse(rawId);
        if (id == null) throw INVALID_ACCOUNT.apply(command).create(rawId);
        return id.getPath();
    }

    public static PolyCoinEconomyAccount getAccount(
            PolyCommand command, PolyCoinEconomyData data,
            UUID owner, @Nullable String rawId
    ) throws CommandSyntaxException {
        return rawId == null
                ? getDefaultAccount(command, data, owner, data.getDefaultCurrency())
                : command.commandResult.require(data.getAccount(owner, parseId(command, rawId)));
    }

    private static PolyCoinEconomyAccount getDefaultAccount(
            PolyCommand command, PolyCoinEconomyData data,
            UUID owner, String currencyId
    ) throws CommandSyntaxException {
        String id = data.getDefaultAccountId(owner, currencyId);
        if (id == null) throw MISSING_DEFAULT.apply(command).create(currencyId);
        return command.commandResult.require(data.getAccount(owner, id));
    }

    public record TransferAccounts(PolyCoinEconomyAccount source, PolyCoinEconomyAccount target) {}

    /// An explicit account selects the currency for the omitted side.
    /// If both are omitted, use each owner's default for the default currency.
    public static TransferAccounts getTransferAccounts(
            PolyCommand command, PolyCoinEconomyData data,
            UUID sourceOwner, @Nullable String sourceId,
            UUID targetOwner, @Nullable String targetId
    ) throws CommandSyntaxException {
        var source = sourceId == null ? null : getAccount(command, data, sourceOwner, sourceId);
        var target = targetId == null ? null : getAccount(command, data, targetOwner, targetId);
        if (source == null) {
            source = getDefaultAccount(command, data, sourceOwner, target == null ? data.getDefaultCurrency() : target.currencyId());
        }
        if (target == null) target = getDefaultAccount(command, data, targetOwner, source.currencyId());
        return new TransferAccounts(source, target);
    }

    /// Shared vanilla-client-compatible amount/from/to tree, in either argument order.
    public static RequiredArgumentBuilder<CommandSourceStack, String> transferArguments(
            PolyCommand command, @Nullable String playerKey,
            Command<CommandSourceStack> execute
    ) {
        var amount = Commands.argument("amount", StringArgumentType.word()).executes(execute);
        for (String side : new String[]{"from", "to"}) {
            String other = side.equals("from") ? "to" : "from";
            amount.then(Commands.literal(side).then(
                    Commands.argument(side, StringArgumentType.string())
                            .suggests((context, builder) -> suggestTransferAccounts(command, context, builder, playerKey, side, other))
                            .executes(execute)
                            .then(Commands.literal(other).then(
                                    Commands.argument(other, StringArgumentType.string())
                                            .suggests((context, builder) -> suggestTransferAccounts(command, context, builder, playerKey, other, side))
                                            .executes(execute)
                            ))
            ));
        }
        return amount;
    }

    public static CompletableFuture<Suggestions> suggestAccounts(
            PolyCommand command, CommandContext<CommandSourceStack> context,
            SuggestionsBuilder builder, String... literalSiblings
    ) {
        try {
            var data = PolyCoin.INSTANCE.getData(context.getSource().getServer());
            return PolyCoinIdentifierArgument.suggestIds(data.getAccounts(getOwner(command, context).id()).keySet(), builder, literalSiblings);
        } catch (CommandSyntaxException exception) {
            return builder.buildFuture();
        }
    }

    private static CompletableFuture<Suggestions> suggestTransferAccounts(
            PolyCommand command, CommandContext<CommandSourceStack> context,
            SuggestionsBuilder builder, @Nullable String playerKey,
            String side, String otherSide
    ) {
        try {
            UUID sourceOwner = getOwner(command, context).id();
            UUID targetOwner = playerKey == null ? sourceOwner : EntityArgument.getPlayer(context, playerKey).getUUID();
            UUID suggestedOwner = side.equals("from") ? sourceOwner : targetOwner;
            UUID otherOwner = side.equals("from") ? targetOwner : sourceOwner;
            var data = PolyCoin.INSTANCE.getData(context.getSource().getServer());
            String otherId = PolyCoinIdentifierArgument.getOptionalId(context, otherSide);
            // The first explicit account may select any currency; the second must match it.
            if (otherId == null) {
                return PolyCoinIdentifierArgument.suggestIds(data.getAccounts(suggestedOwner).keySet(), builder);
            }
            var other = getAccount(command, data, otherOwner, otherId);
            var ids = data.getAccounts(suggestedOwner, other.currencyId()).keySet().stream()
                    .filter(id -> !suggestedOwner.equals(otherOwner) || !id.equals(other.getId()))
                    .toList();
            return PolyCoinIdentifierArgument.suggestIds(ids, builder);
        } catch (CommandSyntaxException exception) {
            return builder.buildFuture();
        }
    }
}
