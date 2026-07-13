package com.ksiu.plugin.protocol.interfaces;

@FunctionalInterface
public interface IMessageParser
{
    public void parse(String[] args);
}
