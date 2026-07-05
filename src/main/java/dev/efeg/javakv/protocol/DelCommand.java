package dev.efeg.javakv.protocol;

public record DelCommand(byte[] key) implements Command {
}
