package com.fleetflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.fleetflow.events.OutboxPublisher;
import com.fleetflow.events.OutboxStore;
import com.fleetflow.events.EventProjector;
import com.fleetflow.lifecycle.LifecycleHistory;
import jakarta.servlet.Filter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest
@ActiveProfiles("test")
class Phase8AsyncEventsTests extends ProfileTestDatabase {
    private static final KafkaContainer broker = new KafkaContainer("apache/kafka:3.9.1");
    static { broker.start(); }

    @DynamicPropertySource
    static void brokerProperties(DynamicPropertyRegistry registry) {
        registry.add("KAFKA_BOOTSTRAP_SERVERS", broker::getBootstrapServers);
        registry.add("spring.kafka.admin.auto-create", () -> true);
        registry.add("spring.kafka.listener.auto-startup", () -> true);
    }

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxPublisher publisher;
    @Autowired OutboxStore store;
    @Autowired EventProjector projector;
    @Autowired LifecycleHistory history;
    @Autowired PlatformTransactionManager transactions;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaListenerEndpointRegistry listeners;
    MockMvc mvc;

    @BeforeEach
    void setup() throws InterruptedException {
        // The listeners write several of these tables in one transaction. Wait for them to
        // finish before TRUNCATE takes exclusive locks, then resume for the next test.
        CountDownLatch stopped = new CountDownLatch(1);
        listeners.stop(stopped::countDown);
        assertThat(stopped.await(20, TimeUnit.SECONDS)).isTrue();
        try {
            jdbc.execute("truncate notifications, processed_events, quarantined_events, "
                    + "analytics_daily_order_transitions, outbox_events");
        } finally {
            listeners.start();
        }
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new CorrelationIdFilter(), context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    @Test
    void committedLifecycleEventReachesBothIdempotentViewsAndAuthorizedReads() throws Exception {
        Fixture fixture = fixture();
        UUID order = createdOrder(fixture);
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ?",
                Integer.class, order)).isZero();
        confirm(fixture, order);
        UUID event = eventId(order);
        assertThat(jdbc.queryForObject("select status from outbox_events where id = ?", String.class, event))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, order))
                .isEqualTo("CONFIRMED");

        assertThat(publisher.sweep()).isEqualTo(1);
        await(() -> processed("notification", event) && processed("analytics", event));
        assertThat(jdbc.queryForObject("select status from outbox_events where id = ?", String.class, event))
                .isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("select count(*) from notifications where event_id = ?",
                Integer.class, event)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select total from analytics_daily_order_transitions "
                + "where day = ? and status = 'CONFIRMED'", Long.class,
                LocalDate.now(ZoneOffset.UTC))).isGreaterThanOrEqualTo(1L);

        mvc.perform(get("/api/v1/notifications/me").header("Authorization", bearer(fixture.customer())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].orderId").value(order.toString()))
                .andExpect(jsonPath("$.content[0].state").value("CONFIRMED"));
        mvc.perform(get("/api/v1/notifications/me").header("Authorization", bearer(fixture.otherCustomer())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));
        jdbc.update("insert into actor_roles(actor_id,role) values (?, 'DRIVER')",
                fixture.otherCustomer().id());
        mvc.perform(get("/api/v1/notifications/me").header("Authorization", bearer(fixture.otherCustomer())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));
        mvc.perform(get("/api/v1/dispatch/analytics/daily")
                .param("from", LocalDate.now(ZoneOffset.UTC).toString())
                .param("to", LocalDate.now(ZoneOffset.UTC).toString())
                .header("Authorization", bearer(fixture.dispatcher())))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/dispatch/analytics/daily")
                .param("from", LocalDate.now(ZoneOffset.UTC).toString())
                .param("to", LocalDate.now(ZoneOffset.UTC).toString())
                .header("Authorization", bearer(fixture.customer())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/dispatch/analytics/daily")
                .param("from", "2026-10-10").param("to", "2026-10-09")
                .header("Authorization", bearer(fixture.dispatcher())))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/dispatch/analytics/daily")
                .param("from", "2026-09-01").param("to", "2026-10-02")
                .header("Authorization", bearer(fixture.dispatcher())))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/notifications/me").param("limit", "21")
                .header("Authorization", bearer(fixture.customer())))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/notifications/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/metrics/fleetflow.outbox.pending")
                .header("Authorization", bearer(fixture.dispatcher())))
                .andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics/fleetflow.outbox.pending")
                .header("Authorization", bearer(fixture.customer())))
                .andExpect(status().isForbidden());
    }

    @Test
    void brokerSendFailureDoesNotUndoBusinessCommitAndRetryDrains() throws Exception {
        Fixture fixture = fixture();
        UUID order = createdOrder(fixture);
        confirm(fixture, order);
        UUID event = eventId(order);
        var factory = new DefaultKafkaProducerFactory<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 1000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 2000));
        try {
            var disconnectedKafka = new KafkaTemplate<>(factory);
            disconnectedKafka.setProducerListener(new ProducerListener<>() { });
            var disconnected = new OutboxPublisher(store, disconnectedKafka,
                    new SimpleMeterRegistry(), "fleetflow.domain-events.v1", 50,
                    Duration.ofSeconds(30), Duration.ofSeconds(2), 20);
            assertThat(disconnected.sweep()).isZero();
        } finally {
            factory.destroy();
        }
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, order))
                .isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("select status from outbox_events where id = ?", String.class, event))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select attempts from outbox_events where id = ?", Integer.class, event))
                .isEqualTo(1);
        jdbc.update("update outbox_events set next_attempt_at = clock_timestamp() where id = ?", event);
        assertThat(publisher.sweep()).isGreaterThanOrEqualTo(1);
        await(() -> processed("notification", event) && processed("analytics", event));
    }

    @Test
    void duplicateKafkaDeliveryHasOneEffectAndPoisonPayloadIsQuarantined() throws Exception {
        Fixture fixture = fixture();
        UUID order = createdOrder(fixture);
        confirm(fixture, order);
        UUID event = eventId(order);
        assertThat(publisher.sweep()).isGreaterThanOrEqualTo(1);
        await(() -> processed("notification", event) && processed("analytics", event));
        String envelope = jdbc.queryForObject("select envelope::text from outbox_events where id = ?",
                String.class, event);
        kafka.send("fleetflow.domain-events.v1", order.toString(), envelope).get(10, TimeUnit.SECONDS);
        kafka.send("fleetflow.domain-events.v1", order.toString(), "{bad-json")
                .get(10, TimeUnit.SECONDS);
        String unsupported = envelope.replaceAll("\\\"schemaVersion\\\"\\s*:\\s*1",
                "\"schemaVersion\":99");
        assertThat(unsupported).isNotEqualTo(envelope);
        kafka.send("fleetflow.domain-events.v1", order.toString(), unsupported)
                .get(10, TimeUnit.SECONDS);
        await(() -> jdbc.queryForObject("select count(*) from quarantined_events where topic = ?",
                Integer.class, "fleetflow.domain-events.v1") >= 4);
        assertThat(jdbc.queryForObject("select count(*) from quarantined_events "
                + "where error_code = 'INVALID_EVENT_JSON'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from quarantined_events "
                + "where error_code = 'UNSUPPORTED_EVENT_ENVELOPE'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from processed_events where event_id = ?",
                Integer.class, event)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from notifications where event_id = ?",
                Integer.class, event)).isEqualTo(1);
    }

    @Test
    void rollbackAndRejectedCommandLeaveNoExtraOutboxEvent() throws Exception {
        Fixture fixture = fixture();
        UUID order = createdOrder(fixture);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.update("update orders set status = 'CONFIRMED', version = version + 1 where id = ?", order);
            history.order(order, 1, "CREATED", "CONFIRMED", "CONFIRM", Instant.now(),
                    fixture.merchant().id(), null);
            tx.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, order))
                .isEqualTo("CREATED");
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ?",
                Integer.class, order)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from order_lifecycle_history where order_id = ?",
                Integer.class, order)).isZero();

        confirm(fixture, order);
        mvc.perform(post("/api/v1/merchant-branches/{branch}/orders/{order}/confirm", fixture.branch(), order)
                .header("Authorization", bearer(fixture.merchant())))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ?",
                Integer.class, order)).isEqualTo(1);
    }

    @Test
    void orderClaimsWaitForEarlierPublishAndExpiredLeaseCanBeReclaimed() throws Exception {
        Fixture fixture = fixture();
        UUID order = createdOrder(fixture);
        confirm(fixture, order);
        mvc.perform(post("/api/v1/merchant-branches/{branch}/orders/{order}/start-preparation",
                fixture.branch(), order).header("Authorization", bearer(fixture.merchant())))
                .andExpect(status().isOk());
        CyclicBarrier start = new CyclicBarrier(2);
        List<OutboxStore.Claimed> first;
        try (var workers = Executors.newFixedThreadPool(2)) {
            Callable<List<OutboxStore.Claimed>> claim = () -> {
                start.await();
                return store.claim(10, Duration.ofMinutes(1));
            };
            var results = workers.invokeAll(List.of(claim, claim));
            first = java.util.stream.Stream.concat(results.get(0).get().stream(),
                    results.get(1).get().stream()).toList();
        }
        assertThat(first).hasSize(1);
        assertThat(first.getFirst().aggregateId()).isEqualTo(order);
        assertThat(store.claim(10, Duration.ofMinutes(1))).isEmpty();
        jdbc.update("update outbox_events set lease_until = clock_timestamp() - interval '1 second' "
                + "where id = ?", first.getFirst().id());
        var recovered = store.claim(10, Duration.ofMinutes(1));
        assertThat(recovered).hasSize(1);
        assertThat(recovered.getFirst().id()).isEqualTo(first.getFirst().id());
        assertThat(store.published(first.getFirst())).isFalse();
        assertThat(store.published(recovered.getFirst())).isTrue();
        var second = store.claim(10, Duration.ofMinutes(1));
        assertThat(second).hasSize(1);
        assertThat(second.getFirst().id()).isNotEqualTo(first.getFirst().id());
    }

    @Test
    void publisherDrainsSuccessiveOrderVersionsInSequence() throws Exception {
        Fixture fixture = fixture();
        UUID order = createdOrder(fixture);
        confirm(fixture, order);
        mvc.perform(post("/api/v1/merchant-branches/{branch}/orders/{order}/start-preparation",
                fixture.branch(), order).header("Authorization", bearer(fixture.merchant())))
                .andExpect(status().isOk());
        assertThat(publisher.sweep()).isEqualTo(2);
        var events = jdbc.query("select id from outbox_events where aggregate_id = ? order by sequence",
                (rs, row) -> (UUID) rs.getObject("id"), order);
        assertThat(events).hasSize(2);
        await(() -> processed("analytics", events.get(0)) && processed("analytics", events.get(1)));
        assertThat(jdbc.queryForObject("select count(*) from outbox_events "
                + "where aggregate_id = ? and status = 'PUBLISHED'", Integer.class, order)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select total from analytics_daily_order_transitions "
                + "where day = ? and status = 'CONFIRMED'", Long.class,
                LocalDate.now(ZoneOffset.UTC))).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select total from analytics_daily_order_transitions "
                + "where day = ? and status = 'PREPARING'", Long.class,
                LocalDate.now(ZoneOffset.UTC))).isEqualTo(1L);
    }

    @Test
    void acknowledgementBeforePublisherMarkerCanRetryWithoutDuplicateEffects() throws Exception {
        Fixture fixture = fixture();
        UUID order = createdOrder(fixture);
        confirm(fixture, order);
        UUID event = eventId(order);
        var claimed = store.claim(1, Duration.ofMinutes(1));
        assertThat(claimed).hasSize(1);
        kafka.send("fleetflow.domain-events.v1", order.toString(), claimed.getFirst().envelope())
                .get(10, TimeUnit.SECONDS);
        await(() -> processed("notification", event) && processed("analytics", event));
        assertThat(jdbc.queryForObject("select status from outbox_events where id = ?", String.class,
                event)).isEqualTo("PENDING");
        jdbc.update("update outbox_events set lease_until = clock_timestamp() - interval '1 second' "
                + "where id = ?", event);
        assertThat(publisher.sweep()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from processed_events where event_id = ?",
                Integer.class, event)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from notifications where event_id = ?",
                Integer.class, event)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select total from analytics_daily_order_transitions "
                + "where day = ? and status = 'CONFIRMED'", Long.class,
                LocalDate.now(ZoneOffset.UTC))).isEqualTo(1L);
    }

    @Test
    void retentionRemovesOnlyOldFullyProjectedPublishedRows() throws Exception {
        Fixture fixture = fixture();
        UUID publishedOrder = createdOrder(fixture);
        confirm(fixture, publishedOrder);
        UUID event = eventId(publishedOrder);
        assertThat(publisher.sweep()).isEqualTo(1);
        await(() -> processed("notification", event) && processed("analytics", event));
        jdbc.update("update outbox_events set published_at = clock_timestamp() - interval '31 days' "
                + "where id = ?", event);
        UUID pendingOrder = createdOrder(fixture);
        confirm(fixture, pendingOrder);
        assertThat(store.prunePublished(Duration.ofDays(30), 100)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where id = ?", Integer.class,
                event)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from processed_events where event_id = ?",
                Integer.class, event)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from notifications where event_id = ?",
                Integer.class, event)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ? "
                + "and status = 'PENDING'", Integer.class, pendingOrder)).isEqualTo(1);
    }

    @Test
    void failedNotificationProjectionRollsBackItsProcessedMarker() throws Exception {
        Fixture fixture = fixture();
        UUID order = createdOrder(fixture);
        confirm(fixture, order);
        UUID event = eventId(order);
        String envelope = jdbc.queryForObject("select envelope::text from outbox_events where id = ?",
                String.class, event);
        jdbc.update("""
                insert into notifications(id, event_id, recipient_actor_id, order_id, kind, state, created_at)
                values (?, ?, ?, ?, 'ORDER_STATUS_CHANGED', 'CONFIRMED', clock_timestamp())
                """, UUID.randomUUID(), event, fixture.customer().id(), order);
        assertThatThrownBy(() -> projector.notification(envelope))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(processed("notification", event)).isFalse();
        jdbc.update("delete from notifications where event_id = ?", event);
        projector.notification(envelope);
        assertThat(processed("notification", event)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from notifications where event_id = ?",
                Integer.class, event)).isEqualTo(1);
    }

    private boolean processed(String consumer, UUID event) {
        return jdbc.queryForObject("select count(*) from processed_events where consumer_name = ? and event_id = ?",
                Integer.class, consumer, event) == 1;
    }

    private void confirm(Fixture fixture, UUID order) throws Exception {
        mvc.perform(post("/api/v1/merchant-branches/{branch}/orders/{order}/confirm", fixture.branch(), order)
                .header("Authorization", bearer(fixture.merchant())))
                .andExpect(status().isOk());
    }

    private UUID eventId(UUID order) {
        return jdbc.queryForObject("select id from outbox_events where aggregate_id = ? "
                + "and event_type = 'ORDER_STATUS_CHANGED' order by sequence desc limit 1", UUID.class, order);
    }

    private Fixture fixture() {
        Actor customer = actor("CUSTOMER"), other = actor("CUSTOMER"), merchant = actor("MERCHANT"),
                dispatcher = actor("DISPATCHER");
        UUID customerId = UUID.randomUUID(), merchantId = UUID.randomUUID(), branch = UUID.randomUUID();
        jdbc.update("insert into customers(id,actor_id,display_name,created_at,updated_at) "
                + "values (?,?,'Customer',now(),now())", customerId, customer.id());
        jdbc.update("insert into merchants(id,name,active,created_at,updated_at) "
                + "values (?,'Merchant',true,now(),now())", merchantId);
        jdbc.update("""
                insert into merchant_branches(id,merchant_id,name,pickup_line1,pickup_city,
                  pickup_latitude,pickup_longitude,active,created_at,updated_at)
                values (?,?,'Branch','Street','Cairo',30,31,true,now(),now())
                """, branch, merchantId);
        jdbc.update("insert into merchant_staff_memberships(id,actor_id,branch_id,active,created_at) "
                + "values (?,?,?,true,now())", UUID.randomUUID(), merchant.id(), branch);
        return new Fixture(customer, other, merchant, dispatcher, customerId, branch);
    }

    private UUID createdOrder(Fixture fixture) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into orders(id,customer_id,merchant_branch_id,status,customer_name_snapshot,
                  merchant_name_snapshot,branch_name_snapshot,pickup_line1,pickup_city,
                  pickup_latitude,pickup_longitude,delivery_line1,delivery_city,
                  delivery_latitude,delivery_longitude,currency,total_amount,created_at,updated_at)
                values (?,?,?,'CREATED','Customer','Merchant','Branch','Street','Cairo',
                  30,31,'Road','Cairo',30,31,'EGP',1.00,now(),now())
                """, id, fixture.customerId(), fixture.branch());
        return id;
    }

    private Actor actor(String role) {
        Actor actor = new Actor(UUID.randomUUID(), UUID.randomUUID().toString());
        jdbc.update("insert into actors(id,issuer,subject,active,created_at,updated_at) "
                + "values (?,?,?,true,now(),now())", actor.id(), TestOidc.ISSUER, actor.subject());
        jdbc.update("insert into actor_roles(actor_id,role) values (?,?)", actor.id(), role);
        return actor;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        assertThat(condition.getAsBoolean()).as("asynchronous projection completed").isTrue();
    }

    private String bearer(Actor actor) { return "Bearer " + TestOidc.token(actor.subject()); }
    private record Actor(UUID id, String subject) { }
    private record Fixture(Actor customer, Actor otherCustomer, Actor merchant, Actor dispatcher,
                           UUID customerId, UUID branch) { }
}
