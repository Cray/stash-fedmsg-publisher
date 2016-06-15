package com.cray.stash;

import com.atlassian.stash.event.RepositoryRefsChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.atlassian.event.api.EventListener;
import java.util.concurrent.ExecutorService;

/**
 * This class is the entry point of the plugin, where it all starts. It's responsible for watching all the events
 * that we care about.
 */
public class EventFactory {

    private final static Logger log = LoggerFactory.getLogger(EventFactory.class);
    private SEPRefChangeEvent sepRefChangeEvent;
    private ExecutorService executorService;

    public EventFactory(SEPRefChangeEvent sepRefChangeEvent, ExecutorService executorService){
        this.sepRefChangeEvent = sepRefChangeEvent;
        this.executorService = executorService;
    }

    @EventListener
    public void onRefChange(RepositoryRefsChangedEvent event) {
        log.info("RefChange event occurred.");
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                sepRefChangeEvent.processEvent(event);
            }
        });
    }
}
