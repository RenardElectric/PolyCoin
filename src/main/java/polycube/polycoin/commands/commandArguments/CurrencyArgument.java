package polycube.polycoin.commands.commandArguments;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import polycube.polycoin.PolyCoin;
import polycube.polycoin.economy.PolyCoinEconomyCurrency;
import polycube.polycoin.economy.PolyCoinEconomyData;

import java.util.concurrent.CompletableFuture;

public final class CurrencyArgument {
    private CurrencyArgument() {}

    public static @Nullable PolyCoinEconomyCurrency getCurrency(CommandSourceStack source, @Nullable String rawId) {
        return getCurrency(source, PolyCoin.INSTANCE.getData(source.getServer()), rawId);
    }

    public static @Nullable PolyCoinEconomyCurrency getCurrency(
            CommandSourceStack source, PolyCoinEconomyData data, @Nullable String rawId
    ) {
        if (rawId == null) return data.getDefaultCurrency();
        Identifier id = PolyCoinIdentifierArgument.parse(rawId);
        if (id == null) {
            source.sendFailure(Component.literal("Invalid currency id: " + rawId));
            return null;
        }
        PolyCoinEconomyCurrency currency = data.getCurrency(id);
        if (currency == null) source.sendFailure(Component.literal("Unknown currency: " + rawId));
        return currency;
    }

    public static CompletableFuture<Suggestions> suggestCurrencies(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return suggestCurrencies(context, builder, new String[0]);
    }

    public static CompletableFuture<Suggestions> suggestCurrencies(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder, String... literalSiblings
    ) {
        return PolyCoinIdentifierArgument.suggestIds(
                PolyCoin.INSTANCE.getData(context.getSource().getServer()).getCurrencies().keySet(),
                builder, literalSiblings
        );
    }
}
