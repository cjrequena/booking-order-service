package com.cjrequena.core.eventstore.service;

import com.cjrequena.core.eventstore.domain.aggregate.Aggregate;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AggregateFactory {


    @SneakyThrows(ReflectiveOperationException.class)
    @SuppressWarnings("unchecked")
    public <T extends Aggregate> T newInstance( Class<? extends Aggregate> aggregateClass, UUID aggregateId) {
        var constructor = aggregateClass.getDeclaredConstructor(UUID.class, Long.TYPE);
        return (T) constructor.newInstance(aggregateId, 0);
    }
}
