package polycube.polycoin.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.PermissionLevel;
import polycube.polycoin.PolyCoin;
import polycube.polycoin.commands.commandArguments.AccountArgument;
import polycube.polycoin.commands.commandArguments.AmountArgument;
import polycube.polycoin.commands.commandArguments.PolyCoinIdentifierArgument;

import java.math.BigInteger;

public final class PayCommand extends PolyCoinCommand {
    public PayCommand() {
        super(
                "pay",
                "Pays another online player from one of your accounts",
                "<player> <amount> [from <account>] [to <account>]",
                PermissionLevel.ALL,
                true
        );
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> getCommand(String name) {
        return super.getCommand(name).then(
                Commands.argument("player", EntityArgument.player())
                        .suggests((context, builder) -> PolyCoinIdentifierArgument.suggestIds(
                                context.getSource().getOnlinePlayerNames(), builder, "help"
                        ))
                        .then(AccountArgument.transferArguments("player", this::pay))
        );
    }

    private int pay(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var source = context.getSource();
        var sender = AccountArgument.getOwner(context);
        var target = EntityArgument.getPlayer(context, "player");
        BigInteger amount = AmountArgument.parse(StringArgumentType.getString(context, "amount"), false);
        String sourceAccountId = PolyCoinIdentifierArgument.getOptionalId(context, "from");
        String targetAccountId = PolyCoinIdentifierArgument.getOptionalId(context, "to");

        if (sender.id().equals(target.getUUID())) {
            source.sendFailure(CommandText.error("You cannot pay yourself. Use account transfer to move money between your accounts."));
            return 0;
        }

        var data = PolyCoin.INSTANCE.getData(source.getServer());
        var accounts = AccountArgument.getTransferAccounts(data, sender.id(), sourceAccountId, target.getUUID(), targetAccountId);
        var senderAccount = accounts.source();
        var targetAccount = accounts.target();
        var currency = CommandResult.require(senderAccount.getCurrency());
        CommandResult.require(data.transfer(senderAccount.getId(), targetAccount.getId(), amount));

        var formattedAmount = CommandText.amount(currency.formatValueComponent(amount, true));
        var onlineSender = source.getServer().getPlayerList().getPlayer(sender.id());
        var senderName = onlineSender == null ? Component.literal(sender.name()) : onlineSender.getDisplayName();
        source.sendSuccess(
                () -> CommandText.success("Paid ")
                        .append(formattedAmount)
                        .append(" to ").append(CommandText.value(target.getDisplayName()))
                        .append(CommandText.field("From account", CommandText.account(senderAccount)))
                        .append(AccountArgument.isActingAs(context)
                                ? CommandText.field("On behalf of", CommandText.value(sender.name())) : Component.empty()),
                AccountArgument.isActingAs(context)
        );
        target.sendSystemMessage(
                CommandText.success("Received ")
                        .append(formattedAmount)
                        .append(" from ")
                        .append(CommandText.value(senderName))
                        .append(CommandText.field("To account", CommandText.account(targetAccount)))
        );
        return 1;
    }

}
