package polycube.polycoin.commands;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.PermissionLevel;
import polycube.polycoin.PolyCoin;

public class HelpCommand extends PolyCoinCommand {
    public HelpCommand() {
        super(
                "help",
                "Displays a list of available commands and their descriptions",
                "",
                PermissionLevel.ALL
        );
    }

    @Override
    protected int execute(CommandSourceStack source) {
        var helpMessage = CommandText.header("Commands")
                .append("\nClick a command to prepare it; use [Usage] for its syntax.");
        for (PolyCoinCommand command : PolyCoinCommands.getCommands()) {
            if (hasPermission(source, command.getPermissionLevel())) {
                String root = "/" + PolyCoin.MOD_ID + " " + command.getName();
                helpMessage.append("\n\n  ").append(CommandText.action(root, root + " "));
                if (!(command instanceof AsCommand)) {
                    helpMessage.append(" ").append(CommandText.action("[Usage]", root + " help"));
                } else {
                    helpMessage.append(CommandText.value(" <player> <account|balance|pay> ..."));
                }
                if (command.getPermissionLevel() != PermissionLevel.ALL) helpMessage.append(CommandText.muted(" (Admin only)"));
                helpMessage.append("\n  " + command.getDescription());
            }
        }
        source.sendSuccess(() -> helpMessage, false);
        return 1;
    }
}
