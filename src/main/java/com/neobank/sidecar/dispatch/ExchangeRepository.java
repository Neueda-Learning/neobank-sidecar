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

    /**
     * The <em>newest</em> dispatch of this application that carried one — what {@code GET
     * /api/v1/applications/{id}} reads back.
     *
     * <p><b>Descending, where the callback matcher above is ascending.</b> Both are right for their
     * own job and the asymmetry is deliberate. Pairing a callback answers "which dispatch provoked
     * this?", and the oldest unanswered one did. Reading an application answers "what does this id
     * mean now?", and after SIM-25 re-sends SIM-01's id that is the most recent thing sent under
     * it.</p>
     *
     * <p>Skips rows with no stored application: an unsolicited callback has an applicationId and
     * nothing behind it, and answering {@code 200} with an empty body would be worse than a
     * {@code 404}.</p>
     */
    Optional<Exchange> findFirstByApplicationIdAndApplicationJsonIsNotNullOrderByIdDesc(
            String applicationId);

    /** Every dispatch that carried an application, newest first — the name search reads these. */
    List<Exchange> findByApplicationJsonIsNotNullOrderByIdDesc();
}
