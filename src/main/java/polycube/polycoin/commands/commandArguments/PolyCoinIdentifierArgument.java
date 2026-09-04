package polycube.polycoin.commands.commandArguments;

import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import polycube.polycoin.PolyCoin;

public final class PolyCoinIdentifierArgument {
    private PolyCoinIdentifierArgument() {}

    public static @Nullable Identifier parse(String rawId) {
        String id = rawId.indexOf(':') < 0 ? PolyCoin.MOD_ID + ":" + rawId : rawId;
        Identifier parsed = Identifier.tryParse(id);
        if (parsed == null || !PolyCoin.MOD_ID.equals(parsed.getNamespace())) return null;
        return parsed;
    }
}
