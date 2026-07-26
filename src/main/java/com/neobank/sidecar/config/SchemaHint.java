package com.neobank.sidecar.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.context.ApplicationListener;

/**
 * Turns the one startup failure every team will hit into an instruction.
 *
 * <p>MySQL runs {@code /docker-entrypoint-initdb.d/*} <b>only when its data directory is
 * empty</b>. Anyone who ran their stack before the sidecar existed already has a populated
 * {@code mysql-data} volume, so the script that creates {@code sidecar_db} and grants
 * {@code appuser} never executes — and this box dies on its first connection with
 * "Unknown database 'sidecar_db'". That reads as "the published image is broken", which sends
 * the whole afternoon in the wrong direction.</p>
 *
 * <p>So the raw JDBC failure gets a sentence attached to it. The fix is one command, and it is
 * safe: the volume holds nothing but a development log.</p>
 */
public class SchemaHint implements ApplicationListener<ApplicationFailedEvent> {

    private static final Logger log = LoggerFactory.getLogger(SchemaHint.class);

    @Override
    public void onApplicationEvent(ApplicationFailedEvent event) {
        String chain = describe(event.getException());
        if (chain.contains("Unknown database")
                || chain.contains("Access denied")
                || chain.contains("CommunicationsException")
                || chain.contains("Communications link failure")) {
            log.error("""

                    ---------------------------------------------------------------------------
                    The sidecar could not reach its schema.

                    Most likely cause: MySQL runs db/init/*.sql only on a FRESH data directory,
                    and yours already existed — so `sidecar_db` was never created and `appuser`
                    was never granted.

                    Fix (safe: the volume holds only a development log):

                        docker compose down -v && docker compose up --build

                    If MySQL simply is not up yet, this resolves itself on the next restart.
                    ---------------------------------------------------------------------------
                    """);
        }
    }

    private static String describe(Throwable error) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = error; current != null; current = current.getCause()) {
            text.append(current).append('\n');
            if (current.getCause() == current) {
                break;
            }
        }
        return text.toString();
    }
}
