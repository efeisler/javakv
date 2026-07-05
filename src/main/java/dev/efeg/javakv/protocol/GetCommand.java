package dev.efeg.javakv.protocol;

public record GetCommand(byte[] key) implements Command {
}
