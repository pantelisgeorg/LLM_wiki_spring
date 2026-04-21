package com.wiki.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Component;

/**
 * Fires once the Spring app is fully up. If the qmd daemon isn't answering,
 * tries to start it. Runs async so app startup isn't blocked by the 15s poll.
 * Disable with `qmd.auto-start: false`.
 */
@Component
@EnableAsync
public class QmdBootProbe {
    private static final Logger log = LoggerFactory.getLogger(QmdBootProbe.class);

    private final QmdClient qmd;
    private final boolean autoStart;

    public QmdBootProbe(QmdClient qmd, @Value("${qmd.auto-start:true}") boolean autoStart) {
        this.qmd = qmd;
        this.autoStart = autoStart;
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!autoStart) {
            log.debug("qmd auto-start disabled");
            return;
        }
        if (qmd.isHealthy()) {
            log.info("qmd daemon already running");
            return;
        }
        qmd.tryStart();
    }
}
