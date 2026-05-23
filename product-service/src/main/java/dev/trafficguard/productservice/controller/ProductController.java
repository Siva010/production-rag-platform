package dev.trafficguard.productservice.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mock product controller that serves as the protected downstream resource
 * guarded by the rate-limiter-service.
 * <p>
 * In a real system, this would fetch product data from a database or another
 * upstream service. Here it returns a static response to isolate and benchmark
 * the rate-limiting behaviour.
 */
@RestController
public class ProductController {

    private static final Logger log = LoggerFactory.getLogger(ProductController.class);

    /**
     * Returns a mock product listing.
     * <p>
     * This endpoint is the protected resource that the rate-limiter-service proxies.
     * Only requests that pass the token-bucket check in the rate-limiter-service
     * will reach this handler.
     *
     * @return a static mock response representing a successful product fetch
     */
    @GetMapping("/products")
    public String getProducts() {
        log.debug("GET /products called — returning mock product data");
        // This is the protected resource our rate limiter guards.
        // In production, replace with a real database or service call.
        return "Success! Here are the products.";
    }
}
