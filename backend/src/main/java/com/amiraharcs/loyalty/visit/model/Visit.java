package com.amiraharcs.loyalty.visit.model;

import com.amiraharcs.loyalty.auth.model.User;
import com.amiraharcs.loyalty.customer.model.CustomerProfile;
import com.amiraharcs.loyalty.loyalty.model.LoyaltyAccount;
import com.amiraharcs.loyalty.store.model.Store;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Retained visit history; awarded-points snapshot/link arrive with the atomic award flow. */
@Entity @Immutable
@Table(name = "visits", schema = "loyalty")
public class Visit {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Europe/London");
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, updatable = false)
    private CustomerProfile customer;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private LoyaltyAccount account;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "confirmed_by", nullable = false, updatable = false)
    private User confirmedBy;
    @Column(name = "business_date", nullable = false, updatable = false)
    private LocalDate businessDate;
    @Column(name = "confirmed_at", nullable = false, updatable = false)
    private Instant confirmedAt;

    protected Visit() { }
    public Visit(CustomerProfile customer, LoyaltyAccount account, Store store, User confirmedBy, Instant confirmedAt) {
        this.customer = Objects.requireNonNull(customer);
        this.account = Objects.requireNonNull(account);
        this.store = Objects.requireNonNull(store);
        this.confirmedBy = Objects.requireNonNull(confirmedBy);
        if (customer.getId() == null || !customer.getId().equals(account.getCustomer().getId())) {
            throw new IllegalArgumentException("Visit account must belong to the customer");
        }
        // PostgreSQL persists microseconds: truncate rather than round across a midnight boundary.
        this.confirmedAt = Objects.requireNonNull(confirmedAt).truncatedTo(ChronoUnit.MICROS);
        this.businessDate = businessDateAt(this.confirmedAt);
    }
    public static LocalDate businessDateAt(Instant instant) {
        return Objects.requireNonNull(instant).atZone(BUSINESS_ZONE).toLocalDate();
    }
    public UUID getId() { return id; }
    public CustomerProfile getCustomer() { return customer; }
    public LoyaltyAccount getAccount() { return account; }
    public Store getStore() { return store; }
    public User getConfirmedBy() { return confirmedBy; }
    public LocalDate getBusinessDate() { return businessDate; }
    public Instant getConfirmedAt() { return confirmedAt; }
}
