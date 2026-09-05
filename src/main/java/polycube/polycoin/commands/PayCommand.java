package polycube.polycoin.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import org.jspecify.annotations.Nullable;
import polycube.polycoin.PolyCoin;
import polycube.polycoin.commands.commandArguments.AccountArgument;
import polycube.polycoin.commands.commandArguments.AmountArgument;
import polycube.polycoin.commands.commandArguments.PolyCoinIdentifierArgument;
import polycube.polycoin.economy.PolyCoinEconomyAccount;
import polycube.polycoin.economy.PolyCoinEconomyData;

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
        var amount = Commands.argument("amount", StringArgumentType.word()).executes(this::pay);
        for (var side : AccountArgument.AccountSide.values()) {
            boolean from = side == AccountArgument.AccountSide.SOURCE;
            String accountKey = from ? "sourceAccount" : "targetAccount";
            String otherKey = from ? "targetAccount" : "sourceAccount";
            var otherSide = from ? AccountArgument.AccountSide.TARGET : AccountArgument.AccountSide.SOURCE;
            amount.then(Commands.literal(from ? "from" : "to").then(
                    Commands.argument(accountKey, StringArgumentType.string())
                            .suggests((context, builder) -> from
                                    ? AccountArgument.suggestAccounts(context, builder)
                                    : AccountArgument.suggestTargetAccountsForDefault(context, builder, "player"))
                            .executes(this::pay)
                            .then(Commands.literal(from ? "to" : "from").then(
                                    Commands.argument(otherKey, StringArgumentType.string())
                                            .suggests((context, builder) -> AccountArgument.suggestMatchingAccounts(
                                                    context, builder, "player", accountKey, otherSide))
                                            .executes(this::pay)
                            ))
            ));
        }
        return super.getCommand(name).then(Commands.argument("player", EntityArgument.player()).then(amount));
    }

    private int pay(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var source = context.getSource();
        var sender = AccountArgument.getOwner(context);
        var target = EntityArgument.getPlayer(context, "player");
        BigInteger amount = AmountArgument.parse(StringArgumentType.getString(context, "amount"), false);
        String sourceAccountId = PolyCoinIdentifierArgument.getOptionalId(context, "sourceAccount");
        String targetAccountId = PolyCoinIdentifierArgument.getOptionalId(context, "targetAccount");

        if (sender.id().equals(target.getUUID())) {
            source.sendFailure(Component.literal("You cannot pay yourself."));
            return 0;
        }

        var data = PolyCoin.INSTANCE.getData(source.getServer());
        PolyCoinEconomyAccount senderAccount = AccountArgument.getAccount(data, sender, sourceAccountId);

        PolyCoinEconomyAccount targetAccount = findTargetAccount(data, target, targetAccountId, senderAccount);
        if (targetAccount == null) {
            String message = targetAccountId == null
                    ? "The target player has no default account for this currency."
                    : "Unknown target account: " + targetAccountId;
            source.sendFailure(Component.literal(message));
            return 0;
        }

        var result = data.transfer(senderAccount, targetAccount, amount);
        if (!result.successful()) {
            source.sendFailure(result.message());
            return 0;
        }

        var formattedAmount = senderAccount.currency().formatValueComponent(amount, true);
        var onlineSender = source.getServer().getPlayerList().getPlayer(sender.id());
        var senderName = onlineSender == null ? Component.literal(sender.name()) : onlineSender.getDisplayName();
        source.sendSuccess(
                () -> Component.literal("Paid ")
                        .append(target.getDisplayName())
                        .append(" ")
                        .append(formattedAmount)
                        .append(AccountArgument.isActingAs(context) ? " on behalf of " + sender.name() : ""),
                AccountArgument.isActingAs(context)
        );
        target.sendSystemMessage(
                Component.literal("Received ")
                        .append(formattedAmount)
                        .append(" from ")
                        .append(senderName)
        );
        return 1;
    }

    private static @Nullable PolyCoinEconomyAccount findTargetAccount(
            PolyCoinEconomyData data,
            ServerPlayer target,
            @Nullable String targetAccountId,
            PolyCoinEconomyAccount senderAccount
    ) throws CommandSyntaxException {
        if (targetAccountId != null) {
            return AccountArgument.getAccount(data, target.getGameProfile(), targetAccountId);
        }

        String defaultAccountId = data.defaultAccount(target.getGameProfile(), senderAccount.currency());
        return defaultAccountId == null
                ? null
                : data.getAccount(target.getGameProfile(), defaultAccountId);
    }
}
