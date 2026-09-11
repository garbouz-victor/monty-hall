package ru.joyhub.competition.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "competition_player")
public class CompetitionPlayerEntity {

    @Id
    private UUID id;
    @Column(name = "public_id", nullable = false, unique = true)
    private UUID publicId;
    @Column(name = "public_tag", nullable = false, unique = true, length = 12)
    private String publicTag;
    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;
    @Column(name = "credential_hash", nullable = false, unique = true, length = 64)
    private String credentialHash;
    @Column(name = "credential_expires_at", nullable = false)
    private Instant credentialExpiresAt;
    @Column(name = "excluded_from_leaderboard", nullable = false)
    private boolean excludedFromLeaderboard;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected CompetitionPlayerEntity() {
    }

    public static CompetitionPlayerEntity create(
            UUID id, UUID publicId, String publicTag, String displayName,
            String credentialHash, Instant credentialExpiresAt, Instant now
    ) {
        CompetitionPlayerEntity player = new CompetitionPlayerEntity();
        player.id = id;
        player.publicId = publicId;
        player.publicTag = publicTag;
        player.displayName = displayName;
        player.credentialHash = credentialHash;
        player.credentialExpiresAt = credentialExpiresAt;
        player.createdAt = now;
        player.updatedAt = now;
        return player;
    }

    public void rename(String displayName, Instant now) {
        this.displayName = displayName;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getPublicId() { return publicId; }
    public String getPublicTag() { return publicTag; }
    public String getDisplayName() { return displayName; }
    public String getCredentialHash() { return credentialHash; }
    public Instant getCredentialExpiresAt() { return credentialExpiresAt; }
    public boolean isExcludedFromLeaderboard() { return excludedFromLeaderboard; }
}
