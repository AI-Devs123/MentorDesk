package com.example.order;

import java.util.List;

/**
 * 样例订单服务，用于 Week5 切片1 解析引擎测试夹具。
 * 包含类、方法、私有方法调用与方法内外部依赖调用，覆盖 callee 边提取。
 */
public class SampleOrderService {

    private OrderRepository orderRepository;

    public Order checkout(String orderId) {
        Order order = orderRepository.findById(orderId);
        validate(order);
        return persist(order);
    }

    private void validate(Order order) {
        if (order == null) {
            throw new IllegalArgumentException("order is null");
        }
    }

    private Order persist(Order order) {
        return orderRepository.save(order);
    }

    public List<Order> listOpen() {
        return orderRepository.findAllOpen();
    }

    interface OrderRepository {
        Order findById(String id);

        Order save(Order o);

        List<Order> findAllOpen();
    }

    static class Order {
    }
}
