package polycube.polycoin.commands.commandArguments;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import polycube.polycoin.PolyCoin;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class PolyCoinIdentifierArgument {
    private PolyCoinIdentifierArgument() {}

    public static @Nullable String getOptionalId(CommandContext<?> context, String name) {
        return hasArgument(context, name) ? context.getArgument(name, String.class) : null;
    }

    public static boolean hasArgument(CommandContext<?> context, String name) {
        return context.getNodes().stream().anyMatch(node ->
                node.getNode() instanceof ArgumentCommandNode<?, ?> && node.getNode().getName().equals(name));
    }

    public static CompletableFuture<Suggestions> suggestIds(
            Collection<String> ids, SuggestionsBuilder builder, String... literalSiblings
    ) {
        var literals = List.of(literalSiblings);
        String remaining = builder.getRemainingLowerCase();
        boolean quoted = remaining.startsWith("\"");
        if (quoted) remaining = remaining.substring(1);
        for (String id : ids) {
            if (SharedSuggestionProvider.matchesSubStr(remaining, id.toLowerCase(Locale.ROOT))) {
                // Quotes also disambiguate numeric currency IDs from leaderboard limits.
                boolean needsQuotes = id.chars().allMatch(Character::isDigit)
                        || id.chars().anyMatch(character -> !StringReader.isAllowedInUnquotedString((char) character));
                builder.suggest(quoted || needsQuotes || literals.contains(id) ? "\"" + id + "\"" : id);
            }
        }
        return builder.buildFuture();
    }

    public static @Nullable Identifier parse(String rawId) {
        String id = rawId.indexOf(':') < 0 ? PolyCoin.MOD_ID + ":" + rawId : rawId;
        Identifier parsed = Identifier.tryParse(id);
        if (parsed == null || !PolyCoin.MOD_ID.equals(parsed.getNamespace())) return null;
        return parsed;
    }
}
