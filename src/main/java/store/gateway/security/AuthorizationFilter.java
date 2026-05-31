package store.gateway.security;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component
public class AuthorizationFilter implements GlobalFilter {

    private final Logger logger = LoggerFactory.getLogger(AuthorizationFilter.class);

    public static final String AUTH_COOKIE_TOKEN = "__store_jwt_token";

    @Value("${auth.service.solve-url:http://auth-service:8082/auth/solve}")
    private String authServiceTokenSolveUrl;

    @Autowired
    private RouterValidator routerValidator;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        logger.debug("filter: entrou no filtro de autorizacao");
        ServerHttpRequest request = exchange.getRequest();

        if (!routerValidator.isSecured.test(request)) {
            logger.debug("filter: rota nao eh segura");
            return chain.filter(exchange);
        }

        if (request.getCookies().containsKey(AUTH_COOKIE_TOKEN)) {
            logger.debug("filter: tem [" + AUTH_COOKIE_TOKEN + "] no cookie");
            String token = request.getCookies().getFirst(AUTH_COOKIE_TOKEN).getValue();
            logger.debug(String.format("filter: [Token]=[%s]", token));
            if (null != token && !token.isEmpty()) {
                return requestAuthTokenSolve(exchange, chain, token.trim());
            }
        }

        logger.debug("filter: access is denied!");
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }

    private Mono<Void> requestAuthTokenSolve(ServerWebExchange exchange, GatewayFilterChain chain, String jwt) {
        logger.debug("solving jwt: " + jwt);
        return WebClient.builder()
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build()
            .post()
            .uri(authServiceTokenSolveUrl)
            .bodyValue(Map.of("token", jwt))
            .retrieve()
            .toEntity(Map.class)
            .flatMap(response -> {
                if (response != null && response.hasBody() && response.getBody() != null) {
                    @SuppressWarnings("unchecked")
                    final Map<String, String> map = response.getBody();
                    String idAccount = map.get("idAccount");
                    logger.debug("solve: id account: " + idAccount);
                    ServerWebExchange authorized = updateRequest(exchange, idAccount, jwt);
                    return chain.filter(authorized);
                } else {
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
                }
            });
    }

    private ServerWebExchange updateRequest(ServerWebExchange exchange, String idAccount, String jwt) {
        logger.debug("original headers: " + exchange.getRequest().getHeaders());
        ServerWebExchange modified = exchange.mutate()
            .request(
                exchange.getRequest()
                    .mutate()
                    .header("id-account", idAccount)
                    .header("Authorization", "Bearer " + jwt)
                    .build()
            ).build();
        logger.debug("updated headers: " + modified.getRequest().getHeaders());
        return modified;
    }

}
