package com.Url_Shortener.repository;

import com.Url_Shortener.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    User save(User user);

    /**
     * Counts one more link against the user's paid-period limit, in a single
     * statement so concurrent requests cannot exceed it. A different
     * periodStart than the stored one means a new period: the count restarts.
     *
     * @return 1 if counted, 0 if the limit was already reached
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE users
               SET period_links_used = CASE WHEN period_links_start = :periodStart
                                            THEN period_links_used + 1 ELSE 1 END,
                   period_links_start = :periodStart
             WHERE id = :userId
               AND (period_links_start IS NULL OR period_links_start <> :periodStart
                    OR period_links_used < :limit)
            """, nativeQuery = true)
    int reservePeriodLink(@Param("userId") Long userId, @Param("periodStart") LocalDateTime periodStart,
                          @Param("limit") int limit);

    /** Gives back a link counted by reservePeriodLink whose creation then failed. */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE users SET period_links_used = period_links_used - 1
             WHERE id = :userId AND period_links_start = :periodStart AND period_links_used > 0
            """, nativeQuery = true)
    int releasePeriodLink(@Param("userId") Long userId, @Param("periodStart") LocalDateTime periodStart);
}
