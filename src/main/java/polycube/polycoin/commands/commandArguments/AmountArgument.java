package polycube.polycoin.commands.commandArguments;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.network.chat.Component;
import polycube.polycoin.economy.PolyCoinEconomyCurrency;

import java.math.BigInteger;

public final class AmountArgument {
    private static final DynamicCommandExceptionType INVALID_AMOUNT = new DynamicCommandExceptionType(
            value -> Component.literal("Invalid amount: " + value)
    );
    private static final SimpleCommandExceptionType NEGATIVE_AMOUNT = new SimpleCommandExceptionType(
            Component.literal("The amount cannot be negative.")
    );
    private static final SimpleCommandExceptionType NON_POSITIVE_AMOUNT = new SimpleCommandExceptionType(
            Component.literal("The amount must be greater than zero.")
    );

    private AmountArgument() {}

    public static BigInteger parse(String rawAmount, boolean allowZero) throws CommandSyntaxException {
        BigInteger amount;
        try {
            amount = PolyCoinEconomyCurrency.parseAmount(rawAmount);
        } catch (NumberFormatException exception) {
            throw INVALID_AMOUNT.create(rawAmount);
        }
        if (allowZero ? amount.signum() < 0 : amount.signum() <= 0) {
            throw (allowZero ? NEGATIVE_AMOUNT : NON_POSITIVE_AMOUNT).create();
        }
        return amount;
    }
}
