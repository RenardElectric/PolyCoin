package polycube.polycoin.commands.commandArguments;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import polycube.polycoin.PolyCoin;
import polycube.polycoin.economy.PolyCoinEconomyCurrency;
import polycube.polycoin.economy.PolyCoinEconomyData;
import polycube.polycore.commands.PolyCommand;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public final class CurrencyArgument {
    private static final Function<PolyCommand, DynamicCommandExceptionType> INVALID_CURRENCY =
            command -> new DynamicCommandExceptionType(
                    id -> command.textComponents.error("Invalid currency id: " + id)
            );

    private CurrencyArgument() {}

    public static PolyCoinEconomyCurrency getCurrency(PolyCommand command, CommandSourceStack source, @Nullable String rawId) throws CommandSyntaxException {
        return getCurrency(command, PolyCoin.INSTANCE.getData(source.getServer()), rawId);
    }

    public static String parseId(PolyCommand command, String rawId) throws CommandSyntaxException {
        Identifier id = PolyCoinIdentifierArgument.parse(rawId);
        if (id == null) throw INVALID_CURRENCY.apply(command).create(rawId);
        return id.getPath();
    }

    public static PolyCoinEconomyCurrency getCurrency(PolyCommand command, PolyCoinEconomyData data, @Nullable String rawId) throws CommandSyntaxException {
        return command.commandResult.require(data.getCurrency(rawId == null ? data.getDefaultCurrency() : parseId(command, rawId)));
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
