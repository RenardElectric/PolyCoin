package polycube.polycoin.commands.commandArguments;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
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
        try {
            return context.getArgument(name, String.class);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public static CompletableFuture<Suggestions> suggestIds(
            Collection<String> ids, SuggestionsBuilder builder, String... literalSiblings
    ) {
        if (literalSiblings.length == 0) return SharedSuggestionProvider.suggest(ids, builder);
        var literals = List.of(literalSiblings);
        String remaining = builder.getRemainingLowerCase();
        boolean quoted = remaining.startsWith("\"");
        if (quoted) remaining = remaining.substring(1);
        for (String id : ids) {
            if (SharedSuggestionProvider.matchesSubStr(remaining, id.toLowerCase(Locale.ROOT))) {
                // Quoting prevents Brigadier from treating an ID as a literal subcommand.
                builder.suggest(quoted || literals.contains(id) ? "\"" + id + "\"" : id);
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
