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
import polycube.polycore.commands.PolyCommand;
import polycube.polycore.text.TextComponents;

import java.math.BigInteger;

public final class PayCommand extends PolyCommand {
    public PayCommand() {
        super(
                "pay",
                "Pays another online player from one of your accounts",
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
                        .then(AccountArgument.transferArguments(this, "player", this::pay))
        );
    }

    private int pay(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var source = context.getSource();
        var sender = AccountArgument.getOwner(this, context);
        var target = EntityArgument.getPlayer(context, "player");
        BigInteger amount = AmountArgument.parse(this, StringArgumentType.getString(context, "amount"), false);
        String sourceAccountId = PolyCoinIdentifierArgument.getOptionalId(context, "from");
        String targetAccountId = PolyCoinIdentifierArgument.getOptionalId(context, "to");

        if (sender.id().equals(target.getUUID())) {
            source.sendFailure(textComponents.error("You cannot pay yourself. Use account transfer to move money between your accounts."));
            return 0;
        }

        var data = PolyCoin.INSTANCE.getData(source.getServer());
        var accounts = AccountArgument.getTransferAccounts(this, data, sender.id(), sourceAccountId, target.getUUID(), targetAccountId);
        var senderAccount = accounts.source();
        var targetAccount = accounts.target();
        var currency = commandResult.require(senderAccount.getCurrency());
        commandResult.require(data.transfer(senderAccount.getId(), targetAccount.getId(), amount));

        var formattedAmount = TextComponents.amount(currency.formatValueComponent(amount, true));
        var onlineSender = source.getServer().getPlayerList().getPlayer(sender.id());
        var senderName = onlineSender == null ? Component.literal(sender.name()) : onlineSender.getDisplayName();
        source.sendSuccess(
                () -> textComponents.success("Paid ")
                        .append(formattedAmount)
                        .append(" to ").append(TextComponents.value(target.getDisplayName()))
                        .append(TextComponents.field("From account", CommandText.account(senderAccount)))
                        .append(AccountArgument.isActingAs(context)
                                ? TextComponents.field("On behalf of", TextComponents.value(sender.name())) : Component.empty()),
                AccountArgument.isActingAs(context)
        );
        target.sendSystemMessage(
                textComponents.success("Received ")
                        .append(formattedAmount)
                        .append(" from ")
                        .append(TextComponents.value(senderName))
                        .append(TextComponents.field("To account", CommandText.account(targetAccount)))
        );
        return 1;
    }

}
