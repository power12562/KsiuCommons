package com.ksiu.commons.streamconnector.chzzk.session.interfaces.session;

import org.json.JSONObject;

@FunctionalInterface
public interface IDonationEvent extends ISessionEvent
{
    public void execute(JSONObject donation);
}
