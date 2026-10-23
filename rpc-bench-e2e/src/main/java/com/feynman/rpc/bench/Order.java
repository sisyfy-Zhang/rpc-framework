package com.feynman.rpc.bench;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * {@code payload.kind = order} 的 fixture：8 个字段，含 {@code List<String>} 与两个 java.time 类型。
 * 普通类（无参构造 + 可变字段）而不是 record：Protostuff 1.8 无法还原 record。
 */
public class Order {

	private Long orderId;
	private String orderNo;
	private String customerId;
	private Integer quantity;
	private Long amountCents;
	private List<String> skus;
	private Instant createdAt;
	private LocalDate deliveryDate;

	public Order() {
	}

	public Order(Long orderId, String orderNo, String customerId, Integer quantity, Long amountCents, List<String> skus,
			Instant createdAt, LocalDate deliveryDate) {
		this.orderId = orderId;
		this.orderNo = orderNo;
		this.customerId = customerId;
		this.quantity = quantity;
		this.amountCents = amountCents;
		this.skus = skus;
		this.createdAt = createdAt;
		this.deliveryDate = deliveryDate;
	}

	public Long getOrderId() {
		return orderId;
	}

	public void setOrderId(Long orderId) {
		this.orderId = orderId;
	}

	public String getOrderNo() {
		return orderNo;
	}

	public void setOrderNo(String orderNo) {
		this.orderNo = orderNo;
	}

	public String getCustomerId() {
		return customerId;
	}

	public void setCustomerId(String customerId) {
		this.customerId = customerId;
	}

	public Integer getQuantity() {
		return quantity;
	}

	public void setQuantity(Integer quantity) {
		this.quantity = quantity;
	}

	public Long getAmountCents() {
		return amountCents;
	}

	public void setAmountCents(Long amountCents) {
		this.amountCents = amountCents;
	}

	public List<String> getSkus() {
		return skus;
	}

	public void setSkus(List<String> skus) {
		this.skus = skus;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}

	public LocalDate getDeliveryDate() {
		return deliveryDate;
	}

	public void setDeliveryDate(LocalDate deliveryDate) {
		this.deliveryDate = deliveryDate;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof Order other && Objects.equals(orderId, other.orderId) && Objects.equals(orderNo, other.orderNo)
				&& Objects.equals(customerId, other.customerId) && Objects.equals(quantity, other.quantity)
				&& Objects.equals(amountCents, other.amountCents) && Objects.equals(skus, other.skus)
				&& Objects.equals(createdAt, other.createdAt) && Objects.equals(deliveryDate, other.deliveryDate);
	}

	@Override
	public int hashCode() {
		return Objects.hash(orderId, orderNo, customerId, quantity, amountCents, skus, createdAt, deliveryDate);
	}

	@Override
	public String toString() {
		return "Order{" + orderId + ", " + orderNo + ", skus=" + skus + "}";
	}
}
