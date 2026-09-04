package polycube.polycoin.commands.commandArguments;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
import polycube.polycoin.PolyCoin;
import polycube.polycoin.economy.PolyCoinEconomyData;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

public final class AccountArgument {
    public enum AccountSide {
        SOURCE,
        TARGET
    }

    private AccountArgument() {}

    public static CompletableFuture<Suggestions> suggestAccounts(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder
    ) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return builder.buildFuture();

        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(context.getSource().getServer());
        return SharedSuggestionProvider.suggest(data.getAccountIds(player.getGameProfile()), builder);
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
