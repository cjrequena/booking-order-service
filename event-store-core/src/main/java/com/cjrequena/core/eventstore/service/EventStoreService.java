package com.cjrequena.core.eventstore.service;


import com.cjrequena.core.eventstore.common.util.JsonUtil;
import com.cjrequena.core.eventstore.configuration.EventStoreConfigurationProperties;
import com.cjrequena.core.eventstore.domain.aggregate.Aggregate;
import com.cjrequena.core.eventstore.domain.event.Event;
import com.cjrequena.core.eventstore.entity.AbstractEventEntity;
import com.cjrequena.core.eventstore.entity.AggregateSnapshotEntity;
import com.cjrequena.core.eventstore.entity.EventEntity;
import com.cjrequena.core.eventstore.entity.EventSubscriptionEntity;
import com.cjrequena.core.eventstore.exception.service.EventStoreOptimisticConcurrencyServiceException;
import com.cjrequena.core.eventstore.repository.AggregateRepository;
import com.cjrequena.core.eventstore.repository.AggregateSnapshotRepository;
import com.cjrequena.core.eventstore.repository.EventRepository;
import com.cjrequena.core.eventstore.repository.EventSubscriptionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
@Log4j2
public class EventStoreService {

  private final AggregateRepository aggregateRepository;
  private final AggregateSnapshotRepository aggregateSnapshotRepository;
  private final EventRepository eventRepository;
  private final EventSubscriptionRepository eventSubscriptionRepository;
  private final EventStoreConfigurationProperties eventStoreConfigurationProperties;
  private final ObjectMapper objectMapper;

  @SneakyThrows
  public void saveAggregate(Aggregate aggregate) throws EventStoreOptimisticConcurrencyServiceException {
    String aggregateType = aggregate.getAggregateType();
    UUID aggregateId = aggregate.getAggregateId();

    // Create new aggregate if it does not exist.
    this.aggregateRepository.createAggregateIfAbsent(aggregateId, aggregateType);

    long expectedAggregateVersion = aggregate.getReproducedAggregateVersion();
    long newAggregateVersion = aggregate.getAggregateVersion();

    Optional<Integer> isVersionUpdated = aggregateRepository.verifyAndUpdateAggregateVersionIfMatch(aggregateId, expectedAggregateVersion, newAggregateVersion);
    if (isVersionUpdated.isEmpty()) {
      String errorMessage = String.format(
        "Optimistic concurrency conflict detected for aggregate '%s' with ID '%s'. The current expected version does not match the version '%s'.",
        aggregateType,
        aggregateId,
        expectedAggregateVersion
      );
      log.warn(errorMessage);
      throw new EventStoreOptimisticConcurrencyServiceException(errorMessage);
    }

    // Append new events
    List<Event> unconfirmedEventsPool = aggregate.getUnconfirmedEventsPool();
    for (Event event : unconfirmedEventsPool) {
      log.info("Appending {} event: {}", aggregateType, event);
      AbstractEventEntity eventEntity = event.mapToEventEntity();
      eventRepository.save(eventEntity);
    }

    // Create snapshot
    EventStoreConfigurationProperties.SnapshotProperties snapshotProperties = eventStoreConfigurationProperties.getSnapshot(aggregateType);
    boolean shouldCreateSnapshot = aggregate.getAggregateVersion() % snapshotProperties.interval() == 0 || unconfirmedEventsPool.size() >= snapshotProperties.interval();
    if (shouldCreateSnapshot) {
      log.info("Creating {} aggregate {} version {} snapshot", aggregate.getAggregateType(), aggregate.getAggregateId(), aggregate.getAggregateVersion());
      AggregateSnapshotEntity aggregateSnapshotEntity = AggregateSnapshotEntity.builder()
        .aggregateId(aggregate.getAggregateId())
        .aggregateVersion(aggregate.getAggregateVersion())
        .aggregateType(aggregate.getAggregateType())
        .data(this.objectMapper.writeValueAsString(aggregate))
        .build();
      this.aggregateSnapshotRepository.save(aggregateSnapshotEntity);
    }
  }

  @Transactional(readOnly = true)
  public Optional<Aggregate> retrieveAggregateSnapshot(Class<? extends Aggregate> aggregateClass, UUID aggregateId, @Nullable Long aggregateVersion) {
    log.info("Retrieving aggregate snapshot for aggregate {}with ID {}", aggregateClass, aggregateId);

    return Optional.ofNullable(aggregateSnapshotRepository.retrieveAggregateSnapshot(aggregateId, aggregateVersion))
      .map(aggregateSnapshotEntity -> fromSnapshotToAggregate(aggregateSnapshotEntity, aggregateClass));
  }

  @Transactional(readOnly = true)
  public List<EventEntity> retrieveEventsByAggregateId(UUID aggregateId, @Nullable Long fromAggregateVersion, @Nullable Long toAggregateVersion) {
    if (log.isInfoEnabled()) {
      if (toAggregateVersion != null) {
        log.info("Retrieving aggregate events for aggregatewith ID {} from version {} to version {}", aggregateId, fromAggregateVersion, toAggregateVersion);
      } else {
        log.info("Retrieving aggregate events for aggregatewith ID {} from version {}", aggregateId, fromAggregateVersion);
      }
    }
    // Validate input
    if (aggregateId == null) {
      throw new IllegalArgumentException("aggregateId cannot be null");
    }
    // Query the repository, with optional parameters for version range
    return eventRepository.retrieveEventsByAggregateId(aggregateId, fromAggregateVersion, toAggregateVersion);
  }

  @Transactional(readOnly = true)
  public boolean verifyIfAggregateExist(@Nonnull UUID aggregateId, @Nonnull String aggregateType) {
    return this.aggregateRepository.verifyIfAggregateExist(aggregateId, aggregateType);
  }

  public void registerNewSubscriptionIfAbsent(@Nonnull String subscriptionName) {
    this.eventSubscriptionRepository.registerNewSubscriptionIfAbsent(subscriptionName);
  }

  @Transactional(readOnly = true)
  public Optional<EventSubscriptionEntity> retrieveEventSubscriptionAndLockSubscriptionOffset(String subscriptionName) {
    return this.eventSubscriptionRepository.retrieveEventSubscriptionAndLockSubscriptionOffset(subscriptionName);
  }

  @Transactional(readOnly = true)
  public List<EventEntity> retrieveEventsByAggregateTypeAfterOffsetTxIAndOffsetId(@Nonnull String aggregateType, @Nonnull Long offsetTxId, Long offsetId) {
    return this.eventRepository.retrieveEventsByAggregateTypeAfterOffsetTxIAndOffsetId(aggregateType, offsetTxId, offsetId);
  }

  public boolean updateEventSubscription(@Nonnull String subscriptionName, @Nonnull Long offsetTxId, @Nonnull Long offsetId){
    final int rowsUpdated = this.eventSubscriptionRepository.updateEventSubscription(subscriptionName, offsetTxId, offsetId);
    return rowsUpdated > 0;
  }

  @SneakyThrows
  private Aggregate fromSnapshotToAggregate(AggregateSnapshotEntity aggregateSnapshotEntity, Class<? extends Aggregate> aggregateClass) {
    String json = aggregateSnapshotEntity.getData();
    final Aggregate aggregate = JsonUtil.jsonStringToObject(json, aggregateClass);
    aggregate.setReproducedAggregateVersion(aggregate.getAggregateVersion());
    return aggregate;
  }

}
