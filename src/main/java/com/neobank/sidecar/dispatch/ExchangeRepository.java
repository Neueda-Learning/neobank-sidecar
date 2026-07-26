package com.neobank.sidecar.dispatch;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExchangeRepository extends JpaRepository<Exchange, Long> {

    List<Exchange> findAllByOrderByIdDesc();

    /**
     * The <em>oldest</em> still-unanswered dispatch of this application — first in, first
     * answered.
     *
     * <p>applicationId is all there is to match on: the callback carries exactly four fields
     * ({@code applicationId, serviceId, status, comment}) and no correlation id, so a
     * cleverer key is not available. Ascending order is what makes that correct. SIM-25
     * deliberately re-sends SIM-01's applicationId, so two dispatch rows share it and two
     * callbacks arrive; oldest-first pairs each callback with the dispatch that provoked it,
     * where newest-first would pair them in reverse and make the log look shuffled.</p>
     */
    Optional<Exchange> findFirstByApplicationIdAndCallbackAtIsNullOrderByIdAsc(String applicationId);
}
