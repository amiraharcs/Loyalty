package com.amiraharcs.loyalty.loyalty.service;

import com.amiraharcs.loyalty.common.api.ResourceNotFoundException;
import com.amiraharcs.loyalty.common.api.ConflictException;
import com.amiraharcs.loyalty.loyalty.model.PointTransaction;
import com.amiraharcs.loyalty.visit.model.Visit;
import com.amiraharcs.loyalty.auth.security.RoleAuthorities;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import com.amiraharcs.loyalty.customer.model.CustomerProfile;
import com.amiraharcs.loyalty.loyalty.model.LoyaltyAccount;
import com.amiraharcs.loyalty.loyalty.model.PointValues;
import com.amiraharcs.loyalty.loyalty.repository.LoyaltyAccountRepository;
import com.amiraharcs.loyalty.loyalty.repository.PointTransactionRepository;
import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Internal module collaboration only. Future HTTP callers must authorize customer ownership first. */
@Service
@Transactional(readOnly = true)
public class LoyaltyAccountService {
    private final LoyaltyAccountRepository accounts;
    private final PointTransactionRepository transactions;
    public LoyaltyAccountService(LoyaltyAccountRepository accounts, PointTransactionRepository transactions) {
        this.accounts = accounts;
        this.transactions = transactions;
    }
    public LoyaltyAccount resolve(UUID customerId) {
        return accounts.findByCustomerId(customerId).orElseThrow(ResourceNotFoundException::new);
    }
    public long currentBalance(UUID customerId) {
        return PointValues.safeInteger(new BigInteger(transactions.sumByAccountId(resolve(customerId).getId())));
    }
    public Page<PointTransactionSummary> history(UUID customerId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("History page size must be between 1 and 100");
        }
        return transactions.findByAccountIdOrderByCreatedAtDescIdDesc(resolve(customerId).getId(), PageRequest.of(page, size))
                .map(entry -> new PointTransactionSummary(entry.getId(),
                        PointValues.safeInteger(BigInteger.valueOf(entry.getSignedAmount())), entry.getType(), entry.getCreatedAt()));
    }
    /** The caller owns the transaction so the lock remains held through its future mutation and commit. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LoyaltyAccount lockForMutation(UUID customerId) {
        return accounts.lockByCustomerId(customerId).orElseThrow(ResourceNotFoundException::new);
    }
    /** Internal visit composition; the caller must already hold the account lock and own the transaction. */
    @PreAuthorize(RoleAuthorities.REQUIRES_STAFF)
    @Transactional(propagation = Propagation.MANDATORY)
    public long recordVisitEarn(Visit visit) {
        try {
            transactions.append(PointTransaction.forVisit(visit));
        } catch (RuntimeException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && "23505".equals(violation.getSQLState())
                        && "uq_point_transactions_visit_earn".equals(violation.getConstraintName())) {
                    throw new ConflictException();
                }
            }
            throw exception;
        }
        return currentBalance(visit.getCustomer().getId());
    }

    /** Registration's existing transaction also owns identity, role and profile persistence. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LoyaltyAccount createForRegistration(CustomerProfile customer, Instant createdAt) {
        return accounts.save(new LoyaltyAccount(customer, createdAt));
    }
}
