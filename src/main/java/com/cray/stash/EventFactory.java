package com.cray.stash;

import com.atlassian.stash.event.RepositoryRefsChangedEvent;
import org.slf4j.Logger;
import com.atlassian.event.api.EventListener;
import java.util.concurrent.ExecutorService;

/**
 * This class is the entry point of the plugin, where it all starts. It's responsible for watching all the events
 * that we care about.
 */
public class EventFactory {

    private  final Logger LOGGER;
    private SEPRefChangeEvent sepRefChangeEvent;
    private ExecutorService executorService;

    public EventFactory(SEPRefChangeEvent sepRefChangeEvent, ExecutorService executorService, EventLoggerFactory eventLoggerFactory){
        this.sepRefChangeEvent = sepRefChangeEvent;
        this.executorService = executorService;
        this.LOGGER = eventLoggerFactory.getLoggerForThis(this);
    }

    @EventListener
    public void onRefChange(final RepositoryRefsChangedEvent event) {
        LOGGER.info("RefChange event occurred.");
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                sepRefChangeEvent.processEvent(event);
            }
        });
    }
}
