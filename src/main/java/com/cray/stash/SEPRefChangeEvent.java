package com.cray.stash;

import com.atlassian.stash.event.RepositoryRefsChangedEvent;
import java.util.List;

/**
 * Created by swalter on 6/9/2016.
 */
public interface SEPRefChangeEvent {
    void processEvent(RepositoryRefsChangedEvent event);
    void sendCommits(List<Message> commitMessages);
}
