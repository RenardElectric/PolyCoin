package polycube.polycoin.commands;

import net.minecraft.network.chat.MutableComponent;
import polycube.polycoin.economy.PolyCoinEconomyAccount;
import polycube.polycoin.economy.PolyCoinEconomyCurrency;
import polycube.polycore.text.TextComponents;

public final class CommandText {
    private CommandText() {}

    public static MutableComponent account(PolyCoinEconomyAccount account) {
        return TextComponents.value(account.name()).append(TextComponents.muted(" (" + account.id().getPath() + ")"));
    }

    public static MutableComponent currency(PolyCoinEconomyCurrency currency) {
        return TextComponents.value(currency.name()).append(TextComponents.muted(" (" + currency.id().getPath() + ")"));
    }
}
