package com.ecommerce.project.repositories;


import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.ecommerce.project.model.Order;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByEmailOrderByOrderIdDesc(String email);
    Optional<Order> findByOrderIdAndEmail(Long orderId, String email);
}