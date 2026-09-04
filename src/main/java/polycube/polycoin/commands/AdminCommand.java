package polycube.polycoin.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.PermissionLevel;
import org.jspecify.annotations.Nullable;
import polycube.polycoin.PolyCoin;
import polycube.polycoin.commands.commandArguments.AccountArgument;
import polycube.polycoin.commands.commandArguments.AmountArgument;
import polycube.polycoin.economy.PolyCoinEconomyData;

import java.util.Locale;

public final class AdminCommand extends PolyCoinCommand {
    private static final String OWNER_ARGUMENT = "player";

    private enum BalanceOperation { SET, ADD, REMOVE }

    public AdminCommand() {
        super(
                "admin",
                "Manages balances and accounts for a player, including offline players",
                "balance <player> [<account> [<set|add|remove> <amount>]] | account <player> <list|info|transfer|create|delete|modify> ...",
                PermissionLevel.GAMEMASTERS
        );
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> getCommand(String name, CommandBuildContext buildContext) {
        return super.getCommand(name, buildContext)
                .then(balanceCommand())
                .then(Commands.literal("account").then(
                        new AccountCommand(OWNER_ARGUMENT).addSubcommands(
                                Commands.argument(OWNER_ARGUMENT, GameProfileArgument.gameProfile()), buildContext
                        )
                ));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> balanceCommand() {
        var account = Commands.argument("account", StringArgumentType.string())
                .suggests((context, builder) -> AccountArgument.suggestAccounts(context, builder, OWNER_ARGUMENT))
                .executes(context -> showBalance(context, StringArgumentType.getString(context, "account")));
        for (BalanceOperation operation : BalanceOperation.values()) {
            account.then(Commands.literal(operation.name().toLowerCase(Locale.ROOT)).then(
                    Commands.argument("amount", StringArgumentType.word())
                            .executes(context -> adjustBalance(context, operation))
            ));
        }
        return Commands.literal("balance").then(
                Commands.argument(OWNER_ARGUMENT, GameProfileArgument.gameProfile())
                        .executes(context -> showBalance(context, null))
                        .then(account)
        );
    }

    private static int showBalance(CommandContext<CommandSourceStack> context, @Nullable String accountId) throws CommandSyntaxException {
        var source = context.getSource();
        var owner = AccountArgument.getOwner(context, OWNER_ARGUMENT);
        var data = PolyCoin.INSTANCE.getData(source.getServer());
        var account = AccountArgument.getAccount(data, owner, accountId == null ? PolyCoinEconomyData.MAIN_ACCOUNT_KEY : accountId);
        var message = Component.literal(owner.name() + " - " + account.id().getPath() + " - ")
                .append(account.name()).append(" balance: ").append(account.formattedBalance());
        source.sendSuccess(() -> message, false);
        return 1;
    }

    private static int adjustBalance(CommandContext<CommandSourceStack> context, BalanceOperation operation) throws CommandSyntaxException {
        var source = context.getSource();
        var owner = AccountArgument.getOwner(context, OWNER_ARGUMENT);
        var amount = AmountArgument.parse(StringArgumentType.getString(context, "amount"), operation == BalanceOperation.SET);
        var data = PolyCoin.INSTANCE.getData(source.getServer());
        Component message;

        // Use the same lock order as transfers and account management.
        synchronized (data) {
            var account = AccountArgument.getAccount(data, owner, StringArgumentType.getString(context, "account"));
            synchronized (account) {
                var previousBalance = account.balance();
                if (operation == BalanceOperation.SET) {
                    account.setBalance(amount);
                } else {
                    var transaction = operation == BalanceOperation.ADD
                            ? account.increaseBalance(amount)
                            : account.decreaseBalance(amount);
                    if (transaction.isFailure()) {
                        source.sendFailure(transaction.message());
                        return 0;
                    }
                }
                var currency = account.currency();
                message = Component.literal("Updated " + owner.name() + " / " + account.id().getPath() + ": ")
                        .append(currency.formatValueComponent(previousBalance, true))
                        .append(" -> ").append(account.formattedBalance());
            }
        }

        source.sendSuccess(() -> message, true);
        return 1;
    }
}
