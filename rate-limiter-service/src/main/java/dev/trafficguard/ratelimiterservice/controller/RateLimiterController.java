package dev.trafficguard.ratelimiterservice.controller;

import dev.trafficguard.ratelimiterservice.service.RedisRateLimiterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.UUID;

@RestController
public class RateLimiterController {

    private final RedisRateLimiterService rateLimiterService;
    private final WebClient webClient;

    private static final Logger log = LoggerFactory.getLogger(RateLimiterController.class);

    /**
     * Constructs a RateLimiterController with the given RedisRateLimiterService.
     * The product-service base URL is injected from configuration.
     * @param rateLimiterService the service that performs token-bucket rate limit checks
     * @param productServiceUrl the base URL of the downstream product-service
     */
    public RateLimiterController(
            RedisRateLimiterService rateLimiterService,
            @Value("${product-service.url:http://product-service:8081}") String productServiceUrl) {
        this.rateLimiterService = rateLimiterService;
        this.webClient = WebClient.builder().baseUrl(productServiceUrl).build();
    }

    /**
     * Handles GET requests to "/api/products" and applies token-bucket rate limiting based on
     * the client's IP address.
     * <p>
     * If the client is within their rate limit, the request is forwarded to the downstream
     * product-service. If the client exceeds the rate limit, HTTP 429 Too Many Requests is returned
     * immediately. If the downstream service is unreachable, HTTP 503 Service Unavailable is returned.
     *
     * @param exchange the ServerWebExchange containing request and response information
     * @return a Mono wrapping a ResponseEntity with product data, a 429, or a 503 error
     */
    @GetMapping("/api/products")
    public Mono<ResponseEntity<String>> getLimitedResource(ServerWebExchange exchange) {
        // Generate a unique request ID for distributed tracing
        String requestId = UUID.randomUUID().toString();

        // Use the client's IP address as the rate-limiting key
        String ipAddress = Objects.requireNonNull(exchange.getRequest().getRemoteAddress())
                .getAddress().getHostAddress();

        log.info("[RequestID: {}] Received request for /api/products from IP: {}", requestId, ipAddress);

        if (rateLimiterService.isAllowed(ipAddress)) {
            // Request is within the rate limit — forward to the downstream product-service
            log.info("[RequestID: {}] Request allowed for IP: {}. Forwarding to product-service.", requestId, ipAddress);

            return webClient.get()
                    .uri("/products")
                    .retrieve()
                    .toEntity(String.class)
                    .doOnSuccess(response -> log.info(
                            "[RequestID: {}] Successfully fetched response from product-service for IP: {}. Status: {}",
                            requestId, ipAddress, response.getStatusCode()))
                    .doOnError(error -> log.error(
                            "[RequestID: {}] Error fetching response from product-service for IP: {}. Error: {}",
                            requestId, ipAddress, error.getMessage()))
                    .onErrorResume(error -> {
                        // Return 503 instead of propagating the error to the client
                        log.error("[RequestID: {}] Downstream product-service unavailable for IP: {}", requestId, ipAddress);
                        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body("Service temporarily unavailable. Please try again later."));
                    });
        } else {
            // Rate limit exceeded — return 429 immediately, no downstream call
            log.warn("[RequestID: {}] Request rate limited for IP: {}", requestId, ipAddress);
            return Mono.just(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "60")
                    .body("Error: Too many requests. Please try again later."));
        }
    }
}
