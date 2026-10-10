package com.fleetflow.dispatch;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class OfferExpiryService {
    private static final Logger log = LoggerFactory.getLogger(OfferExpiryService.class);
    private final JdbcTemplate jdbc;
    private final RecoveryProperties config;
    private final OfferExpiryCommit commit;

    OfferExpiryService(JdbcTemplate jdbc, RecoveryProperties config, OfferExpiryCommit commit) {
        this.jdbc = jdbc;
        this.config = config;
        this.commit = commit;
    }

    /** A fresh transaction claims each row; a crashed sweep leaves remaining rows due. */
    public int sweep() {
        List<UUID> due = jdbc.query("""
                select id from driver_assignments
                where status = 'OFFERED' and expires_at <= clock_timestamp()
                  and (expiry_retry_at is null or expiry_retry_at <= clock_timestamp())
                order by expires_at, id limit ?
                """, (rs, row) -> (UUID) rs.getObject("id"), config.batchSize());
        int expired = 0;
        for (UUID id : due) {
            try {
                if (commit.expire(id)) expired++;
            } catch (RuntimeException failure) {
                log.atWarn().addKeyValue("event", "offer.expiry_failed")
                        .addKeyValue("offerId", id)
                        .addKeyValue("failureType", failure.getClass().getSimpleName())
                        .log("Offer expiry transaction failed; it remains retryable");
                try {
                    jdbc.update("""
                            update driver_assignments
                            set expiry_retry_at = clock_timestamp() + interval '30 seconds'
                            where id = ? and status = 'OFFERED'
                            """, id);
                } catch (RuntimeException retryFailure) {
                    log.atWarn().addKeyValue("event", "offer.expiry_defer_failed")
                            .addKeyValue("offerId", id)
                            .addKeyValue("failureType", retryFailure.getClass().getSimpleName())
                            .log("Could not defer failed offer expiry");
                }
            }
        }
        return expired;
    }
}
