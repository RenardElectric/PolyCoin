package polycube.polycoin.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.PermissionLevel;
import org.jspecify.annotations.Nullable;
import polycube.polycoin.PolyCoin;
import polycube.polycoin.commands.commandArguments.AccountArgument;
import polycube.polycoin.commands.commandArguments.AmountArgument;
import polycube.polycoin.commands.commandArguments.PolyCoinIdentifierArgument;

import java.util.Locale;

public final class BalanceCommand extends PolyCoinCommand {
    private enum BalanceOperation { SET, ADD, REMOVE }

    public BalanceCommand() {
        super("balance", "Displays a player's balance; set, add, and remove are admin-only",
                "[account] [<set|add|remove> <amount>]", PermissionLevel.ALL, true);
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> getCommand(String name) {
        var command = super.getCommand(name).executes(context -> showBalance(context, null));
        var account = Commands.argument("account", StringArgumentType.string())
                .suggests((context, builder) -> AccountArgument.suggestAccounts(context, builder, "set", "add", "remove"))
                .executes(context -> showBalance(context, StringArgumentType.getString(context, "account")));
        for (BalanceOperation operation : BalanceOperation.values()) {
            var adjustment = Commands.literal(operation.name().toLowerCase(Locale.ROOT))
                    .requires(source -> hasPermission(source, PermissionLevel.GAMEMASTERS))
                    .then(Commands.argument("amount", StringArgumentType.word())
                            .executes(context -> adjustBalance(context, operation)));
            account.then(adjustment);
            command.then(adjustment);
        }
        return command.then(account);
    }

    private static int showBalance(CommandContext<CommandSourceStack> context, @Nullable String accountId) throws CommandSyntaxException {
        var source = context.getSource();
        var owner = AccountArgument.getOwner(context);
        var data = PolyCoin.INSTANCE.getData(source.getServer());
        var account = AccountArgument.getAccount(data, owner, accountId);
        var message = CommandText.header("Balance")
                .append(CommandText.field("Owner", CommandText.value(owner.name())))
                .append(CommandText.field("Account", CommandText.account(account)))
                .append(CommandText.field("Available", CommandText.amount(account.formattedBalance())));
        source.sendSuccess(() -> message, false);
        return 1;
    }

    private static int adjustBalance(CommandContext<CommandSourceStack> context, BalanceOperation operation) throws CommandSyntaxException {
        var source = context.getSource();
        var owner = AccountArgument.getOwner(context);
        var amount = AmountArgument.parse(StringArgumentType.getString(context, "amount"), operation == BalanceOperation.SET);
        var data = PolyCoin.INSTANCE.getData(source.getServer());
        Component message;

        // Use the same lock order as transfers and account management.
        synchronized (data) {
            var account = AccountArgument.getAccount(data, owner, PolyCoinIdentifierArgument.getOptionalId(context, "account"));
            synchronized (account) {
                var previousBalance = account.balance();
                if (operation == BalanceOperation.SET) {
                    account.setBalance(amount);
                } else {
                    var transaction = operation == BalanceOperation.ADD
                            ? account.increaseBalance(amount)
                            : account.decreaseBalance(amount);
                    if (transaction.isFailure()) {
                        source.sendFailure(CommandText.error(transaction.message()));
                        return 0;
                    }
                }
                var currency = account.currency();
                message = CommandText.success("Balance updated")
                        .append(CommandText.field("Owner", CommandText.value(owner.name())))
                        .append(CommandText.field("Account", CommandText.account(account)))
                        .append(CommandText.field("Before", CommandText.amount(currency.formatValueComponent(previousBalance, true))))
                        .append(CommandText.field("Now", CommandText.amount(account.formattedBalance())));
            }
        }

        source.sendSuccess(() -> message, true);
        return 1;
    }
}
