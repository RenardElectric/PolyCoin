package polycube.polycoin.commands.commandArguments;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import polycube.polycoin.economy.PolyCoinEconomyCurrency;
import polycube.polycore.commands.PolyCommand;

import java.math.BigInteger;
import java.util.function.Function;

public final class AmountArgument {
    private static final Function<PolyCommand, SimpleCommandExceptionType> NEGATIVE_AMOUNT =
            command -> new SimpleCommandExceptionType(
                    command.textComponents.error("The amount cannot be negative.")
            );
    private static final Function<PolyCommand, SimpleCommandExceptionType> NON_POSITIVE_AMOUNT =
            command -> new SimpleCommandExceptionType(
                    command.textComponents.error("The amount must be greater than zero.")
            );

    private AmountArgument() {}

    public static BigInteger parse(PolyCommand command, String rawAmount, boolean allowZero) throws CommandSyntaxException {
        BigInteger amount = command.commandResult.require(PolyCoinEconomyCurrency.tryParseAmount(rawAmount));
        if (allowZero ? amount.signum() < 0 : amount.signum() <= 0) {
            throw (allowZero ? NEGATIVE_AMOUNT.apply(command) : NON_POSITIVE_AMOUNT.apply(command)).create();
        }
        return amount;
    }
}
