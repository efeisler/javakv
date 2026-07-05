package dev.efeg.javakv.protocol;

/** A parsed client request. */
public sealed interface Command permits SetCommand, GetCommand, DelCommand, PingCommand, QuitCommand {
}
