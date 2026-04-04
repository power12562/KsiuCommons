package com.ksiu.commons.streamconnector.session.interfaces;

@FunctionalInterface
public interface ISubscribeEvent
{
    void execute(String eventType, String channelId);
}
