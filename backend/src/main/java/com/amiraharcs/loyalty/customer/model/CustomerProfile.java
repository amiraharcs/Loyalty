package com.amiraharcs.loyalty.customer.model;

import com.amiraharcs.loyalty.auth.model.User;
import jakarta.persistence.*;
import java.util.Objects;
import java.util.UUID;

/** Customer presentation only; contains no credentials, loyalty balance or game state. */
@Entity
@Table(name = "customer_profiles", schema = "loyalty")
public class CustomerProfile {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true, updatable = false)
    private User user;
    @Column(name = "display_name", length = 100, nullable = false)
    private String displayName;
    @Column(name = "leaderboard_alias", length = 100, nullable = false)
    private String leaderboardAlias;
    @Column(name = "leaderboard_private", nullable = false)
    private boolean leaderboardPrivate;

    protected CustomerProfile() { }

    public CustomerProfile(User user, String displayName, String leaderboardAlias, boolean leaderboardPrivate) {
        this.user = Objects.requireNonNull(user);
        this.displayName = requireName(displayName);
        this.leaderboardAlias = requireName(leaderboardAlias);
        this.leaderboardPrivate = leaderboardPrivate;
    }

    private static String requireName(String value) {
        if (value == null || value.isBlank() || value.length() > 100) {
            throw new IllegalArgumentException("Profile name must contain 1 to 100 characters");
        }
        return value;
    }

    public UUID getId() { return id; }
    public User getUser() { return user; }
    public String getDisplayName() { return displayName; }
    public String getLeaderboardAlias() { return leaderboardAlias; }
    public boolean isLeaderboardPrivate() { return leaderboardPrivate; }
}
