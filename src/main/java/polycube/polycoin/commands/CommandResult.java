package polycube.polycoin.commands;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.serialization.DataResult;

/** Converts economy errors to Brigadier errors only at the command boundary. */
public final class CommandResult {
    private static final DynamicCommandExceptionType ERROR = new DynamicCommandExceptionType(
            message -> CommandText.error(message.toString())
    );

    private CommandResult() {}

    public static <T> T require(DataResult<T> result) throws CommandSyntaxException {
        return result.getOrThrow(ERROR::create);
    }
}
