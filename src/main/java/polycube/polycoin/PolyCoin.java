package polycube.polycoin;

import eu.pb4.common.economy.api.CommonEconomy;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import polycube.polycoin.commands.*;
import polycube.polycoin.economy.PolyCoinEconomyProvider;
import polycube.polycore.commands.PolyCommands;

public class PolyCoin implements ModInitializer {

    public static final String MOD_ID = "polycoin";
    public static final Logger LOGGER = LoggerFactory.getLogger("PolyCoin");
    public static final PolyCoinEconomyProvider INSTANCE = new PolyCoinEconomyProvider();

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing PolyCoin");
        CommonEconomy.register(MOD_ID, INSTANCE);

        var account = new AccountCommand();
        var balance = new BalanceCommand();
        var pay = new PayCommand();
        PolyCommands.registerCommands(
                MOD_ID,
                "PolyCoind",
                LOGGER,
                account,
                new AsCommand(account, balance, pay),
                balance,
                new BalanceTopCommand(),
                new CurrencyCommand(),
                pay,
                new PnjCommand()
        );
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
