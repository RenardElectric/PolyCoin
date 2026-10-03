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
import polycube.polycore.commands.PolyCommand;
import polycube.polycore.text.TextComponents;

import java.util.Locale;

public final class BalanceCommand extends PolyCommand {
    private enum BalanceOperation { SET, ADD, REMOVE }

    public BalanceCommand() {
        super(
                "balance",
                "Displays a player's balance; set, add, and remove are admin-only",
                PermissionLevel.ALL,
                true
        );
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> getCommand(String name) {
        var command = super.getCommand(name).executes(context -> showBalance(context, null));
        var account = Commands.argument("account", StringArgumentType.string())
                .suggests((context, builder) -> AccountArgument.suggestAccounts(this, context, builder, "help", "set", "add", "remove"))
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

    private int showBalance(CommandContext<CommandSourceStack> context, @Nullable String accountId) throws CommandSyntaxException {
        var source = context.getSource();
        var owner = AccountArgument.getOwner(this, context);
        var data = PolyCoin.INSTANCE.getData(source.getServer());
        var account = AccountArgument.getAccount(this, data, owner.id(), accountId);
        var message = textComponents.header("Balance")
                .append(TextComponents.field("Owner", TextComponents.value(owner.name())))
                .append(TextComponents.field("Account", CommandText.account(account)))
                .append(TextComponents.field("Available", TextComponents.amount(account.formattedBalance())));
        source.sendSuccess(() -> message, false);
        return 1;
    }

    private int adjustBalance(CommandContext<CommandSourceStack> context, BalanceOperation operation) throws CommandSyntaxException {
        var source = context.getSource();
        var owner = AccountArgument.getOwner(this, context);
        var amount = AmountArgument.parse(this, StringArgumentType.getString(context, "amount"), operation == BalanceOperation.SET);
        var data = PolyCoin.INSTANCE.getData(source.getServer());
        Component message;

        // Keep the before/after snapshot and adjustment under the economy's sole monitor.
        synchronized (data) {
            var account = AccountArgument.getAccount(this, data, owner.id(), PolyCoinIdentifierArgument.getOptionalId(context, "account"));
            var currency = commandResult.require(account.getCurrency());
            var previousBalance = account.balance();
            if (operation == BalanceOperation.SET) {
                commandResult.require(account.trySetBalance(amount));
            } else {
                var transaction = operation == BalanceOperation.ADD
                        ? account.increaseBalance(amount)
                        : account.decreaseBalance(amount);
                if (transaction.isFailure()) {
                    source.sendFailure(textComponents.error(transaction.message()));
                    return 0;
                }
            }
            message = textComponents.success("Balance updated")
                    .append(TextComponents.field("Owner", TextComponents.value(owner.name())))
                    .append(TextComponents.field("Account", CommandText.account(account)))
                    .append(TextComponents.field("Before", TextComponents.amount(currency.formatValueComponent(previousBalance, true))))
                    .append(TextComponents.field("Now", TextComponents.amount(account.formattedBalance())));
        }

        source.sendSuccess(() -> message, true);
        return 1;
    }
}
