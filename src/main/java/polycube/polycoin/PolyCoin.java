package polycube.polycoin;

import eu.pb4.common.economy.api.CommonEconomy;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import polycube.polycoin.economy.PolyCoinEconomyProvider;
import polycube.polycoin.commands.BalanceCommand;
import polycube.polycoin.commands.HelpCommand;
import polycube.polycoin.commands.PolyCoinCommand;
import polycube.polycoin.commands.PolyCoinCommands;

public class PolyCoin implements ModInitializer {

    public static final String MOD_ID = "polycoin";
    public static final Logger LOGGER = LoggerFactory.getLogger("PolyCoin");
    public static final PolyCoinEconomyProvider INSTANCE = new PolyCoinEconomyProvider();

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing PolyCoin");
        CommonEconomy.register(MOD_ID, INSTANCE);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LOGGER.info("PolyCoin server started");
            INSTANCE.load(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("PolyCoin server stopping");
            INSTANCE.unload(server);
        });

        PolyCoinCommand[] commands = {
                new HelpCommand(),
                new BalanceCommand()
        };
        PolyCoinCommands.registerCommands(commands);
    }
}
