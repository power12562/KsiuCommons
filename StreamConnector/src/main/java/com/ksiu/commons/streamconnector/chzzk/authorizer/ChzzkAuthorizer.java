package com.ksiu.commons.streamconnector.chzzk.authorizer;

import com.ksiu.commons.streamconnector.chzzk.token.ChzzkToken;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;

import java.awt.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class ChzzkAuthorizer
{
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private final int _port;
    private final String _clientID;
    private final String _clientSecret;
    private final String _redirectUri;

    public static final String CHZZK_API_URL = "https://openapi.chzzk.naver.com";

    public ChzzkAuthorizer(String clientId, String clientSecret, int port)
    {
        _port = port;
        _clientID = clientId;
        _clientSecret = clientSecret;
        _redirectUri = "http://localhost:" + String.valueOf(port);
    }

    public CompletableFuture<ChzzkToken> requestToken()
    {
        CompletableFuture<ChzzkToken> future = new CompletableFuture<>();
        String state = UUID.randomUUID().toString().substring(0, 8);
        try
        {
            HttpServer server = HttpServer.create(new InetSocketAddress(_port), 0);
            server.createContext("/", exchange ->
            {
                String path = exchange.getRequestURI().getPath();
                if (path.endsWith("favicon.ico"))
                {
                    exchange.close();
                    return;
                }

                String query = exchange.getRequestURI().getQuery();
                String code = null;
                String receivedState = null;
                if (query != null)
                {
                    for (String param : query.split("&"))
                    {
                        if (param.startsWith("code="))
                            code = param.substring(5);
                        if (param.startsWith("state="))
                            receivedState = param.substring(6);
                    }
                }

                if (code != null && state.equals(receivedState))
                {
                    String response = "인증 성공! 이제 이 창을 닫으셔도 됩니다.";
                    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
                    exchange.sendResponseHeaders(200, response.getBytes().length);
                    exchange.getResponseBody().write(response.getBytes());
                    exchange.getResponseBody().close();
                    exchangeCodeForToken(_clientID, _clientSecret, code, state, future);
                    exchange.close();
                }
                else
                {
                    future.completeExceptionally(new RuntimeException("Invalid Auth State or Code"));
                    exchange.close();
                }
            });
            future.whenComplete((res, ex) ->
            {
                server.stop(0);
            });
            server.start();
            String authUrl = String.format(
                    "https://chzzk.naver.com/account-interlock?clientId=%s&redirectUri=%s&state=%s",
                    _clientID, _redirectUri, state
            );
            Desktop.getDesktop().browse(new URI(authUrl));
        }
        catch (Exception e)
        {
            future.completeExceptionally(e);
        }
        return future.orTimeout(5, TimeUnit.MINUTES);
    }

    private void exchangeCodeForToken(String clientId, String clientSecret, String code, String state, CompletableFuture<ChzzkToken> future)
    {
        String jsonBody = """
                {
                    "grantType": "authorization_code",
                    "clientId": "%s",
                    "clientSecret": "%s",
                    "state": "%s",
                    "code": "%s"
                }
                """.formatted(clientId, clientSecret, state, code);

        HttpRequest requestToken = HttpRequest.newBuilder()
                .uri(URI.create(CHZZK_API_URL + "/auth/v1/token"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        httpClient.sendAsync(requestToken, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenAccept(tokenBodyRaw ->
                {
                    try
                    {
                        final JSONObject tokenBody = new JSONObject(tokenBodyRaw);
                        final JSONObject tokenContent = tokenBody.getJSONObject("content");
                        final String accessToken = tokenContent.getString("accessToken");
                        final String refreshToken = tokenContent.getString("refreshToken");
                        // User 조회
                        HttpRequest requestUser = HttpRequest.newBuilder()
                                .uri(URI.create(CHZZK_API_URL + "/open/v1/users/me"))
                                .header("Authorization", "Bearer " + accessToken)
                                .header("Content-Type", "application/json")
                                .GET()
                                .build();

                        httpClient.sendAsync(requestUser, HttpResponse.BodyHandlers.ofString())
                                .thenApply(HttpResponse::body)
                                .thenAccept(userBodyRaw ->
                                {
                                    try
                                    {
                                        final JSONObject userBody = new JSONObject(userBodyRaw);
                                        final JSONObject userContent = userBody.getJSONObject("content");
                                        final String channelId = userContent.getString("channelId");
                                        final String channelName = userContent.getString("channelName");
                                        future.complete(new ChzzkToken(this, accessToken, refreshToken, channelId, channelName));
                                    }
                                    catch (Exception ex)
                                    {
                                        future.completeExceptionally(new RuntimeException(userBodyRaw));
                                    }
                                }).exceptionally(ex ->
                                {
                                    future.completeExceptionally(ex);
                                    return null;
                                });
                    }
                    catch (Exception ex)
                    {
                        future.completeExceptionally(new RuntimeException(tokenBodyRaw));
                    }
                })
                .exceptionally(ex ->
                {
                    future.completeExceptionally(ex);
                    return null;
                });
    }

    public CompletableFuture<ChzzkToken> refreshToken(ChzzkToken refreshToken)
    {
        CompletableFuture<ChzzkToken> future = new CompletableFuture<>();
        String jsonBody = """
                {
                    "grantType": "refresh_token",
                    "refreshToken": "%s",
                    "clientId": "%s",
                    "clientSecret": "%s"
                }
                """.formatted(refreshToken.getRefreshToken(), _clientID, _clientSecret);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(CHZZK_API_URL + "/auth/v1/token"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenAccept(body ->
                {
                    try
                    {
                        JSONObject tokenBody = new JSONObject(body);
                        final String newAccessToken = tokenBody.getString("accessToken");
                        final String newRefreshToken = tokenBody.getString("refreshToken");
                        future.complete(new ChzzkToken(this, newAccessToken, newRefreshToken, refreshToken.getChannelId(), refreshToken.getChannelName()));
                    }
                    catch (Exception ex)
                    {
                        future.completeExceptionally(ex);
                    }
                })
                .exceptionally(ex ->
                {
                    future.completeExceptionally(ex);
                    return null;
                });

        return future;
    }


    public void revokeToken(String token, String typeHint)
    {
        String form = String.format(
                "clientId=%s&clientSecret=%s&token=%s&tokenTypeHint=%s",
                _clientID, _clientSecret, token, typeHint
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(CHZZK_API_URL + "/auth/v1/token/revoke"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

}
