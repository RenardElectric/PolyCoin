package polycube.polycoin.commands;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.Person;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.jspecify.annotations.Nullable;
import polycube.polycoin.PolyCoin;

import java.util.Objects;

public final class PolyCoinCommands {
    private static PolyCoinCommand @Nullable [] commands;

    private PolyCoinCommands() {}

    public static void registerCommands(PolyCoinCommand... commands) {
        PolyCoinCommands.commands = commands;
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, _) -> {
            var baseCommand = Commands.literal(PolyCoin.MOD_ID);
            baseCommand.executes(context -> printModInfo(context.getSource()));
            for (PolyCoinCommand command : commands) {
                for (var commandAlias : command.getCommands(buildContext)) {
                    baseCommand.then(commandAlias);
                    if (command.hasQuickAlias()) dispatcher.register(commandAlias);
                }
            }
            dispatcher.register(baseCommand);
            PolyCoin.LOGGER.debug("Registered {} PolyCoin subcommand(s)", commands.length);
        });
    }

    public static int printModInfo(CommandSourceStack cst) {
        var optionalModData = FabricLoader.getInstance()
                .getModContainer(PolyCoin.MOD_ID)
                .map(ModContainer::getMetadata);

        if (optionalModData.isEmpty()) {
            PolyCoin.LOGGER.warn("Could not find PolyCoin metadata while handling the base command");
            cst.sendFailure(CommandText.error("Could not fetch mod information."));
            return 0;
        }
        var modData = optionalModData.get();
        var authors = modData.getAuthors().stream()
                .map(Person::getName)
                .reduce((a, b) -> a + " and " + b)
                .orElse("Unknown authors");
        var modInfo = CommandText.header(modData.getName())
                .append(CommandText.muted(" v" + modData.getVersion().getFriendlyString()))
                .append(CommandText.field("Made by", CommandText.value(authors)))
                .append("\n" + modData.getDescription())
                .append("\n").append(CommandText.action("[View commands]", "/" + PolyCoin.MOD_ID + " help"));
        cst.sendSuccess(() -> modInfo, false);
        return 1;
    }

    public static PolyCoinCommand[] getCommands() {
        return Objects.requireNonNull(commands, "PolyCoin commands are unavailable before registration");
    }
}
