package polycube.polycoin.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.PermissionLevel;
import org.jspecify.annotations.Nullable;
import polycube.polycoin.PolyCoin;
import polycube.polycoin.commands.commandArguments.CurrencyArgument;
import polycube.polycoin.economy.PolyCoinEconomyCurrency;
import polycube.polycoin.economy.PolyCoinEconomyData;
import polycube.polycoin.util.Helpers;
import polycube.polycore.commands.CommandResult;
import polycube.polycore.commands.PolyCommand;
import polycube.polycore.text.TextComponents;

import java.util.List;

public final class BalanceTopCommand extends PolyCommand {
    private static final int ENTRY_LIMIT = 10;
    private static final int MAX_ENTRY_LIMIT = 100;

    public BalanceTopCommand() {
        super(
                PolyCoin.MOD_ID,
                "balancetop",
                "Displays the top accounts by balance for a given currency.",
                "[currency] | <limit:1-100> [currency]",
                PermissionLevel.ALL,
                true,
                List.of("baltop")
        );
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> getCommand(String name) {
        return super.getCommand(name).then(
                Commands.argument("limit", IntegerArgumentType.integer(1, MAX_ENTRY_LIMIT))
                        .executes(context -> showLeaderboard(
                                context.getSource(),
                                IntegerArgumentType.getInteger(context, "limit"),
                                null
                        ))
                        .then(
                                Commands.argument("currency", StringArgumentType.string())
                                        .suggests(CurrencyArgument::suggestCurrencies)
                                        .executes(context -> showLeaderboard(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "limit"),
                                                StringArgumentType.getString(context, "currency")
                                        ))
                        )
        ).then(Commands.argument("currency", StringArgumentType.string())
                .suggests((context, builder) -> CurrencyArgument.suggestCurrencies(context, builder, "help"))
                .executes(context -> showLeaderboard(context.getSource(), ENTRY_LIMIT,
                        StringArgumentType.getString(context, "currency"))));
    }

    @Override
    protected int execute(CommandSourceStack source) throws CommandSyntaxException {
        return showLeaderboard(source, ENTRY_LIMIT, null);
    }

    private int showLeaderboard(CommandSourceStack source, int limit, @Nullable String currencyId) throws CommandSyntaxException {
        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(source.getServer());
        PolyCoinEconomyCurrency currency = CurrencyArgument.getCurrency(data, currencyId);
        var entries = CommandResult.require(data.getTopAccounts(currency.getId(), limit));
        var message = TextComponents.header("Top balances")
                .append(TextComponents.field("Currency", CommandText.currency(currency)))
                .append(TextComponents.muted(" • " + entries.size() + " account(s)"));

        if (entries.isEmpty()) {
            message.append("\nNo accounts found.");
        } else {
            for (int index = 0; index < entries.size(); index++) {
                var entry = entries.get(index);
                message.append("\n  ")
                        .append(Component.literal("#" + (index + 1) + " ")
                                .withStyle(index == 0 ? ChatFormatting.GOLD : ChatFormatting.GRAY))
                        .append(TextComponents.value(Helpers.playerNames(source.getServer(), entry.owners())))
                        .append(" • ").append(TextComponents.value(entry.accountName()))
                        .append(TextComponents.muted(" (" + entry.accountId().getPath() + ")"))
                        .append("\n    ").append(TextComponents.amount(currency.formatValueComponent(entry.balance(), true)));
            }
        }

        source.sendSuccess(() -> message, false);
        return 1;
    }
}
