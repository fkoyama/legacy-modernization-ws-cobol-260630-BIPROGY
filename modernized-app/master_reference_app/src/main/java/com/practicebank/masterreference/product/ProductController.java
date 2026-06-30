package com.practicebank.masterreference.product;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.practicebank.masterreference.product.dto.Product;

/** 05-product /products。 */
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    @GetMapping("/{productCode}")
    public Product getProductByCode(@PathVariable String productCode) {
        return service.getByCode(productCode);
    }
}
