package com.example.warehouse;

import java.util.List;
import java.util.ArrayList;
import com.example.inventory.InventoryClient;

public class PickingList {

    public List<String> pickList(String zone) {
        InventoryClient client = InventoryClient.forZone(zone);
        return client.skusForZone(zone);
    }
}
