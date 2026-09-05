package polycube.polycoin.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;
import org.jspecify.annotations.Nullable;
import polycube.polycoin.PolyCoin;
import polycube.polycoin.commands.commandArguments.CurrencyArgument;
import polycube.polycoin.economy.PolyCoinEconomyCurrency;
import polycube.polycoin.economy.PolyCoinEconomyData;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class BalanceTopCommand extends PolyCoinCommand {
    private static final int ENTRY_LIMIT = 10;

    public BalanceTopCommand() {
        super(
                "balancetop",
                "Displays the top accounts by balance for a given currency.",
                "[limit] [currency]",
                PermissionLevel.ALL,
                true,
                List.of("baltop")
        );
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> getCommand(String name) {
        return super.getCommand(name).then(
                Commands.argument("limit", IntegerArgumentType.integer(1))
                        .executes(context -> showLeaderboard(
                                context.getSource(),
                                IntegerArgumentType.getInteger(context, "limit"),
                                null
                        ))
                        .then(
                                Commands.argument("currency", StringArgumentType.word())
                                        .suggests(CurrencyArgument::suggestCurrencies)
                                        .executes(context -> showLeaderboard(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "limit"),
                                                StringArgumentType.getString(context, "currency")
                                        ))
                        )
        );
    }

    @Override
    protected int execute(CommandSourceStack source) {
        return showLeaderboard(source, ENTRY_LIMIT, null);
    }

    private int showLeaderboard(CommandSourceStack source, int limit, @Nullable String currencyId) {
        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(source.getServer());
        PolyCoinEconomyCurrency currency = currencyId == null ? data.getDefaultCurrency() : data.getCurrency(currencyId);

        if (currency == null) {
            source.sendFailure(Component.literal("Unknown currency: " + currencyId));
            return 0;
        }

        var entries = data.getTopAccounts(currency, limit);
        var message = Component.literal("Top accounts for ").append(currency.name()).append(":");

        if (entries.isEmpty()) {
            message.append("\nNo accounts found.");
        } else {
            for (int index = 0; index < entries.size(); index++) {
                var entry = entries.get(index);
                message.append("\n")
                        .append(Component.literal((index + 1) + ". "))
                        .append(ownerName(source, entry.owner()))
                        .append(" - ")
                        .append(entry.accountName())
                        .append(": ")
                        .append(currency.formatValueComponent(entry.balance(), true));
            }
        }

        source.sendSuccess(() -> message, false);
        return 1;
    }

    private static Component ownerName(CommandSourceStack source, UUID owner) {
        var onlinePlayer = source.getServer().getPlayerList().getPlayer(owner);
        if (onlinePlayer != null) {
            return onlinePlayer.getDisplayName();
        }

        return Component.literal(source.getServer().services().nameToIdCache().get(owner)
                .map(NameAndId::name)
                .orElse(owner.toString()));
    }
}
