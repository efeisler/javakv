package dev.efeg.javakv.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses one line of the wire protocol into a {@link Command}.
 *
 * <pre>
 *   SET key value...  [EX seconds]
 *   GET key
 *   DEL key
 *   PING
 *   QUIT
 * </pre>
 *
 * Keys are a single whitespace-delimited token; values run to the end of the line (may
 * contain spaces) except for an optional trailing {@code EX <seconds>}. This is a deliberate
 * simplification so the protocol stays telnet-typeable: keys/values cannot contain embedded
 * CR/LF, and a value that happens to end in literal text shaped like " EX <digits>" is
 * ambiguous with a real TTL suffix.
 */
public final class CommandParser {

    private static final Pattern TRAILING_EX = Pattern.compile("^(.*)\\sEX\\s+(\\d+)$");

    private CommandParser() {
    }

    public static Command parse(String line) {
        String trimmed = line.strip();
        if (trimmed.isEmpty()) {
            throw new ProtocolException("empty command");
        }

        int firstSpace = trimmed.indexOf(' ');
        String verb = (firstSpace == -1 ? trimmed : trimmed.substring(0, firstSpace)).toUpperCase(Locale.ROOT);
        String rest = firstSpace == -1 ? "" : trimmed.substring(firstSpace + 1).strip();

        return switch (verb) {
            case "PING" -> new PingCommand();
            case "QUIT" -> new QuitCommand();
            case "GET" -> parseGet(rest);
            case "DEL" -> parseDel(rest);
            case "SET" -> parseSet(rest);
            default -> throw new ProtocolException("unknown command '" + verb + "'");
        };
    }

    private static Command parseGet(String rest) {
        requireSingleToken(rest, "GET");
        return new GetCommand(rest.getBytes(StandardCharsets.UTF_8));
    }

    private static Command parseDel(String rest) {
        requireSingleToken(rest, "DEL");
        return new DelCommand(rest.getBytes(StandardCharsets.UTF_8));
    }

    private static void requireSingleToken(String rest, String verb) {
        if (rest.isEmpty() || rest.indexOf(' ') != -1) {
            throw new ProtocolException("wrong number of arguments for '" + verb + "'");
        }
    }

    private static Command parseSet(String rest) {
        int firstSpace = rest.indexOf(' ');
        if (rest.isEmpty() || firstSpace == -1) {
            throw new ProtocolException("wrong number of arguments for 'SET'");
        }
        String key = rest.substring(0, firstSpace);
        String valuePart = rest.substring(firstSpace + 1);
        if (valuePart.isEmpty()) {
            throw new ProtocolException("wrong number of arguments for 'SET'");
        }

        String value = valuePart;
        long expirySeconds = 0;
        Matcher matcher = TRAILING_EX.matcher(valuePart);
        if (matcher.matches()) {
            value = matcher.group(1);
            if (value.isEmpty()) {
                throw new ProtocolException("wrong number of arguments for 'SET'");
            }
            try {
                expirySeconds = Long.parseLong(matcher.group(2));
            } catch (NumberFormatException e) {
                throw new ProtocolException("invalid EX value");
            }
        }

        return new SetCommand(
                key.getBytes(StandardCharsets.UTF_8),
                value.getBytes(StandardCharsets.UTF_8),
                expirySeconds);
    }
}
