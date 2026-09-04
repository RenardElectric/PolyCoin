package polycube.polycoin.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
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
        return super.getCommand(name).then(
                Commands.argument("player", EntityArgument.player()).then(
                        Commands.argument("amount", StringArgumentType.word())
                                .executes(context -> pay(
                                        context.getSource(),
                                        EntityArgument.getPlayer(context, "player"),
                                        StringArgumentType.getString(context, "amount"),
                                        null,
                                        null
                                ))
                                .then(Commands.literal("from").then(
                                        Commands.argument("sourceAccount", StringArgumentType.string())
                                                .suggests(AccountArgument::suggestAccounts)
                                                .executes(context -> pay(
                                                        context.getSource(),
                                                        EntityArgument.getPlayer(context, "player"),
                                                        StringArgumentType.getString(context, "amount"),
                                                        StringArgumentType.getString(context, "sourceAccount"),
                                                        null
                                                ))
                                                .then(Commands.literal("to").then(
                                                        Commands.argument("targetAccount", StringArgumentType.string())
                                                                .suggests((context, builder) -> AccountArgument.suggestMatchingAccounts(
                                                                        context, builder, "player", "sourceAccount",
                                                                        AccountArgument.AccountSide.TARGET
                                                                ))
                                                                .executes(context -> pay(
                                                                        context.getSource(),
                                                                        EntityArgument.getPlayer(context, "player"),
                                                                        StringArgumentType.getString(context, "amount"),
                                                                        StringArgumentType.getString(context, "sourceAccount"),
                                                                        StringArgumentType.getString(context, "targetAccount")
                                                                ))
                                                ))
                                ))
                                .then(Commands.literal("to").then(
                                        Commands.argument("targetAccount", StringArgumentType.string())
                                                .suggests((context, builder) -> AccountArgument.suggestTargetAccountsForMain(
                                                        context, builder, "player"
                                                ))
                                                .executes(context -> pay(
                                                        context.getSource(),
                                                        EntityArgument.getPlayer(context, "player"),
                                                        StringArgumentType.getString(context, "amount"),
                                                        null,
                                                        StringArgumentType.getString(context, "targetAccount")
                                                ))
                                                .then(Commands.literal("from").then(
                                                        Commands.argument("sourceAccount", StringArgumentType.string())
                                                                .suggests((context, builder) -> AccountArgument.suggestMatchingAccounts(
                                                                        context, builder, "player", "targetAccount",
                                                                        AccountArgument.AccountSide.SOURCE
                                                                ))
                                                                .executes(context -> pay(
                                                                        context.getSource(),
                                                                        EntityArgument.getPlayer(context, "player"),
                                                                        StringArgumentType.getString(context, "amount"),
                                                                        StringArgumentType.getString(context, "sourceAccount"),
                                                                        StringArgumentType.getString(context, "targetAccount")
                                                                ))
                                                ))
                                ))
                )
        );
    }

    private int pay(CommandSourceStack source, ServerPlayer target, String rawAmount, @Nullable String sourceAccountId, @Nullable String targetAccountId) throws CommandSyntaxException {
        var sender = source.getPlayer();
        if (sender == null) {
            source.sendFailure(Component.literal("This command can only be executed by a player."));
            return 0;
        }

        if (sender.getUUID().equals(target.getUUID())) {
            source.sendFailure(Component.literal("You cannot pay yourself."));
            return 0;
        }

        var data = PolyCoin.INSTANCE.getData(source.getServer());
        PolyCoinEconomyAccount senderAccount = sourceAccountId == null
                ? data.getMainAccount(sender.getUUID())
                : data.getAccount(sender.getGameProfile(), sourceAccountId);

        if (senderAccount == null) {
            source.sendFailure(Component.literal("Unknown source account: " + sourceAccountId));
            return 0;
        }

        BigInteger amount = AmountArgument.parse(rawAmount, false);

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
        source.sendSuccess(
                () -> Component.literal("Paid ")
                        .append(target.getDisplayName())
                        .append(" ")
                        .append(formattedAmount),
                false
        );
        target.sendSystemMessage(
                Component.literal("Received ")
                        .append(formattedAmount)
                        .append(" from ")
                        .append(sender.getDisplayName())
        );
        return 1;
    }

    private static @Nullable PolyCoinEconomyAccount findTargetAccount(
            PolyCoinEconomyData data,
            ServerPlayer target,
            @Nullable String targetAccountId,
            PolyCoinEconomyAccount senderAccount
    ) {
        if (targetAccountId != null) {
            return data.getAccount(target.getGameProfile(), targetAccountId);
        }

        String defaultAccountId = data.defaultAccount(target.getGameProfile(), senderAccount.currency());
        return defaultAccountId == null
                ? null
                : data.getAccount(target.getGameProfile(), defaultAccountId);
    }
}
