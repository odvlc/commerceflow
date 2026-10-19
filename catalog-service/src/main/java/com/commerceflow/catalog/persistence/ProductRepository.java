/*
 * Queries catalog-owned entities; other services obtain product data through APIs or versioned
 * events.
 */
package com.commerceflow.catalog.persistence;

import com.commerceflow.catalog.domain.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    // Public listings exclude inactive rows in SQL; administrative listing deliberately uses findAll.
    Page<Product> findByActiveTrue(Pageable pageable);

    Page<Product> findByActiveTrueAndCategory(String category, Pageable pageable);

    Optional<Product> findByIdempotencyKey(String key);
}
