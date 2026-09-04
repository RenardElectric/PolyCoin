package polycube.polycoin.commands.commandArguments;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import polycube.polycoin.PolyCoin;

import java.util.concurrent.CompletableFuture;

public final class CurrencyArgument {
    private CurrencyArgument() {}

    public static CompletableFuture<Suggestions> suggestCurrencies(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(
                PolyCoin.INSTANCE.getData(context.getSource().getServer()).getCurrencies().keySet(),
                builder
        );
    }
}
