package com.example.inventory.domain;

import java.util.List;

public class InventoryClient {

    public static InventoryClient forZone(String zone) {
        return new InventoryClient();
    }

    public List<String> skusForZone(String zone) {
        return List.of("sku-1", "sku-2");
    }
}
