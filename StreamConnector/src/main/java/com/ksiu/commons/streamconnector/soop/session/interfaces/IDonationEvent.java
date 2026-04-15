package com.ksiu.commons.streamconnector.soop.session.interfaces;

@FunctionalInterface
public interface IDonationEvent
{
    public void execute(String nickName, String msg, int payAmount);
}
