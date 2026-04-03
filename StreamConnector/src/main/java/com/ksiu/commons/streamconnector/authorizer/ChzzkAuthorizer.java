package com.ksiu.commons.streamconnector.authorizer;

import com.ksiu.commons.streamconnector.token.ChzzkToken;
import com.sun.net.httpserver.HttpServer;

import java.awt.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class ChzzkAuthorizer
{
    private final int _port;
    private final String _clientID;
    private final String _clientSecret;
    private final String _redirectUri;

    private final String CHZZK_API_URL = "https://openapi.chzzk.naver.com";

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
                    server.stop(0); // 작업 완료 후 서버 종료
                }
                else
                {
                    future.completeExceptionally(new RuntimeException("Invalid Auth State or Code"));
                    server.stop(0);
                }
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
        return future;
    }

    private void exchangeCodeForToken(String clientId, String clientSecret, String code, String state, CompletableFuture<ChzzkToken> future)
    {
        HttpClient client = HttpClient.newHttpClient();
        String jsonBody = """
                {
                    "grantType": "authorization_code",
                    "clientId": "%s",
                    "clientSecret": "%s",
                    "state": "%s",
                    "code": "%s"
                }
                """.formatted(clientId, clientSecret, state, code);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(CHZZK_API_URL + "/auth/v1/token"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenAccept(body ->
                {
                    if (!body.contains("accessToken") || !body.contains("refreshToken"))
                    {
                        future.completeExceptionally(new RuntimeException("API Error: " + body));
                        return;
                    }

                    String accessToken = body.split("\"accessToken\":\"")[1].split("\"")[0];
                    String refreshToken = body.split("\"refreshToken\":\"")[1].split("\"")[0];
                    ChzzkToken token = new ChzzkToken(this, accessToken, refreshToken);
                    future.complete(token);
                })
                .exceptionally(ex ->
                {
                    future.completeExceptionally(ex);
                    return null;
                });

    }

    public CompletableFuture<ChzzkToken> refreshToken(String refreshToken)
    {
        CompletableFuture<ChzzkToken> future = new CompletableFuture<>();
        HttpClient client = HttpClient.newHttpClient();
        String jsonBody = """
                {
                    "grantType": "refresh_token",
                    "refreshToken": "%s",
                    "clientId": "%s",
                    "clientSecret": "%s"
                }
                """.formatted(refreshToken, _clientID, _clientSecret);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(CHZZK_API_URL + "/auth/v1/token"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenAccept(body ->
                {
                    if (!body.contains("accessToken") || !body.contains("refreshToken"))
                    {
                        future.completeExceptionally(new RuntimeException("Refresh Error: " + body));
                        return;
                    }

                    String newAccessToken = body.split("\"accessToken\":\"")[1].split("\"")[0];
                    String newRefreshToken = body.split("\"refreshToken\":\"")[1].split("\"")[0];

                    future.complete(new ChzzkToken(this, newAccessToken, newRefreshToken));
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
        HttpClient client = HttpClient.newHttpClient();
        String form = String.format(
                "clientId=%s&clientSecret=%s&token=%s&tokenTypeHint=%s",
                _clientID, _clientSecret, token, typeHint
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(CHZZK_API_URL + "/auth/v1/token/revoke"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

}
