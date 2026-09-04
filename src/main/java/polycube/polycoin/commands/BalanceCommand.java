package polycube.polycoin.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.PermissionLevel;
import org.jspecify.annotations.Nullable;
import polycube.polycoin.PolyCoin;
import polycube.polycoin.commands.commandArguments.AccountArgument;
import polycube.polycoin.economy.PolyCoinEconomyAccount;

public class BalanceCommand extends PolyCoinCommand {
    public BalanceCommand() {
        super(
                "balance",
                "Displays the balance of the player",
                "[account]",
                PermissionLevel.ALL,
                true
        );
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> getCommand(String name) {
        return super.getCommand(name).then(
                Commands.argument("account", StringArgumentType.word())
                        .suggests(AccountArgument::suggestAccounts)
                        .executes(context -> showBalance(
                                context.getSource(),
                                StringArgumentType.getString(context, "account")
                        ))
        );
    }

    @Override
    protected int execute(CommandSourceStack source) {
        return showBalance(source, null);
    }

    private int showBalance(CommandSourceStack source, @Nullable String accountId) {
        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command can only be executed by a player."));
            return 0;
        }

        PolyCoinEconomyAccount account = accountId == null
                ? PolyCoin.INSTANCE.getData(source.getServer()).getMainAccount(player.getUUID())
                : PolyCoin.INSTANCE.getData(source.getServer()).getAccount(player.getGameProfile(), accountId);

        if (account == null) {
            source.sendFailure(Component.literal("Unknown account: " + accountId));
            return 0;
        }

        source.sendSuccess(
                () -> account.name().copy()
                        .append(" balance: ")
                        .append(account.formattedBalance()),
                false
        );
        return 1;
    }
}
