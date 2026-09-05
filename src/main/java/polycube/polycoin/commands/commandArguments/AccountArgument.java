package polycube.polycoin.commands.commandArguments;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;
import polycube.polycoin.PolyCoin;
import polycube.polycoin.commands.CommandText;
import polycube.polycoin.economy.PolyCoinEconomyAccount;
import polycube.polycoin.economy.PolyCoinEconomyData;

import java.util.concurrent.CompletableFuture;

public final class AccountArgument {
    public static final String OWNER_ARGUMENT = "asPlayer";
    private static final SimpleCommandExceptionType SINGLE_OWNER_REQUIRED = new SimpleCommandExceptionType(
            CommandText.error("Select exactly one account owner.")
    );
    private static final DynamicCommandExceptionType UNKNOWN_ACCOUNT = new DynamicCommandExceptionType(
            id -> CommandText.error("Unknown account: " + id)
    );
    private static final DynamicCommandExceptionType INVALID_ACCOUNT = new DynamicCommandExceptionType(
            id -> CommandText.error("Invalid account id: " + id)
    );

    public enum AccountSide {
        SOURCE,
        TARGET
    }

    private AccountArgument() {}

    public static boolean isActingAs(CommandContext<CommandSourceStack> context) {
        try {
            context.getArgument(OWNER_ARGUMENT, GameProfileArgument.Result.class);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public static GameProfile getOwner(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        if (!isActingAs(context)) return context.getSource().getPlayerOrException().getGameProfile();

        var owners = GameProfileArgument.getGameProfiles(context, OWNER_ARGUMENT);
        if (owners.size() != 1) throw SINGLE_OWNER_REQUIRED.create();
        var owner = owners.iterator().next();
        return new GameProfile(owner.id(), owner.name());
    }

    public static Identifier parseId(String rawId) throws CommandSyntaxException {
        Identifier id = PolyCoinIdentifierArgument.parse(rawId);
        if (id == null) throw INVALID_ACCOUNT.create(rawId);
        return id;
    }

    public static PolyCoinEconomyAccount getAccount(
            PolyCoinEconomyData data, GameProfile owner, @Nullable String rawId
    ) throws CommandSyntaxException {
        if (rawId == null) return data.getDefaultAccount(owner);
        PolyCoinEconomyAccount account = data.getAccount(owner, parseId(rawId).getPath());
        if (account == null) throw UNKNOWN_ACCOUNT.create(rawId);
        return account;
    }

    public static CompletableFuture<Suggestions> suggestAccounts(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder
    ) {
        return suggestAccounts(context, builder, new String[0]);
    }

    public static CompletableFuture<Suggestions> suggestAccounts(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder,
            String... literalSiblings
    ) {
        try {
            GameProfile owner = getOwner(context);
            PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(context.getSource().getServer());
            return PolyCoinIdentifierArgument.suggestIds(data.getAccountIds(owner), builder, literalSiblings);
        } catch (CommandSyntaxException exception) {
            return builder.buildFuture();
        }
    }

    public static CompletableFuture<Suggestions> suggestTransferTargets(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder,
            String sourceAccountArgument
    ) {
        try {
            GameProfile owner = getOwner(context);
            PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(context.getSource().getServer());
            String rawId = PolyCoinIdentifierArgument.getOptionalId(context, sourceAccountArgument);
            Identifier sourceId = parseId(rawId == null ? data.getDefaultAccountId(owner.id()) : rawId);
            var currency = data.getAccountCurrency(owner, sourceId.getPath());
            if (currency == null) return builder.buildFuture();
            return SharedSuggestionProvider.suggest(
                    data.getAccountIds(owner, currency).stream().filter(id -> !id.equals(sourceId.getPath())), builder
            );
        } catch (CommandSyntaxException exception) {
            return builder.buildFuture();
        }
    }

    public static CompletableFuture<Suggestions> suggestTargetAccountsForDefault(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder, String playerKey
    ) {
        try {
            GameProfile source = getOwner(context);
            ServerPlayer target = EntityArgument.getPlayer(context, playerKey);
            PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(context.getSource().getServer());
            var currency = data.getAccountCurrency(source, data.getDefaultAccountId(source.id()));
            if (currency == null) return builder.buildFuture();
            return SharedSuggestionProvider.suggest(
                    data.getAccountIds(target.getGameProfile(), currency),
                    builder
            );
        } catch (CommandSyntaxException exception) {
            return builder.buildFuture();
        }
    }

    public static CompletableFuture<Suggestions> suggestMatchingAccounts(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder,
            String playerKey, String otherAccountKey, AccountSide suggestedSide
    ) {
        try {
            GameProfile source = getOwner(context);

            ServerPlayer target = EntityArgument.getPlayer(context, playerKey);
            GameProfile suggestedOwner = suggestedSide == AccountSide.SOURCE ? source : target.getGameProfile();
            GameProfile otherOwner = suggestedSide == AccountSide.SOURCE ? target.getGameProfile() : source;
            PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(context.getSource().getServer());
            var currency = data.getAccountCurrency(
                    otherOwner,
                    parseId(StringArgumentType.getString(context, otherAccountKey)).getPath()
            );

            return currency == null
                    ? builder.buildFuture()
                    : SharedSuggestionProvider.suggest(data.getAccountIds(suggestedOwner, currency), builder);
        } catch (CommandSyntaxException exception) {
            return builder.buildFuture();
        }
    }
}
