package polycube.polycoin.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.server.permissions.PermissionLevel;
import polycube.polycoin.PolyCoin;
import polycube.polycoin.commands.commandArguments.AccountArgument;
import polycube.polycore.commands.PolyCommand;

import java.util.List;

public final class AsCommand extends PolyCommand {
    private final List<PolyCommand> playerCommands;

    public AsCommand(PolyCommand... playerCommands) {
        super(
                PolyCoin.MOD_ID,
                "as",
                "Runs player commands for another player, including offline players",
                "<player> <account|balance|pay> ...",
                PermissionLevel.GAMEMASTERS
        );
        this.playerCommands = List.of(playerCommands);
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> getCommand(String name, CommandBuildContext buildContext) {
        var player = Commands.argument(AccountArgument.OWNER_ARGUMENT, GameProfileArgument.gameProfile());
        for (var command : playerCommands) {
            for (var alias : command.getCommands(buildContext)) player.then(alias);
        }
        // Do not add a literal "help" here: it would shadow a player named help.
        return Commands.literal(name)
                .requires(source -> hasPermission(source, getPermissionLevel()))
                .executes(context -> execute(context.getSource()))
                .then(player);
    }
}
