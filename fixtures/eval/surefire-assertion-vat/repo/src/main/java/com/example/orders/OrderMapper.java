package com.example.orders;

import java.util.LinkedHashMap;
import java.util.Map;

public class OrderMapper {

    public Map<String, String> map(Order order) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("order_id", order.id());
        row.put("customer", order.customerName());
        String address = order.shippingAddress();
        if (address == null) {
            address = "(no address)";
        }
        row.put("shipping_address", address);
        row.put("vat_id", order.vatId() == null ? null : order.vatId().strip());
        return row;
    }
}
