package com.practicebank.masterreference.product;

import org.springframework.stereotype.Service;

import com.practicebank.masterreference.common.NotFoundException;
import com.practicebank.masterreference.product.dto.Product;

/** 05-product 商品マスタ参照。 */
@Service
public class ProductService {

    private final ProductRepository repository;

    public ProductService(ProductRepository repository) {
        this.repository = repository;
    }

    public Product getByCode(String productCode) {
        return repository.findByCode(productCode)
                .orElseThrow(() -> new NotFoundException("product not found: " + productCode));
    }
}
