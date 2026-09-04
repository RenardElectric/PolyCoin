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
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;
import polycube.polycoin.PolyCoin;
import polycube.polycoin.economy.PolyCoinEconomyAccount;
import polycube.polycoin.economy.PolyCoinEconomyData;

import java.util.concurrent.CompletableFuture;

public final class AccountArgument {
    private static final SimpleCommandExceptionType SINGLE_OWNER_REQUIRED = new SimpleCommandExceptionType(
            Component.literal("Select exactly one account owner.")
    );
    private static final DynamicCommandExceptionType UNKNOWN_ACCOUNT = new DynamicCommandExceptionType(
            id -> Component.literal("Unknown account: " + id)
    );
    private static final DynamicCommandExceptionType INVALID_ACCOUNT = new DynamicCommandExceptionType(
            id -> Component.literal("Invalid account id: " + id)
    );

    public enum AccountSide {
        SOURCE,
        TARGET
    }

    private AccountArgument() {}

    public static GameProfile getOwner(
            CommandContext<CommandSourceStack> context, @Nullable String ownerArgument
    ) throws CommandSyntaxException {
        if (ownerArgument == null) return context.getSource().getPlayerOrException().getGameProfile();

        var owners = GameProfileArgument.getGameProfiles(context, ownerArgument);
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
            PolyCoinEconomyData data, GameProfile owner, String rawId
    ) throws CommandSyntaxException {
        PolyCoinEconomyAccount account = data.getAccount(owner, parseId(rawId).getPath());
        if (account == null) throw UNKNOWN_ACCOUNT.create(rawId);
        return account;
    }

    public static CompletableFuture<Suggestions> suggestAccounts(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder
    ) {
        return suggestAccounts(context, builder, null);
    }

    public static CompletableFuture<Suggestions> suggestAccounts(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder, @Nullable String ownerArgument
    ) {
        try {
            GameProfile owner = getOwner(context, ownerArgument);
            PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(context.getSource().getServer());
            return SharedSuggestionProvider.suggest(data.getAccountIds(owner), builder);
        } catch (CommandSyntaxException exception) {
            return builder.buildFuture();
        }
    }

    public static CompletableFuture<Suggestions> suggestTransferTargets(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder,
            @Nullable String ownerArgument, String sourceAccountArgument
    ) {
        try {
            GameProfile owner = getOwner(context, ownerArgument);
            PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(context.getSource().getServer());
            Identifier sourceId = parseId(StringArgumentType.getString(context, sourceAccountArgument));
            var currency = data.getAccountCurrency(owner, sourceId.getPath());
            if (currency == null) return builder.buildFuture();
            return SharedSuggestionProvider.suggest(
                    data.getAccountIds(owner, currency).stream().filter(id -> !id.equals(sourceId.getPath())), builder
            );
        } catch (CommandSyntaxException exception) {
            return builder.buildFuture();
        }
    }

    public static CompletableFuture<Suggestions> suggestTargetAccountsForMain(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder, String playerKey
    ) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(context, playerKey);
        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(context.getSource().getServer());
        return SharedSuggestionProvider.suggest(
                data.getAccountIds(target.getGameProfile(), data.getMainCurrency()),
                builder
        );
    }

    public static CompletableFuture<Suggestions> suggestMatchingAccounts(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder,
            String playerKey, String otherAccountKey, AccountSide suggestedSide
    ) throws CommandSyntaxException {
        ServerPlayer source = context.getSource().getPlayer();
        if (source == null) return builder.buildFuture();

        ServerPlayer target = EntityArgument.getPlayer(context, playerKey);
        ServerPlayer suggestedOwner = suggestedSide == AccountSide.SOURCE ? source : target;
        ServerPlayer otherOwner = suggestedSide == AccountSide.SOURCE ? target : source;
        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(context.getSource().getServer());
        var currency = data.getAccountCurrency(
                otherOwner.getGameProfile(),
                StringArgumentType.getString(context, otherAccountKey)
        );

        return currency == null
                ? builder.buildFuture()
                : SharedSuggestionProvider.suggest(data.getAccountIds(suggestedOwner.getGameProfile(), currency), builder);
    }
}
