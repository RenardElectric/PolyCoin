package polycube.polycoin.commands;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.PermissionLevel;
import polycube.polycoin.PolyCoin;

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
    protected int execute(CommandSourceStack source) {
        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command can only be executed by a player."));
            return 0;
        }
        var account = PolyCoin.INSTANCE.getData(source.getServer()).getMainAccount(player.getUUID());
        source.sendSuccess(() -> Component.literal("Your balance is: ").append(account.formattedBalance()).append(" ").append(account.currency().name()), false);
        return 1;
    }
}
