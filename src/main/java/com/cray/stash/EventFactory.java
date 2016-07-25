package com.cray.stash;

import com.atlassian.stash.event.RepositoryRefsChangedEvent;
import org.slf4j.Logger;
import com.atlassian.event.api.EventListener;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;

/**
 * This class is the entry point of the plugin, where it all starts. It's responsible for watching all the events
 * that we care about.
 */
public class EventFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger("com.cray.stash.logger");
    private SEPRefChangeEvent sepRefChangeEvent;
    private ExecutorService executorService;

    public EventFactory(SEPRefChangeEvent sepRefChangeEvent, ExecutorService executorService){
        this.sepRefChangeEvent = sepRefChangeEvent;
        this.executorService = executorService;
    }

    @EventListener
    public void onRefChange(final RepositoryRefsChangedEvent event) {
        LOGGER.info("RefChange event occurred.");
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                sepRefChangeEvent.connectRelayAndProcess(event);
            }
        });
    }
}
