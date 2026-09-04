package polycube.polycard.commands;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.PermissionLevel;

public class HelpCommand extends PolyCardCommand {
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
        StringBuilder helpMessage = new StringBuilder("\nAvailable commands:");
        for (PolyCardCommand command : PolyCardCommands.getCommands()) {
            if (hasPermission(source, command.getPermissionLevel())) {
                helpMessage.append("\n").append(command.getFullDescription());
            }
        }
        source.sendSuccess(() -> Component.literal(helpMessage.toString()), false);
        return 1;
    }
}
